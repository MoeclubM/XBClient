plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val panelType = providers.environmentVariable("XBCLIENT_PANEL_TYPE").orNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "xboard"
require(panelType in setOf("xboard", "pxboard")) { "XBCLIENT_PANEL_TYPE must be xboard or pxboard" }

fun parseBoolSecret(name: String, default: Boolean): Boolean =
    when (providers.environmentVariable(name).orNull?.trim()?.lowercase().orEmpty()) {
        "" -> default
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> error("$name must be true or false")
    }

fun escapeBuildConfigString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val appMinAndroidApi = 33
val compileAndroidApi = 37
val targetAndroidApi = 36
val latestBuildTools = "36.1.0"
val androidNdkVersion = "28.2.13676358"
val miuixVersion = "0.9.4"
val defaultApiUrl = providers.environmentVariable("XBCLIENT_DEFAULT_API_URL").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_DEFAULT_API_URL GitHub Secret is required")
val appName = providers.environmentVariable("XBCLIENT_APP_NAME").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_APP_NAME GitHub Secret is required")
val androidApplicationId = providers.environmentVariable("XBCLIENT_APPLICATION_ID").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_APPLICATION_ID GitHub Secret is required")
val appPaymentEnabled = parseBoolSecret("XBCLIENT_APP_PAYMENT_ENABLED", true)
val appOpenAdUnitId = providers.environmentVariable("XBCLIENT_APP_OPEN_AD_UNIT_ID").orNull?.trim().orEmpty()
val planRewardedAdUnitId = providers.environmentVariable("XBCLIENT_PLAN_REWARDED_AD_UNIT_ID").orNull?.trim().orEmpty()
val pointsRewardedAdUnitId = providers.environmentVariable("XBCLIENT_POINTS_REWARDED_AD_UNIT_ID").orNull?.trim().orEmpty()
val hasAnyAdUnit = appOpenAdUnitId.isNotEmpty() || planRewardedAdUnitId.isNotEmpty() || pointsRewardedAdUnitId.isNotEmpty()
val admobAppId = providers.environmentVariable("XBCLIENT_ADMOB_APP_ID").orNull?.trim().orEmpty()
if (hasAnyAdUnit && admobAppId.isEmpty()) {
    error("XBCLIENT_ADMOB_APP_ID GitHub Secret is required when any AdMob ad unit ID is configured")
}
val userAgent = providers.environmentVariable("XBCLIENT_USER_AGENT").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("XBCLIENT_USER_AGENT GitHub Secret is required")
val oauthCallbackScheme = providers.environmentVariable("XBCLIENT_OAUTH_CALLBACK_SCHEME").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: if (panelType == "pxboard") "xbclient" else error("XBCLIENT_OAUTH_CALLBACK_SCHEME GitHub Secret is required")
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
val gitCommitCount = gitText("rev-list", "--count", "HEAD").toInt()
val playVersionCodeBase = 1778837979
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
val githubRepoSlug = providers.environmentVariable("XBCLIENT_GITHUB_REPO").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("GITHUB_REPOSITORY").orNull
        ?.trim()?.takeIf { it.isNotEmpty() }
    ?: "MoeclubM/XBClient"
val githubProjectUrl = if (githubRepoSlug.startsWith("http://") || githubRepoSlug.startsWith("https://")) {
    githubRepoSlug
} else {
    "https://github.com/$githubRepoSlug"
}

android {
    namespace = "moe.telecom.xbclient"
    compileSdk {
        version = release(compileAndroidApi) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = latestBuildTools
    ndkVersion = androidNdkVersion

    defaultConfig {
        applicationId = androidApplicationId
        minSdk = appMinAndroidApi
        targetSdk = targetAndroidApi
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["oauthCallbackScheme"] = oauthCallbackScheme
        manifestPlaceholders["admobAppId"] = admobAppId
        resValue("string", "app_name", appName)
        buildConfigField("Boolean", "APP_PAYMENT_ENABLED", appPaymentEnabled.toString())
        buildConfigField("String", "ADMOB_APP_ID", "\"${escapeBuildConfigString(admobAppId)}\"")
        buildConfigField("String", "APP_OPEN_AD_UNIT_ID", "\"${escapeBuildConfigString(appOpenAdUnitId)}\"")
        buildConfigField("String", "PLAN_REWARDED_AD_UNIT_ID", "\"${escapeBuildConfigString(planRewardedAdUnitId)}\"")
        buildConfigField("String", "POINTS_REWARDED_AD_UNIT_ID", "\"${escapeBuildConfigString(pointsRewardedAdUnitId)}\"")
        buildConfigField("String", "DEFAULT_API_URL", "\"${escapeBuildConfigString(defaultApiUrl)}\"")
        buildConfigField("String", "USER_AGENT", "\"${escapeBuildConfigString(userAgent)}\"")
        buildConfigField("String", "PANEL_TYPE", "\"$panelType\"")
        buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"${escapeBuildConfigString(oauthCallbackScheme)}\"")
        buildConfigField("String", "WEBSITE_URL", "\"${escapeBuildConfigString(websiteUrl.trim())}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${escapeBuildConfigString(privacyPolicyUrl.trim())}\"")
        buildConfigField("String", "USER_AGREEMENT_URL", "\"${escapeBuildConfigString(userAgreementUrl.trim())}\"")
        buildConfigField(
            "String",
            "GITHUB_PROJECT_URL",
            "\"${escapeBuildConfigString(githubProjectUrl)}\"",
        )
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            versionCode = gitCommitTimestamp
            buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            versionCode = playVersionCodeBase + gitCommitCount
            buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "false")
        }
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
    (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package")) &&
        it.name.contains("Release")
}.configureEach {
    dependsOn(validateSharedSigning)
}

dependencies {
    implementation("moe.telecom.neto:sdk-android:0.0.7")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.6.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:$miuixVersion")
}

configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}
