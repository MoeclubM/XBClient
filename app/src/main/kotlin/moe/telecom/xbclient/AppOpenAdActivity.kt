package moe.telecom.xbclient

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
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

class AppOpenAdActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var appOpenAd: AppOpenAd? = null
    private var adShowing = false
    private var nextOpened = false
    private val timeoutRunnable = Runnable {
        if (!adShowing) {
            openMainActivity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences(XBCLIENT_PREFS, MODE_PRIVATE)
        val adUnitId = prefs.getString("app_open_ad_unit_id", "").orEmpty()
        if (prefs.getString("auth_data", "").orEmpty().isEmpty() || !prefs.getBoolean("language_onboarding_done", false) || !prefs.getBoolean("vpn_disclosure_done", false)) {
            openAuthActivity()
            return
        }
        if (!prefs.getBoolean("app_open_ad_enabled", false) || adUnitId.isEmpty()) {
            openMainActivity()
            return
        }
        handler.postDelayed(timeoutRunnable, APP_OPEN_AD_SHOW_WINDOW_MS)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                MobileAds.initialize(
                    this@AppOpenAdActivity,
                    InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()
                )
                runOnUiThread { loadAppOpenAd(adUnitId) }
            } catch (_: Exception) {
                runOnUiThread { openMainActivity() }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        appOpenAd = null
        super.onDestroy()
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
                            handler.removeCallbacks(timeoutRunnable)
                            appOpenAd = ad
                            showAppOpenAd()
                        }
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        runOnUiThread { openMainActivity() }
                    }
                }
            )
        } catch (_: Exception) {
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

    private fun openNextActivity(activityClass: Class<*>) {
        if (nextOpened) {
            return
        }
        nextOpened = true
        handler.removeCallbacks(timeoutRunnable)
        startActivity(
            Intent(this, activityClass)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private const val APP_OPEN_AD_SHOW_WINDOW_MS = 4000L
    }
}
