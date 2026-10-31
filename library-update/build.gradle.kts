plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.libraryupdate"

    // The worker test builds the worker's notification on the JVM, and its cancel Intent is a
    // platform stub; returning defaults lets it construct instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":source-contract"))
    implementation(project(":domain"))
    implementation(project(":i18n"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.work)

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
