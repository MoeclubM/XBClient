package moe.telecom.xbclient

object RustCore {
    init {
        System.loadLibrary("xbclient_core")
        initializeAndroid(XbClientVpnService::class.java)
    }

    external fun initializeAndroid(serviceClass: Class<*>)

    external fun startAnyTlsVpn(requestJson: String): String

    external fun stopAnyTlsVpn(sessionId: Long): String

    external fun testAnyTlsNode(requestJson: String): String
}
