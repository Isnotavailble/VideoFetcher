plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videofetcher"
    compileSdk = 34
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.videofetcher"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // =========================================================
    // NEW: ABI Splitting (Reduces APK size from ~200MB to ~50MB)
    // =========================================================
    splits {
        abi {
            isEnable = true
            reset()
            // Creates separate APKs for modern, older, and emulator architectures
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false // Prevents the creation of the massive 200MB file
        }
    }

    buildFeatures {
        compose = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    
    // =========================================================
    // NEW: Required for the DownloaderViewModel and State Management
    // =========================================================
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // YoutubeDL and FFmpeg dependencies
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}