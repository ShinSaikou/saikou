// Compile-only stand-ins for the extension ABI both hosts ship (upstream v0.20.4's source-api and the
// same classes vendored into Saikou's :extensions). Nothing here is packaged: the extension takes it
// as compileOnly, and at run time every call resolves to the host's own class. Each declaration
// copies the host's JVM signature and visibility and nothing else, so a mismatch shows up as a
// load failure in the host rather than as behaviour the fixture invented.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.fixture.libraryupdate.stubs"
    compileSdk = 36
    defaultConfig {
        minSdk = libs.versions.android.sdk.min.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.okhttp.core)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.gradle.get()}")
}
