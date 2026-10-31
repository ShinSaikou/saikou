plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.core.common"

    // The vendored utilities read Build fields and a few platform statics at class init. Returning
    // defaults keeps the pure-Kotlin halves (format sniffing, the preference key prefixes, the
    // solve coordinator) testable on the JVM without Robolectric.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(libs.bundles.kotlinx.coroutines)
    api(libs.bundles.okhttp)
    api(libs.bundles.serialization)
    api(libs.kotlinx.datetime)
    api(libs.logcat)
    api(libs.okio)
    api(libs.saikou.image)
    api(libs.unifile)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work)
    implementation(libs.conscrypt)
    implementation(libs.jsoup)
    implementation(libs.natural.comparator)
    implementation(libs.okhttp.logging)
    implementation(libs.quickJs)

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
