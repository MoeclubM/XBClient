package moe.telecom.xbclient.tauri.mobile

import android.app.Activity
import android.content.pm.PackageManager
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions
import java.util.concurrent.atomic.AtomicBoolean

@InvokeArg
class RewardedAdArgs {
    lateinit var adUnitId: String
    var userId: String = ""
    var customData: String = ""
}

@InvokeArg
class AppOpenAdArgs {
    lateinit var adUnitId: String
}

@TauriPlugin
class XbClientMobilePlugin(private val activity: Activity) : Plugin(activity) {
    private var initialized = false

    @Command
    fun showRewardedAd(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(RewardedAdArgs::class.java)
            initializeAdMob()
            activity.runOnUiThread {
                RewardedAd.load(
                    AdRequest.Builder(args.adUnitId).build(),
                    object : AdLoadCallback<RewardedAd> {
                        override fun onAdLoaded(ad: RewardedAd) {
                            showRewarded(ad, args, invoke)
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            invoke.reject("Rewarded ad failed to load: $adError")
                        }
                    }
                )
            }
        } catch (error: Exception) {
            invoke.reject(error.message)
        }
    }

    @Command
    fun showAppOpenAd(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(AppOpenAdArgs::class.java)
            initializeAdMob()
            activity.runOnUiThread {
                AppOpenAd.load(
                    AdRequest.Builder(args.adUnitId).build(),
                    object : AdLoadCallback<AppOpenAd> {
                        override fun onAdLoaded(ad: AppOpenAd) {
                            showAppOpen(ad, invoke)
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            invoke.reject("App open ad failed to load: $adError")
                        }
                    }
                )
            }
        } catch (error: Exception) {
            invoke.reject(error.message)
        }
    }

    private fun initializeAdMob() {
        if (initialized) return
        val appId = activity.packageManager
            .getApplicationInfo(activity.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString("moe.telecom.xbclient.ADMOB_APP_ID")
            .orEmpty()
        if (appId.isBlank()) {
            throw IllegalStateException("Tauri Android AdMob App ID is missing")
        }
        MobileAds.initialize(activity, InitializationConfig.Builder(appId).build())
        initialized = true
    }

    private fun showRewarded(ad: RewardedAd, args: RewardedAdArgs, invoke: Invoke) {
        val completed = AtomicBoolean(false)
        ad.setServerSideVerificationOptions(
            ServerSideVerificationOptions(
                userId = args.userId,
                customData = args.customData
            )
        )
        ad.setImmersiveMode(false)
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                if (completed.compareAndSet(false, true)) {
                    invoke.reject("Rewarded ad dismissed before reward")
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                if (completed.compareAndSet(false, true)) {
                    invoke.reject("Rewarded ad failed to show: $fullScreenContentError")
                }
            }
        }
        ad.show(
            activity,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(reward: RewardItem) {
                    if (completed.compareAndSet(false, true)) {
                        invoke.resolve(
                            JSObject()
                                .put("earned", true)
                                .put("rewardType", reward.type)
                                .put("rewardAmount", reward.amount)
                        )
                    }
                }
            }
        )
    }

    private fun showAppOpen(ad: AppOpenAd, invoke: Invoke) {
        val completed = AtomicBoolean(false)
        ad.setImmersiveMode(false)
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                if (completed.compareAndSet(false, true)) {
                    invoke.resolve(JSObject().put("shown", true))
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                if (completed.compareAndSet(false, true)) {
                    invoke.reject("App open ad failed to show: $fullScreenContentError")
                }
            }
        }
        ad.show(activity)
    }
}
