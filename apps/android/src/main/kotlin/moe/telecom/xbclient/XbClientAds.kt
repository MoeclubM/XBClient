package moe.telecom.xbclient

import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadCallback
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.common.ResponseInfo
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader
import com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class XbClientAds(
    private val activity: ComponentActivity,
    private val onRewardEarned: (String) -> Unit
) : DefaultLifecycleObserver {
    private var adsInitialized = false
    private var adsInitializing = false
    private var startedPlanAdUnitId = ""
    private var startedPointsAdUnitId = ""
    private var startedAppOpenAdUnitId = ""
    private var pendingRewardUserId = ""
    private var pendingRewardCustomData = ""
    private var pendingRewardAdUnitId = ""
    private var pendingRewardShow = false
    private var isShowingFullScreenAd = false
    private var skipNextAppOpen = false

    private val rewardedPreloadCallback = object : PreloadCallback {
        override fun onAdPreloaded(preloadId: String, responseInfo: ResponseInfo) {
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    onRewardedPreloaded(preloadId)
                }
            }
        }

        override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
            activity.runOnUiThread {
                Log.w(TAG, "Rewarded ad failed to preload: $adError")
                if (!activity.isDestroyed && pendingRewardShow && pendingRewardAdUnitId == preloadId) {
                    pendingRewardShow = false
                    Toast.makeText(activity, R.string.reward_ad_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val appOpenPreloadCallback = object : PreloadCallback {
        override fun onAdFailedToPreload(preloadId: String, adError: LoadAdError) {
            Log.w(TAG, "App open ad failed to preload: $adError")
        }
    }

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        initializeAds()
    }

    fun release() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        if (activity.isFinishing && adsInitialized) {
            replaceRewardedPreload(startedPlanAdUnitId, "")
            startedPlanAdUnitId = ""
            replaceRewardedPreload(startedPointsAdUnitId, "")
            startedPointsAdUnitId = ""
            replaceAppOpenPreload(startedAppOpenAdUnitId, "")
            startedAppOpenAdUnitId = ""
        }
    }

    fun sync(state: XbClientUiState) {
        if (!state.loaded || !state.isLoggedIn) {
            if (adsInitialized) {
                replaceRewardedPreload(startedPlanAdUnitId, "")
                replaceRewardedPreload(startedPointsAdUnitId, "")
                replaceAppOpenPreload(startedAppOpenAdUnitId, "")
            }
            startedPlanAdUnitId = ""
            startedPointsAdUnitId = ""
            startedAppOpenAdUnitId = ""
            return
        }
        val planId = if (state.planRewardAdEnabled) state.planRewardedAdUnitId else ""
        val pointsId = if (state.pointsRewardAdEnabled) state.pointsRewardedAdUnitId else ""
        val appOpenId = if (state.appOpenAdEnabled) state.appOpenAdUnitId else ""
        if (!adsInitialized) {
            startedPlanAdUnitId = planId
            startedPointsAdUnitId = pointsId
            startedAppOpenAdUnitId = appOpenId
            initializeAds()
            return
        }
        applyPreloads(planId, pointsId, appOpenId)
    }

    fun showRewardedAd(adUnitId: String, userId: String, customData: String) {
        if (adUnitId.isEmpty() || activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (!adsInitialized) {
            pendingRewardUserId = userId
            pendingRewardCustomData = customData
            pendingRewardAdUnitId = adUnitId
            pendingRewardShow = true
            initializeAds()
            return
        }
        val ad = RewardedAdPreloader.pollAd(adUnitId)
        if (ad == null) {
            pendingRewardUserId = userId
            pendingRewardCustomData = customData
            pendingRewardAdUnitId = adUnitId
            pendingRewardShow = true
            if (RewardedAdPreloader.getConfiguration(adUnitId) == null) {
                replaceRewardedPreload("", adUnitId)
            }
            Toast.makeText(activity, R.string.reward_ad_loading, Toast.LENGTH_SHORT).show()
            return
        }
        pendingRewardShow = false
        isShowingFullScreenAd = true
        ad.setServerSideVerificationOptions(
            ServerSideVerificationOptions(
                userId = userId,
                customData = customData
            )
        )
        ad.setImmersiveMode(false)
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                activity.runOnUiThread {
                    isShowingFullScreenAd = false
                    skipNextAppOpen = true
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                activity.runOnUiThread {
                    Log.w(TAG, "Rewarded ad failed to show: $fullScreenContentError")
                    isShowingFullScreenAd = false
                    skipNextAppOpen = true
                    Toast.makeText(activity, R.string.reward_ad_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
        ad.show(
            activity,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(reward: RewardItem) {
                    activity.runOnUiThread { onRewardEarned(customData) }
                }
            }
        )
    }

    override fun onStart(owner: LifecycleOwner) {
        showAppOpenAdIfAvailable()
    }

    private fun initializeAds() {
        if (adsInitialized || adsInitializing) {
            return
        }
        if (MobileAds.isInitialized) {
            onAdsInitialized()
            return
        }
        adsInitializing = true
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                MobileAds.initialize(
                    activity.applicationContext,
                    InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()
                ) {}
                activity.runOnUiThread {
                    if (!activity.isDestroyed) {
                        onAdsInitialized()
                    }
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    adsInitializing = false
                    Log.w(TAG, "MobileAds initialization failed.", error)
                    if (!activity.isDestroyed && pendingRewardShow) {
                        pendingRewardShow = false
                        Toast.makeText(activity, R.string.reward_ad_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun onAdsInitialized() {
        adsInitialized = true
        adsInitializing = false
        applyPreloads(startedPlanAdUnitId, startedPointsAdUnitId, startedAppOpenAdUnitId)
        if (pendingRewardShow) {
            showRewardedAd(pendingRewardAdUnitId, pendingRewardUserId, pendingRewardCustomData)
        }
    }

    private fun applyPreloads(planId: String, pointsId: String, appOpenId: String) {
        replaceRewardedPreload(startedPlanAdUnitId, planId)
        startedPlanAdUnitId = planId
        replaceRewardedPreload(startedPointsAdUnitId, pointsId)
        startedPointsAdUnitId = pointsId
        replaceAppOpenPreload(startedAppOpenAdUnitId, appOpenId)
        startedAppOpenAdUnitId = appOpenId
    }

    private fun replaceRewardedPreload(previous: String, next: String) {
        if (previous == next) {
            if (next.isEmpty() || RewardedAdPreloader.getConfiguration(next) != null) {
                return
            }
        } else if (previous.isNotEmpty()) {
            RewardedAdPreloader.destroy(previous)
        }
        if (next.isEmpty()) {
            return
        }
        if (RewardedAdPreloader.getConfiguration(next) != null) {
            RewardedAdPreloader.destroy(next)
        }
        RewardedAdPreloader.start(
            next,
            PreloadConfiguration(AdRequest.Builder(next).build()),
            rewardedPreloadCallback
        )
    }

    private fun replaceAppOpenPreload(previous: String, next: String) {
        if (previous == next) {
            if (next.isEmpty() || AppOpenAdPreloader.getConfiguration(next) != null) {
                return
            }
        } else if (previous.isNotEmpty()) {
            AppOpenAdPreloader.destroy(previous)
        }
        if (next.isEmpty()) {
            return
        }
        if (AppOpenAdPreloader.getConfiguration(next) != null) {
            AppOpenAdPreloader.destroy(next)
        }
        AppOpenAdPreloader.start(
            next,
            PreloadConfiguration(AdRequest.Builder(next).build()),
            appOpenPreloadCallback
        )
    }

    private fun onRewardedPreloaded(preloadId: String) {
        if (pendingRewardShow && pendingRewardAdUnitId == preloadId) {
            pendingRewardShow = false
            showRewardedAd(preloadId, pendingRewardUserId, pendingRewardCustomData)
        }
    }

    private fun showAppOpenAdIfAvailable() {
        if (isShowingFullScreenAd) {
            return
        }
        if (skipNextAppOpen) {
            skipNextAppOpen = false
            return
        }
        if (!adsInitialized || activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return
        }
        val adUnitId = startedAppOpenAdUnitId
        if (adUnitId.isEmpty() || !AppOpenAdPreloader.isAdAvailable(adUnitId)) {
            return
        }
        val ad = AppOpenAdPreloader.pollAd(adUnitId) ?: return
        isShowingFullScreenAd = true
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                activity.runOnUiThread {
                    isShowingFullScreenAd = false
                    skipNextAppOpen = true
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                activity.runOnUiThread {
                    Log.w(TAG, "App open ad failed to show: $fullScreenContentError")
                    isShowingFullScreenAd = false
                    skipNextAppOpen = true
                }
            }
        }
        ad.show(activity)
    }

    companion object {
        private const val TAG = "XBClientAds"
    }
}
