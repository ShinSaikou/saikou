plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.tracking"

    // TrackerHttpClientTest builds the real NetworkHelper, whose cookie jar and Cloudflare
    // interceptor touch platform statics at construction; returning defaults lets them construct.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(project(":i18n"))

    implementation(libs.bundles.okhttp)
    implementation(libs.okhttp.logging)
    // AniList's descriptions arrive as HTML; its entity table reads them as text on the JVM too.
    implementation(libs.jsoup)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver3)
    // The user-certificate trust test signs a certificate for the shared client to refuse.
    testImplementation(libs.okhttp.tls)
}
