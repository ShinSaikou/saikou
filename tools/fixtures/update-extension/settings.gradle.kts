// The library update fixture extension (phase 2 unit A, ruling R66). A build of its own on
// purpose: the product's settings.gradle.kts never includes it, so checkModuleGraph, lint and the
// application's APKs never see it. Build it with tools/fixtures/update-extension/build.sh.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
    // The product's catalog, so the plugin and the OkHttp the stubs compile against move with it.
    versionCatalogs {
        create("libs") {
            from(files("../../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "update-fixture-extension"

include(":stubs")
include(":extension")
