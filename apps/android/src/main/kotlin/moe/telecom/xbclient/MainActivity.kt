package moe.telecom.xbclient

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: XbClientViewModel by viewModels()
    private lateinit var ads: XbClientAds
    private var pendingVpnNodeIndex = 0
    private var receiverRegistered = false
    private var redirectedToAuth = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.beginVpn(this, pendingVpnNodeIndex)
            } else {
                Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
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
        ads = XbClientAds(this) { viewModel.onRewardAdEarned(it) }
        ads.start()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is XbClientEvent.Message -> Toast.makeText(this@MainActivity, event.text, Toast.LENGTH_SHORT).show()
                        is XbClientEvent.RequestVpnPermission -> requestVpnPermission(event.nodeIndex)
                        is XbClientEvent.ShowRewardAd -> ads.showRewardedAd(event.adUnitId, event.userId, event.customData)
                        is XbClientEvent.OpenExternalUrl -> BrowserOpener.open(this@MainActivity, event.url)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (!state.loaded) {
                        return@collect
                    }
                    applyEdgeToEdge(state.themeMode)
                    if ((!state.isLoggedIn || !state.languageOnboardingDone || !state.vpnDisclosureDone) && !redirectedToAuth) {
                        redirectedToAuth = true
                        startActivity(
                            Intent(this@MainActivity, AuthActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                        return@collect
                    }
                    ads.sync(state)
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
            viewModel.syncAppearanceSettings()
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

    override fun onDestroy() {
        ads.release()
        super.onDestroy()
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

    private fun applyEdgeToEdge(themeMode: String) {
        val darkTheme = when (themeMode) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        if (darkTheme) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            )
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
