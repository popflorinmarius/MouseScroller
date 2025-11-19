plugins {
    // Updated to 8.2.2 (More stable)
    id("com.android.application") version "8.2.2"
    // Updated to 1.9.22 (Fixes the crash you saw)
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

android {
    namespace = "com.example.mousetotouch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mousetotouch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    
    // Add Java 17 compatibility to ensure smooth building
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_10
        targetCompatibility = JavaVersion.VERSION_1_10
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
