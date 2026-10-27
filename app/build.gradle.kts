plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.ray8"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ray8"
        minSdk = 26
        targetSdk = 35
        // Every CI run gets a higher versionCode, so a new build can always be installed over the old one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { 1000 + it } ?: 8
        versionName = "0.8"
    }

    // One fixed key for every build: Android only installs an update when it is signed with the same key
    // as the installed app. A KEYSTORE_FILE (GitHub secret) wins; otherwise the keystore in this repo is used.
    val ksPath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file("8ray-release.keystore")
                storePassword = "8ray-keystore"
                keyAlias = "8ray"
                keyPassword = "8ray-keystore"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Outputs: armeabi-v7a, arm64-v8a and a universal APK (every ABI that libbox.aar contains).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // sing-box (libbox), built by the "libbox" job of the GitHub workflow into app/libs/libbox.aar.
    implementation(files("libs/libbox.aar"))
    // QR code: encoder (share a config) and camera scanner (import a config).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
