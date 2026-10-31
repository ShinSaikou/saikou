plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.core.metadata"
}

dependencies {
    // A standalone leaf: the ComicInfo and MangaDetails models plus the shared XML instance.
    api(libs.xmlutil.core)
    api(libs.xmlutil.serialization)
    implementation(libs.kotlinx.serialization.json)
}
