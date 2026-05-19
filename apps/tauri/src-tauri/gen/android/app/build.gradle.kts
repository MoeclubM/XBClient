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
        applicationId = xbclientApplicationId
        minSdk = minAndroidApi
        targetSdk = latestAndroidApi
        versionCode = appVersionCode
        versionName = appVersionName
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = "tauri.build.gradle.kts")
