import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val rootLocalProperties = Properties()
val rootLocalPropertiesFile = rootProject.file("local.properties")
if (rootLocalPropertiesFile.isFile) {
    rootLocalPropertiesFile.inputStream().use { rootLocalProperties.load(it) }
}

fun sdkDirectory(): File {
    rootLocalProperties.getProperty("sdk.dir")?.let { return file(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return file(it) }
    System.getenv("ANDROID_HOME")?.let { return file(it) }
    val localAppData = System.getenv("LOCALAPPDATA")
    if (localAppData != null) {
        return file("$localAppData/Android/Sdk")
    }
    error("Android SDK directory is not configured. Set sdk.dir in local.properties.")
}

val rustCrateDir = rootProject.file("rust/xbclient-core")
val minAndroidApi = 26
val latestAndroidApi = 37
val latestBuildTools = "37.0.0"
val androidNdkVersion = "28.2.13676358"
val androidSdkDir = sdkDirectory()
val androidNdkDir = androidSdkDir.resolve("ndk/$androidNdkVersion")
val hostOs = System.getProperty("os.name").lowercase(Locale.ROOT)
val isWindows = hostOs.contains("windows")
val ndkHostTag = when {
    isWindows -> "windows-x86_64"
    hostOs.contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val executableSuffix = if (isWindows) ".cmd" else ""
val toolSuffix = if (isWindows) ".exe" else ""
val ndkBinDir = androidNdkDir.resolve("toolchains/llvm/prebuilt/$ndkHostTag/bin")
val defaultApiUrlRaw = providers.gradleProperty("xbclient.defaultApiUrl")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_DEFAULT_API_URL").orNull
    ?: rootLocalProperties.getProperty("xbclient.defaultApiUrl")
    ?: rootLocalProperties.getProperty("XBCLIENT_DEFAULT_API_URL")
    ?: error("XBCLIENT_DEFAULT_API_URL, -Pxbclient.defaultApiUrl or local.properties xbclient.defaultApiUrl is required")
val defaultApiUrl = defaultApiUrlRaw
    .trim()
    .takeIf { it.isNotEmpty() }
    ?: error("Default API URL is empty")
val appNameRaw = providers.gradleProperty("xbclient.appName")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_APP_NAME").orNull
    ?: rootLocalProperties.getProperty("xbclient.appName")
    ?: rootLocalProperties.getProperty("XBCLIENT_APP_NAME")
    ?: "XBClient"
val appName = appNameRaw.trim().ifEmpty { "XBClient" }
val admobAppIdRaw = providers.gradleProperty("xbclient.admobAppId")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_ADMOB_APP_ID").orNull
    ?: rootLocalProperties.getProperty("xbclient.admobAppId")
    ?: rootLocalProperties.getProperty("XBCLIENT_ADMOB_APP_ID")
    ?: error("XBCLIENT_ADMOB_APP_ID, -Pxbclient.admobAppId or local.properties xbclient.admobAppId is required")
val admobAppId = admobAppIdRaw
    .trim()
    .takeIf { it.isNotEmpty() }
    ?: error("AdMob App ID is empty")
val userAgentRaw = providers.gradleProperty("xbclient.userAgent")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_USER_AGENT").orNull
    ?: rootLocalProperties.getProperty("xbclient.userAgent")
    ?: rootLocalProperties.getProperty("XBCLIENT_USER_AGENT")
    ?: error("XBCLIENT_USER_AGENT, -Pxbclient.userAgent or local.properties xbclient.userAgent is required")
val userAgent = userAgentRaw.trim().takeIf { it.isNotEmpty() }
    ?: error("User-Agent is empty")
val localSigningProperties = Properties()
val localSigningPropertiesFile = rootProject.file("app/config/release-signing.local.txt")
if (localSigningPropertiesFile.isFile) {
    localSigningPropertiesFile.inputStream().use { localSigningProperties.load(it) }
}
fun signingValue(gradleProperty: String, environmentVariable: String): String? =
    providers.gradleProperty(gradleProperty).orNull
        ?: providers.environmentVariable(environmentVariable).orNull
        ?: localSigningProperties.getProperty(environmentVariable)?.takeIf { it.isNotBlank() }

fun gitText(vararg args: String, required: Boolean = true): String {
    val process = ProcessBuilder("git", *args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = process.waitFor()
    if (exitCode != 0 && required) {
        error("git ${args.joinToString(" ")} failed: $output")
    }
    return if (exitCode == 0) output else ""
}

val gitCommitTimestamp = gitText("log", "-1", "--format=%ct").toInt()
val gitShortHash = gitText("rev-parse", "--short=8", "HEAD")
val gitExactTag = gitText("describe", "--tags", "--exact-match", "HEAD", required = false)
val appVersionCode = gitCommitTimestamp
val appVersionName = gitExactTag.removePrefix("v").ifEmpty { "0.0.$gitCommitTimestamp-$gitShortHash" }
val debugVersionNameSuffix = ".debug"
val releaseStoreFile = providers.gradleProperty("xbclient.releaseStoreFile")
    .orElse(providers.environmentVariable("XBCLIENT_RELEASE_STORE_FILE"))
    .orElse(rootProject.file("app/config/release-signing.jks").absolutePath)
val releaseStorePassword = signingValue("xbclient.releaseStorePassword", "XBCLIENT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("xbclient.releaseKeyAlias")
    .orElse(providers.environmentVariable("XBCLIENT_RELEASE_KEY_ALIAS"))
    .orElse("xbclient")
val releaseKeyPassword = signingValue("xbclient.releaseKeyPassword", "XBCLIENT_RELEASE_KEY_PASSWORD")

val rustTargets = listOf(
    Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android${minAndroidApi}-clang$executableSuffix"),
    Triple("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi${minAndroidApi}-clang$executableSuffix"),
    Triple("x86", "i686-linux-android", "i686-linux-android${minAndroidApi}-clang$executableSuffix"),
    Triple("x86_64", "x86_64-linux-android", "x86_64-linux-android${minAndroidApi}-clang$executableSuffix"),
)

android {
    namespace = "moe.telecom.xbclient"
    compileSdk = latestAndroidApi
    buildToolsVersion = latestBuildTools
    ndkVersion = androidNdkVersion

    defaultConfig {
        applicationId = "moe.telecom.xbclient"
        minSdk = minAndroidApi
        targetSdk = latestAndroidApi
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["admobApplicationId"] = admobAppId
        resValue("string", "app_name", appName)
        buildConfigField("String", "DEFAULT_API_URL", "\"${defaultApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "USER_AGENT", "\"${userAgent.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        create("release") {
            releaseStoreFile.orNull?.let { storeFile = rootProject.file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
            versionNameSuffix = debugVersionNameSuffix
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

fun rustEnvName(target: String): String =
    "CARGO_TARGET_${target.uppercase(Locale.ROOT).replace('-', '_')}_LINKER"

fun ccEnvName(prefix: String, target: String): String =
    "${prefix}_${target.replace('-', '_')}"

rustTargets.forEach { (abi, target, clang) ->
    val cargoTask = tasks.register<Exec>("cargoBuild${abi.replace("-", "_")}") {
        workingDir = rustCrateDir
        inputs.files(fileTree(rustCrateDir.resolve("src")), rustCrateDir.resolve("Cargo.toml"), rustCrateDir.resolve("Cargo.lock"))
        inputs.property("androidNdkVersion", androidNdkVersion)
        inputs.property("minAndroidApi", minAndroidApi)
        inputs.property("rustTarget", target)
        outputs.file(rustCrateDir.resolve("target/$target/release/libxbclient_core.so"))
        val cc = ndkBinDir.resolve(clang).absolutePath
        val cxx = cc.removeSuffix("clang$executableSuffix") + "clang++$executableSuffix"
        environment(rustEnvName(target), cc)
        environment(ccEnvName("CC", target), cc)
        environment(ccEnvName("CXX", target), cxx)
        environment(ccEnvName("AR", target), ndkBinDir.resolve("llvm-ar$toolSuffix").absolutePath)
        environment("ANDROID_NDK_HOME", androidNdkDir.absolutePath)
        commandLine("cargo", "build", "--release", "--target", target)
    }

    tasks.register<Copy>("copyRust${abi.replace("-", "_")}") {
        dependsOn(cargoTask)
        from(rustCrateDir.resolve("target/$target/release/libxbclient_core.so"))
        into(layout.buildDirectory.dir("generated/rustJniLibs/$abi"))
    }
}

val copyRustJniLibs = tasks.register("copyRustJniLibs") {
    dependsOn(rustTargets.map { (abi, _, _) -> "copyRust${abi.replace("-", "_")}" })
}

android.sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.file("generated/rustJniLibs").get().asFile.absolutePath)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(copyRustJniLibs)
}

val validateSharedSigning = tasks.register("validateSharedSigning") {
    doLast {
        val storePath = releaseStoreFile.orNull
            ?: error("xbclient.releaseStoreFile or XBCLIENT_RELEASE_STORE_FILE is required for APK signing")
        if (!rootProject.file(storePath).isFile) {
            error("APK signing keystore not found: $storePath")
        }
        if (releaseStorePassword.isNullOrEmpty()) {
            error("xbclient.releaseStorePassword or XBCLIENT_RELEASE_STORE_PASSWORD is required for APK signing")
        }
        if (releaseKeyAlias.orNull.isNullOrEmpty()) {
            error("xbclient.releaseKeyAlias or XBCLIENT_RELEASE_KEY_ALIAS is required for APK signing")
        }
        if (releaseKeyPassword.isNullOrEmpty()) {
            error("xbclient.releaseKeyPassword or XBCLIENT_RELEASE_KEY_PASSWORD is required for APK signing")
        }
    }
}

tasks.matching {
    it.name == "assembleDebug" ||
        it.name == "assembleRelease" ||
        it.name == "bundleDebug" ||
        it.name == "bundleRelease" ||
        it.name == "packageDebug" ||
        it.name == "packageRelease"
}.configureEach {
    dependsOn(validateSharedSigning)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-ads:25.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.yaml:snakeyaml:2.5")
}
