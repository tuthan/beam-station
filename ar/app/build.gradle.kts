plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.tuthan.stationar"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tuthan.stationar"
        // The Rokid Station is Android 11, and the app is for it alone.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // The release key stays on this machine (ar/stationar.keystore, created by tools/install.sh, never committed).
    // Updates need the same key, so keep it.
    val keystore = rootProject.file("stationar.keystore")
    signingConfigs {
        if (keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = "stationar"
                keyAlias = "stationar"
                keyPassword = "stationar"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No dependency-metadata block in the APK: it is encrypted to Google's key and lists the dependencies.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
}

dependencyLocking {
    lockAllConfigurations()
    // STRICT: a configuration resolved without lock state fails instead of resolving unlocked.
    lockMode.set(LockMode.STRICT)
}
