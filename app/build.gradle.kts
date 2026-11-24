import java.util.Properties

plugins {
    id("com.android.application")
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
    namespace = "io.github.ealang.agentbasicsmcp"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.ealang.agentbasicsmcp"
        // Android 10: the first release where background activity starts
        // need the overlay grant and background location is its own
        // permission, so the code has no older branch to carry.
        minSdk = 29
        targetSdk = 35
        // versionCode is what Android compares on install: it must rise with
        // every release, or the new APK is refused as a downgrade.
        // versionName is the string people see in Settings and in `inspect`.
        versionCode = 5
        versionName = "1.2.0"
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

    // Labels live here rather than in the manifest so each build type can
    // carry its own. app_label names the app in Settings and notifications;
    // launcher_label is the shorter one under the home-screen icon.
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            // A debug build installs beside a release one rather than over
            // it: different package, own data, own token and port, so a
            // release-signed copy can stay on the phone while development
            // builds come and go.
            applicationIdSuffix = ".debug"
            resValue("string", "app_label", "Agent Basics MCP (debug)")
            resValue("string", "launcher_label", "Agent Basics (debug)")
        }
        getByName("release") {
            // Not shrunk: a release behaves exactly like the debug build it
            // was tested as. Signing is a step after the build (see README).
            isMinifyEnabled = false
            resValue("string", "app_label", "Agent Basics MCP")
            resValue("string", "launcher_label", "Agent Basics")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    packaging {
        resources {
            // Ktor's console colouring brings desktop native libraries along.
            excludes += "org/fusesource/jansi/**"
        }
    }

    testOptions {
        // android.util.Log and the like do nothing in a JVM test instead of
        // throwing.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        // The per-build-type labels above are resValue entries.
        resValues = true
        // Two interfaces under src/main/aidl: the PebbleKit 2 binder contract
        // CoreApp's service speaks. See PebbleKit2.kt.
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // org.json is in the Android framework already.
    // The HTTP server. CIO is Ktor's own engine: coroutines over NIO, no
    // Netty or Jetty underneath.
    implementation("io.ktor:ktor-server-core:3.6.1")
    implementation("io.ktor:ktor-server-cio:3.6.1")
    implementation("io.ktor:ktor-server-websockets:3.6.1")
    // MediaBrowserCompat, for the experimental media_search only: search is
    // part of the androidx media protocol, not the framework MediaBrowser.
    implementation("androidx.media:media:1.7.1")

    // The UI. Compose draws every widget itself — none of this is in the OS —
    // so the whole toolkit rides in the APK. The BOM pins the androidx.compose
    // artifacts to versions tested together; only it carries a version.
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.material3:material3")
    // The three icons the page draws. Material 3 stopped bringing these along.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    // ContextCompat.registerReceiver: the exported flag on every Android,
    // not only the ones whose framework method takes it.
    implementation("androidx.core:core-ktx:1.19.1")

    // Tests run on the build machine's JVM, no device: ./gradlew testDebugUnitTest
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    // The SDK's org.json is a stub off the device; this is the real one.
    testImplementation("org.json:json:20260814")
}
