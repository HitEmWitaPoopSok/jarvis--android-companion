plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"
val appCode = ((project.findProperty("appCode") as String?) ?: "1").toInt()

android {
    namespace = "app.jarvis.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.jarvis.companion"
        minSdk = 29
        targetSdk = 34
        versionCode = appCode
        versionName = appVersion
    }

    // A fixed signing key, committed on purpose: every build is signed the same way, so a new
    // version installs over the old one and keeps your pairing. It is a personal sideloaded
    // app; this key protects nothing but update continuity.
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis-companion"
            keyAlias = "jarvis"
            keyPassword = "jarvis-companion"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
        debug {
            signingConfig = signingConfigs.getByName("jarvis")
        }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
