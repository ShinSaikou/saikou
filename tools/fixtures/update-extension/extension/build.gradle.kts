// The library update fixture extension: twenty synthetic sources through one factory (ruling R66).
// A release build signed with the machine's debug key, whose SHA-256 build.sh writes beside the APK
// for the fixture server's repo.json, so each host trusts it through its own repository path.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.fixture.libraryupdate"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.fixture.libraryupdate"
        minSdk = libs.versions.android.sdk.min.get().toInt()
        targetSdk = 36
        versionCode = 1
        // The loader reads the extension library version from here when the manifest omits it:
        // 1.4 is in both hosts' accepted set (upstream v0.20.4 and Saikou both accept 1.4 and 1.6).
        versionName = "1.4.1"
    }
    buildTypes {
        // Release, unminified, signed with the debug key: a debug build adds a dex of API stubs
        // for platform classes newer than minSdk, which an extension has no business carrying.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            // The commit hash would make every rebuild a different APK for no change in the code.
            vcsInfo.include = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":stubs"))
    compileOnly(libs.okhttp.core)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.gradle.get()}")
}
