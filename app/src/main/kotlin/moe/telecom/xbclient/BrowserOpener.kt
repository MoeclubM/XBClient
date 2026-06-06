package moe.telecom.xbclient

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

object BrowserOpener {
    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") {
            throw IllegalStateException("仅支持打开 HTTP(S) URL。")
        }
        val customTabsIntent = CustomTabsIntent.Builder().build()
        if (context !is Activity) {
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        customTabsIntent.launchUrl(context, uri)
    }
}
