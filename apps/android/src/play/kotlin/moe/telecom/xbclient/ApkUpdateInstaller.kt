package moe.telecom.xbclient

import android.content.Context
import java.io.File

object ApkUpdateInstaller {
    fun downloadApk(url: String, target: File, userAgent: String) {
        throw UnsupportedOperationException("Google Play 版本请通过商店更新。")
    }

    fun installApk(context: Context, apkFile: File) {
        BrowserOpener.open(context, "https://play.google.com/store/apps/details?id=${context.packageName}")
    }
}
