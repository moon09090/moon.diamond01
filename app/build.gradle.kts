plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aryan.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aryan.moonnew"
        minSdk = 26
        targetSdk = 33
        versionCode = 2
        versionName = "2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
implementation("com.alphacephei:vosk-android:0.3.75")
implementation("net.java.dev.jna:jna:5.18.1@aar")
}
