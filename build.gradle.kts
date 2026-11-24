plugins {
    // From AGP 9 the Android plugin compiles Kotlin itself; there is no
    // separate Kotlin Android plugin to apply.
    id("com.android.application") version "9.5.2" apply false
    // The Compose compiler ships with Kotlin. This plugin's version is also
    // what sets the Kotlin version for the whole build.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
