import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val repoRoot = rootProject.file("../../../../..").canonicalFile
val repoLocalProperties = Properties()
val repoLocalPropertiesFile = repoRoot.resolve("local.properties")
if (repoLocalPropertiesFile.isFile) {
    repoLocalPropertiesFile.inputStream().use { repoLocalProperties.load(it) }
}
val localSigningProperties = Properties()
val localSigningPropertiesFile = repoRoot.resolve("app/config/release-signing.local.txt")
if (localSigningPropertiesFile.isFile) {
    localSigningPropertiesFile.inputStream().use { localSigningProperties.load(it) }
}

val minAndroidApi = 26
val latestAndroidApi = 36
val latestBuildTools = "36.1.0"
val applicationIdRaw = providers.gradleProperty("xbclient.applicationId").orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("XBCLIENT_APPLICATION_ID").orNull?.takeIf { it.isNotBlank() }
    ?: repoLocalProperties.getProperty("xbclient.applicationId")?.takeIf { it.isNotBlank() }
    ?: repoLocalProperties.getProperty("XBCLIENT_APPLICATION_ID")?.takeIf { it.isNotBlank() }
    ?: "moe.telecom.xbclient"
val xbclientApplicationId = applicationIdRaw.trim()
val appNameRaw = providers.gradleProperty("xbclient.appName")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_APP_NAME").orNull
    ?: repoLocalProperties.getProperty("xbclient.appName")
    ?: repoLocalProperties.getProperty("XBCLIENT_APP_NAME")
    ?: "XBClient"
val appName = appNameRaw.trim().ifEmpty { "XBClient" }
val admobAppIdRaw = providers.gradleProperty("xbclient.admobAppId")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_ADMOB_APP_ID").orNull
    ?: repoLocalProperties.getProperty("xbclient.admobAppId")
    ?: repoLocalProperties.getProperty("XBCLIENT_ADMOB_APP_ID")
    ?: error("XBCLIENT_ADMOB_APP_ID, -Pxbclient.admobAppId or local.properties xbclient.admobAppId is required")
val admobAppId = admobAppIdRaw
    .trim()
    .takeIf { it.isNotEmpty() }
    ?: error("AdMob App ID is empty")
val oauthCallbackSchemeRaw = providers.gradleProperty("xbclient.oauthCallbackScheme")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_OAUTH_CALLBACK_SCHEME").orNull
    ?: repoLocalProperties.getProperty("xbclient.oauthCallbackScheme")
    ?: repoLocalProperties.getProperty("XBCLIENT_OAUTH_CALLBACK_SCHEME")
    ?: error("XBCLIENT_OAUTH_CALLBACK_SCHEME, -Pxbclient.oauthCallbackScheme or local.properties xbclient.oauthCallbackScheme is required")
val oauthCallbackScheme = oauthCallbackSchemeRaw
    .trim()
    .takeIf { it.isNotEmpty() }
    ?: error("OAuth callback scheme is empty")
fun signingValue(gradleProperty: String, environmentVariable: String): String? =
    providers.gradleProperty(gradleProperty).orNull
        ?: providers.environmentVariable(environmentVariable).orNull
        ?: localSigningProperties.getProperty(environmentVariable)?.takeIf { it.isNotBlank() }

fun gitText(vararg args: String, required: Boolean = true): String {
    val process = ProcessBuilder("git", *args)
        .directory(repoRoot)
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
val gitLatestTag = gitText("describe", "--tags", "--abbrev=0", required = false)
val gitCommitsSinceLatestTag = if (gitLatestTag.isNotEmpty()) {
    gitText("rev-list", "$gitLatestTag..HEAD", "--count", required = false).ifEmpty { "0" }
} else {
    ""
}
val appVersionCode = gitCommitTimestamp
val appVersionName = gitExactTag.removePrefix("v").ifEmpty {
    if (gitLatestTag.isNotEmpty()) {
        "${gitLatestTag.removePrefix("v")}-beta.$gitCommitsSinceLatestTag.$gitShortHash"
    } else {
        "0.0.$gitCommitTimestamp-$gitShortHash"
    }
}
val debugVersionNameSuffix = ".debug"
val releaseStoreFile = signingValue("xbclient.releaseStoreFile", "XBCLIENT_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("xbclient.releaseStorePassword", "XBCLIENT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("xbclient.releaseKeyAlias", "XBCLIENT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("xbclient.releaseKeyPassword", "XBCLIENT_RELEASE_KEY_PASSWORD")

android {
    compileSdk = latestAndroidApi
    buildToolsVersion = latestBuildTools
    namespace = "moe.telecom.xbclient.tauri"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        manifestPlaceholders["admobAppId"] = admobAppId
        manifestPlaceholders["oauthCallbackScheme"] = oauthCallbackScheme
        applicationId = xbclientApplicationId
        minSdk = minAndroidApi
        targetSdk = latestAndroidApi
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "app_name", appName)
        resValue("string", "main_activity_title", appName)
        buildConfigField("String", "ADMOB_APP_ID", "\"${admobAppId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"${oauthCallbackScheme.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    signingConfigs {
        create("release") {
            releaseStoreFile?.let { storeFile = repoRoot.resolve(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            versionNameSuffix = debugVersionNameSuffix
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
        resValues = true
    }
}

rust {
    rootDirRel = "../../../"
}

val validateSharedSigning = tasks.register("validateSharedSigning") {
    doLast {
        val storePath = releaseStoreFile
            ?: error("xbclient.releaseStoreFile or XBCLIENT_RELEASE_STORE_FILE is required for APK signing")
        if (!repoRoot.resolve(storePath).isFile) {
            error("APK signing keystore not found: $storePath")
        }
        if (releaseStorePassword.isNullOrEmpty()) {
            error("xbclient.releaseStorePassword or XBCLIENT_RELEASE_STORE_PASSWORD is required for APK signing")
        }
        if (releaseKeyAlias.isNullOrEmpty()) {
            error("xbclient.releaseKeyAlias or XBCLIENT_RELEASE_KEY_ALIAS is required for APK signing")
        }
        if (releaseKeyPassword.isNullOrEmpty()) {
            error("xbclient.releaseKeyPassword or XBCLIENT_RELEASE_KEY_PASSWORD is required for APK signing")
        }
    }
}

tasks.matching {
    it.name.endsWith("Release") &&
        (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package"))
}.configureEach {
    dependsOn(validateSharedSigning)
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}

apply(from = "tauri.build.gradle.kts")
