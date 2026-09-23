plugins {
    alias(libs.plugins.android.application)
}

// Minimal, dependency-free WebView player. Its release APK is embedded into FOLD FORGE and used as the
// template for on-device APK builds (manifest is re-written, web assets injected, APK re-signed).
android {
    namespace = "com.foldforge.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.foldforge.player.template"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0-template"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}
