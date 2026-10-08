import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseProperties = Properties().apply {
    val releaseFile = rootProject.file("RELEASE")
    if (releaseFile.exists()) {
        releaseFile.inputStream().use { load(it) }
    }
}

val appVersion = releaseProperties.getProperty("VERSION", "0.0.1")
val appChannel = releaseProperties.getProperty("CHANNEL", "beta")
val appVersionName = if (appChannel.isNotEmpty()) "$appVersion-$appChannel" else appVersion
val appVersionCode = releaseProperties.getProperty("VERSION_CODE", "1").toIntOrNull() ?: 1
val appMinSdk = releaseProperties.getProperty("MIN_SDK", "26").toIntOrNull() ?: 26
val appTargetSdk = releaseProperties.getProperty("TARGET_SDK", "35").toIntOrNull() ?: 35
val appCompileSdk = releaseProperties.getProperty("COMPILE_SDK", "35").toIntOrNull() ?: 35

android {
    namespace = "com.quazaar.synker.klient"
    compileSdk = appCompileSdk

    defaultConfig {
        applicationId = "com.quazaar.synker.klient"
        minSdk = appMinSdk
        targetSdk = appTargetSdk
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }

    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)

    debugImplementation(libs.androidx.ui.tooling)
}