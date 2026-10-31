plugins {
    alias(libs.plugins.saikou.android.library)
}

android {
    namespace = "com.shinsaikou.core.archive"
}

dependencies {
    // A standalone leaf, as in upstream: libarchive, unifile and jsoup, no module dependencies.
    // UniFile is part of the public API (UniFile.archiveReader), so consumers see it.
    api(libs.unifile)
    implementation(libs.archive)
    implementation(libs.jsoup)

    androidTestImplementation(libs.bundles.androidx.test)
}
