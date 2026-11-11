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

        // Pixel phones are 64-bit ARM; leaving out other processor types removes most of the size.
        ndk {
            abiFilters += "arm64-v8a"
        }
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
            // Remove unused code and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Store native libraries compressed in the APK (smaller download).
    // OCR model files are compressed too; they are unpacked once on first run.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
