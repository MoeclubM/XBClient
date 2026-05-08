package moe.telecom.xbclient

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: XbClientViewModel by viewModels()
    private var pendingVpnNodeIndex = 0
    private var receiverRegistered = false
    private var rewardedAd: RewardedAd? = null
    private var rewardedAdUnitId = ""
    private var rewardedAdLoading = false
    private var pendingRewardUserId = ""
    private var pendingRewardCustomData = ""
    private var pendingRewardShow = false
    private var appOpenAd: AppOpenAd? = null
    private var appOpenAdUnitId = ""
    private var appOpenAdLoading = false
    private var appOpenAdShowing = false
    private var appOpenAdShown = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.beginVpn(this, pendingVpnNodeIndex)
            } else {
                Toast.makeText(this, "连接权限未授予。", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != XbClientVpnService.ACTION_STATE) {
                return
            }
            viewModel.onVpnStateChanged(
                running = intent.getBooleanExtra(XbClientVpnService.EXTRA_RUNNING, false),
                nodeIndex = intent.getIntExtra(XbClientVpnService.EXTRA_NODE_INDEX, -1),
                error = intent.getStringExtra(XbClientVpnService.EXTRA_ERROR).orEmpty()
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.uiState.value.loaded }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            MobileAds.initialize(
                this@MainActivity,
                InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is XbClientEvent.Message -> Toast.makeText(this@MainActivity, event.text, Toast.LENGTH_SHORT).show()
                        is XbClientEvent.RequestVpnPermission -> requestVpnPermission(event.nodeIndex)
                        is XbClientEvent.ShowRewardAd -> showRewardedAd(event.adUnitId, event.userId, event.customData)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.isLoggedIn && state.appOpenAdEnabled && state.appOpenAdUnitId.isNotEmpty()) {
                        showAppOpenAdOnce(state.appOpenAdUnitId)
                    }
                }
            }
        }
        setContent {
            XbClientApp(viewModel)
        }
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(XbClientVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
        receiverRegistered = true
        val state = viewModel.uiState.value
        if (state.loaded) {
            val running = getSharedPreferences(XBCLIENT_PREFS, MODE_PRIVATE).getBoolean("vpn_running", false)
            if (state.vpnRequested != running) {
                viewModel.onVpnStateChanged(running, -1, "")
            }
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(vpnStateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_SELECT_NODE) {
            viewModel.requestNodeSwitchDialog(connectAfterSelect = true)
            return
        }
        val uri = intent?.data
        if (uri?.scheme == BuildConfig.OAUTH_CALLBACK_SCHEME && uri.host == "oauth") {
            viewModel.handleOAuthCallback(uri)
        }
    }

    private fun loadRewardedAd(adUnitId: String) {
        if (rewardedAdLoading) {
            return
        }
        rewardedAdLoading = true
        rewardedAdUnitId = adUnitId
        RewardedAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) {
                    runOnUiThread {
                        rewardedAd = ad
                        rewardedAdLoading = false
                        if (pendingRewardShow && rewardedAdUnitId == adUnitId) {
                            pendingRewardShow = false
                            showRewardedAd(adUnitId, pendingRewardUserId, pendingRewardCustomData)
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    runOnUiThread {
                        rewardedAd = null
                        rewardedAdLoading = false
                        if (pendingRewardShow) {
                            pendingRewardShow = false
                            Toast.makeText(this@MainActivity, "广告加载失败：${adError.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    private fun showRewardedAd(adUnitId: String, userId: String, customData: String) {
        val ad = rewardedAd
        if (ad == null || rewardedAdUnitId != adUnitId) {
            pendingRewardUserId = userId
            pendingRewardCustomData = customData
            pendingRewardShow = true
            loadRewardedAd(adUnitId)
            Toast.makeText(this, "广告正在加载，请稍后再试。", Toast.LENGTH_SHORT).show()
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
            override fun onAdDismissedFullScreenContent() {
                runOnUiThread {
                    rewardedAd = null
                    loadRewardedAd(adUnitId)
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                runOnUiThread {
                    rewardedAd = null
                    loadRewardedAd(adUnitId)
                    Toast.makeText(this@MainActivity, "广告展示失败：${fullScreenContentError.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        ad.show(
            this,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(reward: RewardItem) {
                    runOnUiThread {
                        viewModel.onRewardAdEarned(reward.amount, reward.type)
                    }
                }
            }
        )
    }

    private fun loadAppOpenAd(adUnitId: String) {
        if (appOpenAdLoading || appOpenAdShown) {
            return
        }
        appOpenAdLoading = true
        appOpenAdUnitId = adUnitId
        AppOpenAd.load(
            AdRequest.Builder(adUnitId).build(),
            object : AdLoadCallback<AppOpenAd> {
                override fun onAdLoaded(ad: AppOpenAd) {
                    runOnUiThread {
                        appOpenAd = ad
                        appOpenAdLoading = false
                        if (!appOpenAdShown && appOpenAdUnitId == adUnitId) {
                            showAppOpenAdOnce(adUnitId)
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    runOnUiThread {
                        appOpenAd = null
                        appOpenAdLoading = false
                        Toast.makeText(this@MainActivity, "开屏广告加载失败：${adError.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun showAppOpenAdOnce(adUnitId: String) {
        if (appOpenAdShown || appOpenAdShowing) {
            return
        }
        val ad = appOpenAd
        if (ad == null || appOpenAdUnitId != adUnitId) {
            loadAppOpenAd(adUnitId)
            return
        }
        ad.setImmersiveMode(false)
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                runOnUiThread {
                    appOpenAd = null
                    appOpenAdShowing = false
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                runOnUiThread {
                    appOpenAd = null
                    appOpenAdShowing = false
                    Toast.makeText(this@MainActivity, "开屏广告展示失败：${fullScreenContentError.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        appOpenAdShown = true
        appOpenAdShowing = true
        ad.show(this)
    }

    private fun requestVpnPermission(nodeIndex: Int) {
        pendingVpnNodeIndex = nodeIndex
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            viewModel.beginVpn(this, pendingVpnNodeIndex)
        }
    }

    companion object {
        const val ACTION_SELECT_NODE = "moe.telecom.xbclient.action.SELECT_NODE"
    }
}
