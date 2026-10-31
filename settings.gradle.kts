pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

// Type-safe project accessors are off: a project named `extensions` makes Gradle generate
// RootProjectAccessor.getExtensions(), which clashes with ExtensionAware.getExtensions() and
// breaks Kotlin DSL script compilation (contract 01 errata). Modules use project(":path").

rootProject.name = "Saikou"

// The image utilities live in their own repository, saikou-image, and arrive from JitPack by default.
// A checkout named by `saikou.image.dir` in local.properties (or SAIKOU_IMAGE_DIR) replaces the
// published artifact, so a change there can be built and tested here before it is tagged.
val localProperties = java.util.Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val saikouImageDir = System.getenv("SAIKOU_IMAGE_DIR")?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("saikou.image.dir")?.takeIf { it.isNotBlank() }
if (saikouImageDir != null) {
    includeBuild(saikouImageDir) {
        dependencySubstitution {
            substitute(module("com.github.ShinSaikou:saikou-image")).using(project(":image"))
        }
    }
}

include(":app")
include(":backup")
include(":bench")
include(":core:archive")
include(":core:common")
include(":core:metadata")
include(":data")
include(":domain")
include(":downloads")
include(":extensions")
include(":i18n")
include(":library-update")
include(":presentation-core")
include(":reader")
include(":source-contract")
include(":sources:local")
include(":sync")
include(":tracking")
