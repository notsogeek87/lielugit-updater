// Root build file: only declares plugin versions (applied in the library module).
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
