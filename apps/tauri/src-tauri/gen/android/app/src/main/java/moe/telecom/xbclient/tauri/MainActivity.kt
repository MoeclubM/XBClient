package moe.telecom.xbclient.tauri

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import moe.telecom.xbclient.tauri.mobile.XbClientMobilePlugin

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    captureOAuthCallback(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    captureOAuthCallback(intent)
  }

  private fun captureOAuthCallback(intent: Intent?) {
    val uri = intent?.data
    if (uri?.scheme == BuildConfig.OAUTH_CALLBACK_SCHEME && uri.host == "oauth") {
      XbClientMobilePlugin.captureOAuthCallback(uri.toString())
    }
  }
}
