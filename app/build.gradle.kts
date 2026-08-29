import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The debug keystore lives outside the repo (path comes from local.properties),
// so a rebuilt container signs with the same key and the APK still installs
// over the copy already on the phone.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.ealang.phonemcp"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ealang.phonemcp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    localProps["DEBUG_KEYSTORE"]?.toString()?.let { path ->
        signingConfigs {
            getByName("debug") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
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
    // The only third-party piece. org.json is in the Android framework already.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Unit tests only cover logic that needs no device — the time parser being
    // the case where a quiet mistake produces a timer at the wrong hour rather
    // than a visible failure.
    testImplementation("junit:junit:4.13.2")
}
