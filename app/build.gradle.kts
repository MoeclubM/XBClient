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

val minAndroidApi = 26
val latestAndroidApi = 36
val latestBuildTools = "36.1.0"
val androidNdkVersion = "28.2.13676358"
extensions.extraProperties["aerionMinAndroidApi"] = minAndroidApi
extensions.extraProperties["aerionAndroidNdkVersion"] = androidNdkVersion
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
val applicationIdRaw = providers.gradleProperty("xbclient.applicationId").orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("XBCLIENT_APPLICATION_ID").orNull?.takeIf { it.isNotBlank() }
    ?: rootLocalProperties.getProperty("xbclient.applicationId")?.takeIf { it.isNotBlank() }
    ?: rootLocalProperties.getProperty("XBCLIENT_APPLICATION_ID")?.takeIf { it.isNotBlank() }
    ?: "moe.telecom.xbclient"
val xbclientApplicationId = applicationIdRaw.trim()
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
val oauthCallbackSchemeRaw = providers.gradleProperty("xbclient.oauthCallbackScheme")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_OAUTH_CALLBACK_SCHEME").orNull
    ?: rootLocalProperties.getProperty("xbclient.oauthCallbackScheme")
    ?: rootLocalProperties.getProperty("XBCLIENT_OAUTH_CALLBACK_SCHEME")
    ?: "secone"
val oauthCallbackScheme = oauthCallbackSchemeRaw.trim().ifEmpty { "secone" }
val websiteUrl = providers.gradleProperty("xbclient.websiteUrl")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_WEBSITE_URL").orNull
    ?: rootLocalProperties.getProperty("xbclient.websiteUrl")
    ?: rootLocalProperties.getProperty("XBCLIENT_WEBSITE_URL")
    ?: ""
val privacyPolicyUrl = providers.gradleProperty("xbclient.privacyPolicyUrl")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_PRIVACY_POLICY_URL").orNull
    ?: rootLocalProperties.getProperty("xbclient.privacyPolicyUrl")
    ?: rootLocalProperties.getProperty("XBCLIENT_PRIVACY_POLICY_URL")
    ?: ""
val userAgreementUrl = providers.gradleProperty("xbclient.userAgreementUrl")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_USER_AGREEMENT_URL").orNull
    ?: rootLocalProperties.getProperty("xbclient.userAgreementUrl")
    ?: rootLocalProperties.getProperty("XBCLIENT_USER_AGREEMENT_URL")
    ?: ""
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
    namespace = "moe.telecom.xbclient"
    compileSdk = latestAndroidApi
    buildToolsVersion = latestBuildTools
    ndkVersion = androidNdkVersion

    defaultConfig {
        applicationId = xbclientApplicationId
        minSdk = minAndroidApi
        targetSdk = latestAndroidApi
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["oauthCallbackScheme"] = oauthCallbackScheme
        resValue("string", "app_name", appName)
        buildConfigField("String", "ADMOB_APP_ID", "\"${admobAppId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEFAULT_API_URL", "\"${defaultApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "USER_AGENT", "\"${userAgent.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"${oauthCallbackScheme.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "WEBSITE_URL", "\"${websiteUrl.trim().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${privacyPolicyUrl.trim().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "USER_AGREEMENT_URL", "\"${userAgreementUrl.trim().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        create("release") {
            releaseStoreFile?.let { storeFile = rootProject.file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            versionNameSuffix = debugVersionNameSuffix
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
            include("arm64-v8a", "x86_64")
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

android.sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.file("generated/aerionJniLibs").get().asFile.absolutePath)
apply(from = rootProject.file("gradle/aerion-native.gradle.kts"))

val validateSharedSigning = tasks.register("validateSharedSigning") {
    doLast {
        val storePath = releaseStoreFile
            ?: error("xbclient.releaseStoreFile or XBCLIENT_RELEASE_STORE_FILE is required for APK signing")
        if (!rootProject.file(storePath).isFile) {
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
    it.name == "assembleRelease" ||
        it.name == "bundleRelease" ||
        it.name == "packageRelease"
}.configureEach {
    dependsOn(validateSharedSigning)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.5")
}

configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}
