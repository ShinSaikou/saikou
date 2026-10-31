plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.saikou.compose)
}

android {
    namespace = "com.shinsaikou.presentation.core"
}

dependencies {
    // api: SettingsItems and util/Preference.kt expose tachiyomi.core.common.preference types.
    api(project(":core:common"))
    // api: the theme enums from :domain appear in SaikouTheme's public signature.
    api(project(":domain"))
    api(project(":i18n"))
    // api: StateViewModel is the base class every view model in :app and :reader extends.
    api(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
}
