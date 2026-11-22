package moe.telecom.xbclient

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.ads.mobile.sdk.MobileAds
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
    private val onRewardLoadingChanged: (Boolean) -> Unit,
    private val onRewardEarned: (String) -> Unit
) {
    private var adsInitialized = false
    private var adsInitializing = false
    private var startedPlanAdUnitId = ""
    private var startedPointsAdUnitId = ""
    private var pendingRewardUserId = ""
    private var pendingRewardCustomData = ""
    private var pendingRewardAdUnitId = ""
    private var pendingRewardShow = false
    private val hasConfiguredAds: Boolean
        get() = PxBoardApi.enabled && BuildConfig.ADMOB_APP_ID.isNotEmpty() && (
            BuildConfig.PLAN_REWARDED_AD_UNIT_ID.isNotEmpty() ||
                BuildConfig.POINTS_REWARDED_AD_UNIT_ID.isNotEmpty()
            )

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
                    clearPendingReward()
                    Toast.makeText(
                        activity,
                        "${activity.getString(R.string.reward_ad_unavailable)}\n${adError.code}: ${adError.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun start() {
        if (!hasConfiguredAds) return
        initializeAds()
    }

    fun release() {
        if (!hasConfiguredAds) return
        clearPendingReward()
        if (activity.isFinishing && adsInitialized) {
            replaceRewardedPreload(startedPlanAdUnitId, "")
            startedPlanAdUnitId = ""
            replaceRewardedPreload(startedPointsAdUnitId, "")
            startedPointsAdUnitId = ""
        }
    }

    fun sync(state: XbClientUiState) {
        if (!hasConfiguredAds) return
        if (!state.loaded || !state.isLoggedIn) {
            if (adsInitialized) {
                replaceRewardedPreload(startedPlanAdUnitId, "")
                replaceRewardedPreload(startedPointsAdUnitId, "")
            }
            startedPlanAdUnitId = ""
            startedPointsAdUnitId = ""
            return
        }
        val planId = if (state.planRewardAdEnabled) state.planRewardedAdUnitId else ""
        val pointsId = if (state.pointsRewardAdEnabled) state.pointsRewardedAdUnitId else ""
        if (!adsInitialized) {
            startedPlanAdUnitId = planId
            startedPointsAdUnitId = pointsId
            initializeAds()
            return
        }
        applyPreloads(planId, pointsId)
    }

    fun showRewardedAd(adUnitId: String, userId: String, customData: String) {
        if (!hasConfiguredAds || adUnitId.isEmpty() || activity.isFinishing || activity.isDestroyed) {
            clearPendingReward()
            return
        }
        if (!adsInitialized) {
            pendingRewardUserId = userId
            pendingRewardCustomData = customData
            pendingRewardAdUnitId = adUnitId
            pendingRewardShow = true
            onRewardLoadingChanged(true)
            initializeAds()
            return
        }
        val ad = RewardedAdPreloader.pollAd(adUnitId)
        if (ad == null) {
            pendingRewardUserId = userId
            pendingRewardCustomData = customData
            pendingRewardAdUnitId = adUnitId
            pendingRewardShow = true
            onRewardLoadingChanged(true)
            if (RewardedAdPreloader.getConfiguration(adUnitId) == null) {
                replaceRewardedPreload("", adUnitId)
            }
            return
        }
        clearPendingReward()
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return
        }
        ad.setServerSideVerificationOptions(
            ServerSideVerificationOptions(
                userId = userId,
                customData = customData
            )
        )
        ad.setImmersiveMode(false)
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                activity.runOnUiThread {
                    Log.w(TAG, "Rewarded ad failed to show: $fullScreenContentError")
                    Toast.makeText(
                        activity,
                        "${activity.getString(R.string.reward_ad_unavailable)}\n${fullScreenContentError.code}: ${fullScreenContentError.message}",
                        Toast.LENGTH_LONG
                    ).show()
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

    fun cancelPendingRewardAd() {
        clearPendingReward()
    }

    private fun initializeAds() {
        if (!hasConfiguredAds || adsInitialized || adsInitializing) {
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
                    InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID)
                        .setExtras(Bundle().apply { putBoolean("force_use_cronet", true) })
                        .build()
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
                        clearPendingReward()
                        Toast.makeText(
                            activity,
                            "${activity.getString(R.string.reward_ad_unavailable)}\n${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun onAdsInitialized() {
        adsInitialized = true
        adsInitializing = false
        applyPreloads(startedPlanAdUnitId, startedPointsAdUnitId)
        if (pendingRewardShow) {
            showRewardedAd(pendingRewardAdUnitId, pendingRewardUserId, pendingRewardCustomData)
        }
    }

    private fun applyPreloads(planId: String, pointsId: String) {
        replaceRewardedPreload(startedPlanAdUnitId, planId)
        startedPlanAdUnitId = planId
        replaceRewardedPreload(startedPointsAdUnitId, pointsId)
        startedPointsAdUnitId = pointsId
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

    private fun onRewardedPreloaded(preloadId: String) {
        if (pendingRewardShow && pendingRewardAdUnitId == preloadId) {
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                clearPendingReward()
                return
            }
            showRewardedAd(preloadId, pendingRewardUserId, pendingRewardCustomData)
        }
    }

    private fun clearPendingReward() {
        pendingRewardShow = false
        pendingRewardAdUnitId = ""
        pendingRewardUserId = ""
        pendingRewardCustomData = ""
        onRewardLoadingChanged(false)
    }

    companion object {
        private const val TAG = "XBClientAds"
    }
}
