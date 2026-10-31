plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.shinsaikou.source"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)  // JsonObject for MangaInfo.memo / ChapterInfo.memo
    api(libs.okio)                        // BufferedSource in ImageResult
    api(libs.androidx.preference)         // PreferenceScreen in ConfigurableSource
}
