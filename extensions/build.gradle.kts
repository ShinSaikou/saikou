plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.extensions"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":source-contract"))
    implementation(project(":domain"))

    // Extension-facing ABI. Every artifact here is referenced by extension bytecode by name,
    // so it must be on the host runtime classpath. rxJava, rxAndroid and injekt are allowed
    // only in this module (contract 01, section 1.2).
    implementation(libs.rxJava)
    implementation(libs.rxAndroid)
    implementation(libs.injekt)
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.jsoup)
    implementation(libs.bundles.serialization)
    // Upstream registers an XML instance for extensions, so InjektBootstrap builds one. :core:metadata
    // exposes xmlutil as api, but the module graph keeps that module out of this one.
    implementation(libs.xmlutil.core)
    implementation(libs.xmlutil.serialization)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.logcat)

    implementation(libs.androidx.core)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.webkit)
    // quickjs is declared by :core:common, where JavaScriptEngine lives, and reaches this
    // module transitively.

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

// There was an exportRuntimeClasspath task here, which wrote this module's release runtime
// classpath for the compatibility job to index. It is gone: the job now reads the class
// definitions out of the release APK's dex files, which is what an installed extension actually
// loads against, and this module's classpath never was (:app carries classes this module does
// not). See the header of tools/compat/compat_job.py for the whole reason.
