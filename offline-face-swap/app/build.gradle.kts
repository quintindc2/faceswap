plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.faceswap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.faceswap"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Keep the APK smaller: phones are arm. (Remove this block to also support x86 emulators.)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Bundled (offline) ML Kit face mesh model: 468 3D landmarks per face.
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta3")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
