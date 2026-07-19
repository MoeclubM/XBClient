plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val minAndroidApi = 26
val latestAndroidApi = 36
val latestBuildTools = "36.1.0"
val androidNdkVersion = "28.2.13676358"
extensions.extraProperties["aerionMinAndroidApi"] = minAndroidApi
extensions.extraProperties["aerionAndroidNdkVersion"] = androidNdkVersion
val defaultApiUrl = providers.environmentVariable("XBCLIENT_DEFAULT_API_URL").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_DEFAULT_API_URL GitHub Secret is required")
val appName = providers.environmentVariable("XBCLIENT_APP_NAME").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_APP_NAME GitHub Secret is required")
val androidApplicationId = providers.environmentVariable("XBCLIENT_APPLICATION_ID").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_APPLICATION_ID GitHub Secret is required")
val admobAppId = providers.environmentVariable("XBCLIENT_ADMOB_APP_ID").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_ADMOB_APP_ID GitHub Secret is required")
val userAgent = providers.environmentVariable("XBCLIENT_USER_AGENT").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_USER_AGENT GitHub Secret is required")
val oauthCallbackScheme = providers.environmentVariable("XBCLIENT_OAUTH_CALLBACK_SCHEME").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_OAUTH_CALLBACK_SCHEME GitHub Secret is required")
val websiteUrl = providers.environmentVariable("XBCLIENT_WEBSITE_URL").orNull.orEmpty()
val privacyPolicyUrl = providers.environmentVariable("XBCLIENT_PRIVACY_POLICY_URL").orNull.orEmpty()
val userAgreementUrl = providers.environmentVariable("XBCLIENT_USER_AGREEMENT_URL").orNull.orEmpty()
val releaseStoreFile = providers.environmentVariable("XBCLIENT_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("XBCLIENT_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("XBCLIENT_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("XBCLIENT_RELEASE_KEY_PASSWORD").orNull

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
android {
    namespace = "moe.telecom.xbclient"
    compileSdk = latestAndroidApi
    buildToolsVersion = latestBuildTools
    ndkVersion = androidNdkVersion

    defaultConfig {
        applicationId = androidApplicationId
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
        buildConfigField(
            "String",
            "GITHUB_PROJECT_URL",
            "\"https://github.com/MoeclubM/XBClient\"",
        )
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
            isUniversalApk = false
            reset()
            include("arm64-v8a")
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
android.sourceSets.getByName("main").assets.srcDir(rootProject.file("rust/aerion-core/assets"))
apply(from = rootProject.file("gradle/aerion-native.gradle.kts"))

val validateSharedSigning = tasks.register("validateSharedSigning") {
    doLast {
        val storePath = releaseStoreFile
            ?: error("XBCLIENT_RELEASE_STORE_FILE is required for APK signing")
        if (!rootProject.file(storePath).isFile) {
            error("APK signing keystore not found: $storePath")
        }
        if (releaseStorePassword.isNullOrEmpty()) {
            error("XBCLIENT_RELEASE_STORE_PASSWORD GitHub Secret is required for APK signing")
        }
        if (releaseKeyAlias.isNullOrEmpty()) {
            error("XBCLIENT_RELEASE_KEY_ALIAS GitHub Secret is required for APK signing")
        }
        if (releaseKeyPassword.isNullOrEmpty()) {
            error("XBCLIENT_RELEASE_KEY_PASSWORD GitHub Secret is required for APK signing")
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
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.5")
}

configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}
