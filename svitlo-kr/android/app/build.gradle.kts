plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.svitlo.kr"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.svitlo.kr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Fixed key committed to the repo so every CI build can update the
        // previously installed app. It is NOT suitable for Google Play.
        create("shared") {
            storeFile = file("svitlo-debug.keystore")
            storePassword = "android"
            keyAlias = "svitlo"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
