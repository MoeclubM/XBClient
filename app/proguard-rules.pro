# Keep JNI entry points that are resolved by native symbol names.
-keep class moe.telecom.xbclient.AerionCore {
    native <methods>;
}

# Called from Rust by the literal method name through JNI.
-keepclassmembers class moe.telecom.xbclient.XbClientVpnService {
    public static boolean protectSocketFd(int);
}
