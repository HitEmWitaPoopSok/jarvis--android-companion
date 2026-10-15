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

    // Release signing comes from the environment (CI secrets or your shell), never from the repo:
    //   JARVIS_KEYSTORE_FILE, JARVIS_KEYSTORE_PASSWORD, JARVIS_KEY_ALIAS, JARVIS_KEY_PASSWORD
    // Without them (a plain local build) the APK is signed with the Android debug key, which is
    // fine for trying it out but will not update over a release signed with your real key.
    val keystorePath = System.getenv("JARVIS_KEYSTORE_FILE")
    val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()
    if (hasReleaseKey) {
        signingConfigs {
            create("jarvis") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS")
                keyPassword = System.getenv("JARVIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "jarvis" else "debug")
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
