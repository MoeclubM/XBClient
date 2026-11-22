# Preserve line numbers for Play Console crash deobfuscation while hiding filenames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Neto Rust FFI JNI entry points (Java_moe_telecom_neto_sdk_NetoBridge_*).
-keep class moe.telecom.neto.sdk.NetoBridge {
    native <methods>;
}

# Static methods invoked back from Rust via JNI (neto-platform-android).
-keep class moe.telecom.neto.sdk.NetoVpnService {
    public static boolean protectSocketFd(int);
    public static void onLog(java.lang.String, java.lang.String);
    public static void onEvent(java.lang.String);
}
