plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.saikou.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.reader"
    buildFeatures {
        viewBinding = true // the vendored viewer Views inflate their layouts through ViewBinding
    }
    testOptions {
        // Contract 07 part 2 section 9.1, for the same reason :domain and :core:common have it:
        // ReaderLayerPaintTest touches android.graphics.Paint and ColorMatrix, and ReaderIntentsTest
        // touches Intent, on paths the test never exercises for their behaviour.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:archive"))
    implementation(project(":source-contract"))
    implementation(project(":domain"))
    implementation(project(":presentation-core"))
    implementation(project(":i18n"))

    implementation(libs.bundles.reader.views)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.core)
    implementation(libs.diskLruCache)
    implementation(libs.material)
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodelCompose)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // The resolver's downloaded branch is tested against a real manifest row: the real `:data`
    // repository on the in-memory JVM driver, and `:downloads`' own binding of DownloadedChapters.
    // Test scope only: checkModuleGraph reads the api and implementation configurations, and
    // :reader itself still sees downloads only through :domain's interface.
    testImplementation(project(":downloads"))
    testImplementation(project(":data"))
    testImplementation(libs.sqldelight.sqliteDriver)

    // The ten device tests contract 07 part 2 section 9.2 assigns live in reader/src/androidTest.
    androidTestImplementation(libs.bundles.androidx.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
