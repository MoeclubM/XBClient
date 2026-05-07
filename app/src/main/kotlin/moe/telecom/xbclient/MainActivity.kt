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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        MobileAds.initialize(this) {}
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
            this,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    rewardedAdLoading = false
                    if (pendingRewardShow && rewardedAdUnitId == adUnitId) {
                        pendingRewardShow = false
                        showRewardedAd(adUnitId, pendingRewardUserId, pendingRewardCustomData)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    rewardedAdLoading = false
                    if (pendingRewardShow) {
                        pendingRewardShow = false
                        Toast.makeText(this@MainActivity, "广告加载失败：${error.message}", Toast.LENGTH_SHORT).show()
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
            ServerSideVerificationOptions.Builder()
                .setUserId(userId)
                .setCustomData(customData)
                .build()
        )
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewardedAd(adUnitId)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                loadRewardedAd(adUnitId)
                Toast.makeText(this@MainActivity, "广告展示失败：${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
        ad.show(this) { reward ->
            viewModel.onRewardAdEarned(reward.amount, reward.type)
        }
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
