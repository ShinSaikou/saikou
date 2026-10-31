buildscript {
    dependencies {
        // The Kotlin Gradle plugin AGP's built-in Kotlin uses, pinned as the product pins it.
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
