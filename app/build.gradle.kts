plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version code comes from the GitHub Actions run number so every release is an update.
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()

val keystoreFile = file("release.keystore")
val hasReleaseKey = keystoreFile.exists() && System.getenv("SIGNING_STORE_PASSWORD") != null

android {
    namespace = "app.screentextcopy"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.screentextcopy"
        minSdk = 30
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Keep OCR model files uncompressed so they copy quickly on first run.
    androidResources {
        noCompress += "traineddata"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
