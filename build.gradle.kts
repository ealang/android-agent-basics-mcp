plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // From Kotlin 2.0 the Compose compiler ships with Kotlin itself, applied
    // through this plugin — no more matching a compiler-extension version to
    // the Kotlin version by hand.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
