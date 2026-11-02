plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.starlight.charge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.starlight.charge"
        minSdk = 29
        targetSdk = 36
        versionCode = 33
        versionName = "4.17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded personal app: signed with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Personal media (such as the video wallpaper's loop) lives in src/local, which git ignores.
    sourceSets["main"].res.srcDir("src/local/res")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
