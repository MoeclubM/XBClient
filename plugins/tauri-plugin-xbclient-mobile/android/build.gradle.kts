import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val repoLocalProperties = Properties()
val repoLocalPropertiesFile = rootProject.file("local.properties")
if (repoLocalPropertiesFile.isFile) {
    repoLocalPropertiesFile.inputStream().use { repoLocalProperties.load(it) }
}
val userAgentRaw = providers.gradleProperty("xbclient.userAgent")
    .orNull
    ?: providers.environmentVariable("XBCLIENT_USER_AGENT").orNull
    ?: repoLocalProperties.getProperty("xbclient.userAgent")
    ?: repoLocalProperties.getProperty("XBCLIENT_USER_AGENT")
    ?: error("XBCLIENT_USER_AGENT, -Pxbclient.userAgent or local.properties xbclient.userAgent is required")
val userAgent = userAgentRaw
    .trim()
    .takeIf { it.isNotEmpty() }
    ?: error("User-Agent is empty")

android {
    namespace = "moe.telecom.xbclient.tauri.mobile"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "USER_AGENT", "\"${userAgent.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.0.1")
    implementation(project(":tauri-android"))
}
