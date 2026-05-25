package moe.telecom.xbclient.tauri.mobile

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.webkit.WebView
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import app.tauri.annotation.ActivityCallback
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
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import moe.telecom.xbclient.XbClientVpnService

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

@InvokeArg
class VpnArgs {
    lateinit var nodeJson: String
    lateinit var nodesJson: String
    var nodeIndex: Int = 0
    var excludedApps: String = ""
    var allowedApps: String = ""
    var nodeDns: String = ""
    var overseasDns: String = ""
    var directDns: String = ""
    var dnsMode: String = ""
    var virtualDnsPool: String = ""
    var ipv6Enabled: Boolean = true
}

@TauriPlugin
class XbClientMobilePlugin(private val activity: Activity) : Plugin(activity) {
    companion object {
        private val oauthCallbackUrl = AtomicReference("")

        fun captureOAuthCallback(url: String?) {
            if (!url.isNullOrBlank()) {
                oauthCallbackUrl.set(url)
            }
        }
    }

    private var initialized = false
    private var pendingVpnIntent: Intent? = null

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == XbClientVpnService.ACTION_STATE) {
                val running = intent.getBooleanExtra(XbClientVpnService.EXTRA_RUNNING, false)
                val nodeIndex = intent.getIntExtra(XbClientVpnService.EXTRA_NODE_INDEX, -1)
                val error = intent.getStringExtra(XbClientVpnService.EXTRA_ERROR).orEmpty()
                val nodeName = intent.getStringExtra(XbClientVpnService.EXTRA_NODE_NAME).orEmpty()

                val payload = JSObject()
                    .put("running", running)
                    .put("nodeIndex", nodeIndex)
                    .put("nodeName", nodeName)
                    .put("error", error)

                trigger("vpnStateChanged", payload)
            }
        }
    }

    override fun load(webView: WebView) {
        super.load(webView)
        webView.settings.setSupportZoom(false)
        webView.settings.builtInZoomControls = false
        webView.settings.displayZoomControls = false
        val filter = IntentFilter(XbClientVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(vpnStateReceiver, filter)
        }
    }

    override fun onDestroy(activity: AppCompatActivity) {
        activity.unregisterReceiver(vpnStateReceiver)
    }

    @Command
    fun startVpn(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(VpnArgs::class.java)
            val intent = Intent(activity, XbClientVpnService::class.java).apply {
                action = XbClientVpnService.ACTION_START
                putExtra(XbClientVpnService.EXTRA_NODE, args.nodeJson)
                putExtra(XbClientVpnService.EXTRA_NODES, args.nodesJson)
                putExtra(XbClientVpnService.EXTRA_NODE_INDEX, args.nodeIndex)
                putExtra(XbClientVpnService.EXTRA_EXCLUDED_APPS, args.excludedApps)
                putExtra(XbClientVpnService.EXTRA_ALLOWED_APPS, args.allowedApps)
                putExtra(XbClientVpnService.EXTRA_NODE_DNS, args.nodeDns)
                putExtra(XbClientVpnService.EXTRA_OVERSEAS_DNS, args.overseasDns)
                putExtra(XbClientVpnService.EXTRA_DIRECT_DNS, args.directDns)
                putExtra(XbClientVpnService.EXTRA_DNS_MODE, args.dnsMode)
                putExtra(XbClientVpnService.EXTRA_VIRTUAL_DNS_POOL, args.virtualDnsPool)
                putExtra(XbClientVpnService.EXTRA_IPV6_ENABLED, args.ipv6Enabled)
            }

            val prepare = VpnService.prepare(activity)
            if (prepare != null) {
                pendingVpnIntent = intent
                startActivityForResult(invoke, prepare, "vpnPermissionResult")
            } else {
                activity.startService(intent)
                invoke.resolve(JSObject().put("started", true))
            }
        } catch (error: Exception) {
            invoke.reject(error.message)
        }
    }

    @Command
    fun stopVpn(invoke: Invoke) {
        val intent = Intent(activity, XbClientVpnService::class.java).apply {
            action = XbClientVpnService.ACTION_STOP
        }
        activity.startService(intent)
        invoke.resolve(JSObject().put("stopped", true))
    }

    @Command
    fun getVpnState(invoke: Invoke) {
        val prefs = activity.getSharedPreferences("xbclient", Context.MODE_PRIVATE)
        val running = prefs.getBoolean("vpn_running", false)
        val nodeIndex = prefs.getInt("vpn_node_index", -1)
        invoke.resolve(JSObject().put("running", running).put("nodeIndex", nodeIndex))
    }

    @Command
    fun listInstalledApps(invoke: Invoke) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val items = JSONArray()
        activity.packageManager.queryIntentActivities(intent, 0)
            .map { info -> info.loadLabel(activity.packageManager).toString() to info.activityInfo.packageName }
            .filter { (_, packageName) -> packageName != activity.packageName }
            .distinctBy { (_, packageName) -> packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { (label, _) -> label })
            .forEach { (label, packageName) ->
                items.put(JSObject().put("label", label).put("packageName", packageName))
            }
        invoke.resolve(JSObject().put("apps", items))
    }

    @ActivityCallback
    fun vpnPermissionResult(invoke: Invoke, result: ActivityResult) {
        val intent = pendingVpnIntent
        pendingVpnIntent = null
        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            activity.startService(intent)
            invoke.resolve(JSObject().put("started", true))
        } else {
            invoke.reject("VPN permission denied")
        }
    }

    @Command
    fun takeOAuthCallback(invoke: Invoke) {
        val url = oauthCallbackUrl.getAndSet("")
        invoke.resolve(JSObject().put("url", url))
    }

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
