plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.domain"

    // UiPreferences reads Build.VERSION at construction; the JVM tests never touch it.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":source-contract"))
    api(project(":core:common"))

    // Only for @Immutable on the vendored Manga; no Compose code is compiled into this module.
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
