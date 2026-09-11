import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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

        // TDLib ships as a prebuilt arm64 .so (lifted from agent-voice-gateway);
        // limiting the ABI set keeps the build from producing splits that lack it.
        ndk { abiFilters += "arm64-v8a" }
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

    buildFeatures {
        compose = true
    }

    testOptions {
        // The queue classes call android.util.Log; let those calls no-op on
        // the JVM instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // org.json is in the Android framework already.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // TdApi.java (generated, lifted from agent-voice-gateway) annotates its
    // constants with androidx IntDef/Nullable; this is compile-time metadata
    // only, no runtime library comes with it.
    implementation("androidx.annotation:annotation:1.8.2")
    // MediaBrowserCompat, for the experimental media_search only: search is
    // part of the androidx media protocol, not the framework MediaBrowser.
    implementation("androidx.media:media:1.7.0")

    // The UI. Compose draws every widget itself — none of this is in the OS —
    // so the whole toolkit rides in the APK. The BOM pins the androidx.compose
    // artifacts to versions tested together; only it carries a version.
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Unit tests only cover logic that needs no device — the time parser being
    // the case where a quiet mistake produces a timer at the wrong hour rather
    // than a visible failure.
    testImplementation("junit:junit:4.13.2")
    // meta.json round-trips through org.json, whose android.jar entry is a
    // stub on the JVM; the real library shadows it on the test classpath.
    testImplementation("org.json:json:20240303")
}
