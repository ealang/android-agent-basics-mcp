import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional: DEBUG_KEYSTORE in local.properties points debug builds at a
// keystore of your own, so every build signs with the same key and the APK
// installs over the copy already on the phone. Without it the SDK's default
// debug key is used.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.ealang.puckmcp"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ealang.puckmcp"
        // Android 10: the first release where background activity starts
        // need the overlay grant and background location is its own
        // permission, so the code has no older branch to carry.
        minSdk = 29
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

    buildFeatures {
        compose = true
    }
}

composeCompiler {
    // The whole UI hangs off one invariant: bumping MainActivity.tick
    // recomposes everything, and composables re-read live state (Config,
    // service statics) as they run. Strong skipping — the default since
    // Kotlin 2.0.20 — breaks that: it skips a composable whose arguments
    // are reference-equal and memoizes content lambdas even over unstable
    // captures, so a row whose inputs are `activity` and a constant never
    // re-ran and its switch sat stale after a toggle. Seen on-device.
    // Turning the feature off restores "a bump repaints everything" as a
    // global guarantee instead of a per-callsite audit.
    featureFlags.add(
        org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag
            .StrongSkipping.disabled(),
    )
}

dependencies {
    // org.json is in the Android framework already.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // MediaBrowserCompat, for the experimental media_search only: search is
    // part of the androidx media protocol, not the framework MediaBrowser.
    implementation("androidx.media:media:1.7.0")

    // The UI. Compose draws every widget itself — none of this is in the OS —
    // so the whole toolkit rides in the APK. The BOM pins the androidx.compose
    // artifacts to versions tested together; only it carries a version.
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    // ContextCompat.registerReceiver: the exported flag on every Android,
    // not only the ones whose framework method takes it.
    implementation("androidx.core:core-ktx:1.13.1")
}
