package moe.telecom.xbclient

object AerionCore {
    init {
        System.loadLibrary("xbclient_tauri_lib")
        initializeAndroid(XbClientVpnService::class.java)
    }

    external fun initializeAndroid(serviceClass: Class<*>)

    external fun startVpn(requestJson: String): String

    external fun stopVpn(sessionId: Long): String

    external fun testNode(requestJson: String): String
}
