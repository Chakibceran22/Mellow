// Top-level build file. Plugins are declared here and applied per module.
//
// AGP 9 owns Kotlin for Android modules (built-in Kotlin support), so no module applies
// `org.jetbrains.kotlin.android`. Declaring `kotlin.jvm` here does two jobs: `:core:model`
// uses it, and it pins the Kotlin Gradle Plugin on the shared buildscript classpath to the
// same version as the Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
