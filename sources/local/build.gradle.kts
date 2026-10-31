plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.sources.local"

    // UniFile guards createFile, findFile and renameTo with TextUtils.isEmpty, and the JVM tests
    // run against real directories through UniFile.fromFile. Returning defaults lets those guards
    // fall through; the two platform statics the tests genuinely need are stubbed with mockk.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:archive"))
    implementation(project(":core:metadata"))
    implementation(project(":source-contract"))
    implementation(project(":domain"))
    implementation(project(":i18n"))

    implementation(libs.androidx.core)
    // EpubReaderExtensions reads the epub package document, which EpubReader hands back as a
    // jsoup Document; :core:archive keeps jsoup to itself.
    implementation(libs.jsoup)
    implementation(libs.natural.comparator)
    implementation(libs.unifile)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
