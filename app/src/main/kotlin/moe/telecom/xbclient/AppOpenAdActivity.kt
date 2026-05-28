package moe.telecom.xbclient

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class AppOpenAdActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var appOpenAd: AppOpenAd? = null
    private var adShowing = false
    private var nextOpened = false
    private var appOpenLoadStarted = false
    private val configTimeoutRunnable = Runnable {
        if (!appOpenLoadStarted && !nextOpened) {
            Log.w(TAG, "App open config fetch timed out.")
            openMainActivity()
        }
    }
    private val adLoadTimeoutRunnable = Runnable {
        if (!adShowing && !nextOpened) {
            Log.w(TAG, "App open ad load timed out.")
            openMainActivity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LaunchBrandScreen()
        }
        val prefs = getSharedPreferences(XBCLIENT_PREFS, MODE_PRIVATE)
        if (prefs.getString("auth_data", "").orEmpty().isEmpty() || !prefs.getBoolean("language_onboarding_done", false) || !prefs.getBoolean("vpn_disclosure_done", false)) {
            openAuthActivity()
            return
        }
        val cachedEnabled = prefs.getBoolean("app_open_ad_enabled", false)
        val cachedUnitId = prefs.getString("app_open_ad_unit_id", "").orEmpty()
        handler.postDelayed(configTimeoutRunnable, APP_OPEN_CONFIG_TIMEOUT_MS)
        if (cachedEnabled && cachedUnitId.isNotEmpty()) {
            startAppOpenAdLoad(cachedUnitId)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = fetchAppOpenAdConfig(prefs.getString("auth_data", "").orEmpty())
                runOnUiThread {
                    handler.removeCallbacks(configTimeoutRunnable)
                    if (config == null) {
                        if (!appOpenLoadStarted) {
                            openMainActivity()
                        }
                        return@runOnUiThread
                    }
                    val (enabled, unitId) = config
                    prefs.edit()
                        .putBoolean("app_open_ad_enabled", enabled)
                        .putString("app_open_ad_unit_id", unitId)
                        .apply()
                    if (!enabled || unitId.isEmpty()) {
                        if (!appOpenLoadStarted) {
                            openMainActivity()
                        }
                        return@runOnUiThread
                    }
                    startAppOpenAdLoad(unitId)
                }
            } catch (error: Exception) {
                Log.w(TAG, "App open config request failed.", error)
                runOnUiThread {
                    handler.removeCallbacks(configTimeoutRunnable)
                    if (!appOpenLoadStarted) {
                        openMainActivity()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(configTimeoutRunnable)
        handler.removeCallbacks(adLoadTimeoutRunnable)
        appOpenAd = null
        super.onDestroy()
    }

    private fun fetchAppOpenAdConfig(authData: String): Pair<Boolean, String>? {
        val result = XboardApi.request("admob_reward_config", apiUrl(), authData, JSONObject())
        if (!result.optBoolean("ok")) {
            return null
        }
        val body = result.optJSONObject("body") ?: return null
        if (body.optString("status") == "fail") {
            return null
        }
        val data = body.optJSONObject("data") ?: return null
        return data.optBoolean("app_open_ad_enabled") to data.optString("app_open_ad_unit_id")
    }

    private fun startAppOpenAdLoad(adUnitId: String) {
        if (appOpenLoadStarted || nextOpened || isFinishing || adUnitId.isEmpty()) {
            return
        }
        appOpenLoadStarted = true
        handler.removeCallbacks(configTimeoutRunnable)
        handler.removeCallbacks(adLoadTimeoutRunnable)
        handler.postDelayed(adLoadTimeoutRunnable, APP_OPEN_AD_LOAD_TIMEOUT_MS)
        lifecycleScope.launch(Dispatchers.IO) {
            MobileAds.initialize(
                this@AppOpenAdActivity,
                InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()
            )
            runOnUiThread { loadAppOpenAd(adUnitId) }
        }
    }

    private fun loadAppOpenAd(adUnitId: String) {
        if (nextOpened || isFinishing) {
            return
        }
        try {
            AppOpenAd.load(
                AdRequest.Builder(adUnitId).build(),
                object : AdLoadCallback<AppOpenAd> {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        runOnUiThread {
                            if (nextOpened || isFinishing) {
                                return@runOnUiThread
                            }
                            handler.removeCallbacks(adLoadTimeoutRunnable)
                            appOpenAd = ad
                            showAppOpenAd()
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        Log.w(TAG, "App open ad failed to load: $adError")
                        runOnUiThread { openMainActivity() }
                    }
                }
            )
        } catch (error: Exception) {
            Log.w(TAG, "App open ad load crashed.", error)
            openMainActivity()
        }
    }

    private fun showAppOpenAd() {
        val ad = appOpenAd
        if (ad == null) {
            openMainActivity()
            return
        }
        ad.setImmersiveMode(false)
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                runOnUiThread { openMainActivity() }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                Log.w(TAG, "App open ad failed to show: $fullScreenContentError")
                runOnUiThread { openMainActivity() }
            }
        }
        adShowing = true
        ad.show(this)
    }

    private fun openAuthActivity() {
        openNextActivity(AuthActivity::class.java)
    }

    private fun openMainActivity() {
        openNextActivity(MainActivity::class.java)
    }

    private fun apiUrl(): String {
        val value = BuildConfig.DEFAULT_API_URL.trim()
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    private fun openNextActivity(activityClass: Class<*>) {
        if (nextOpened) {
            return
        }
        nextOpened = true
        handler.removeCallbacks(configTimeoutRunnable)
        handler.removeCallbacks(adLoadTimeoutRunnable)
        startActivity(
            Intent(this, activityClass)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private const val TAG = "XBClientAds"
        private const val APP_OPEN_CONFIG_TIMEOUT_MS = 10_000L
        private const val APP_OPEN_AD_LOAD_TIMEOUT_MS = 12_000L
    }
}

@Composable
private fun LaunchBrandScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    XbClientTheme("") {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.96f, animationSpec = tween(260))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier.size(112.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
}
