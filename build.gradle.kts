import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

buildscript {
    dependencies {
        // Pins the Kotlin Gradle plugin AGP's built-in Kotlin uses, exactly as upstream v0.20.4 does.
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

// Contract 01, section 1.2. Keep this map identical to the table there.
val allowedProjectDeps: Map<String, Set<String>> = mapOf(
    ":core:common" to emptySet(),
    ":core:archive" to setOf(":core:common"),
    ":core:metadata" to setOf(":core:common"),
    ":source-contract" to emptySet(),
    ":i18n" to emptySet(),
    ":presentation-core" to setOf(":core:common", ":i18n", ":domain"),
    ":domain" to setOf(":core:common", ":source-contract"),
    ":data" to setOf(":core:common", ":source-contract", ":domain"),
    ":sources:local" to setOf(":core:common", ":core:archive", ":core:metadata", ":source-contract", ":domain", ":i18n"),
    ":backup" to setOf(":core:common", ":source-contract", ":domain", ":data", ":tracking", ":library-update", ":i18n"),
    ":reader" to setOf(":core:common", ":core:archive", ":source-contract", ":domain", ":presentation-core", ":i18n"),
    ":downloads" to setOf(":core:common", ":core:archive", ":core:metadata", ":source-contract", ":domain"),
    ":library-update" to setOf(":core:common", ":source-contract", ":domain", ":i18n"),
    ":sync" to setOf(":core:common", ":domain"),
    ":tracking" to setOf(":core:common", ":domain", ":i18n"),
    ":extensions" to setOf(":core:common", ":source-contract", ":domain"),
    ":bench" to emptySet(),
)
val extensionsOnlyGroups = setOf("io.reactivex", "com.github.mihonapp")
val extensionsOnlyImports = listOf("import rx.", "import uy.kohesive.injekt", "import dev.mihon.injekt", "import eu.kanade.tachiyomi.source.")
val containerProjects = setOf(":core", ":sources")

tasks.register("checkModuleGraph") {
    group = "verification"
    description = "Fails on any project dependency, library or import the module graph forbids."
    doLast {
        val problems = mutableListOf<String>()
        subprojects.filter { it.path !in containerProjects }.forEach { p ->
            val allowed = allowedProjectDeps[p.path]
            if (allowed == null && p.path != ":app") {
                problems += "${p.path}: not listed in the module graph"
                return@forEach
            }
            listOf("api", "implementation").mapNotNull { p.configurations.findByName(it) }.forEach { c ->
                c.dependencies.forEach { d ->
                    when (d) {
                        is ProjectDependency ->
                            if (p.path == ":app" && d.path == ":bench" || p.path != ":app" && d.path !in allowed!!) {
                                problems += "${p.path} -> ${d.path} (${c.name}) is not allowed"
                            }
                        is ExternalModuleDependency ->
                            if (p.path != ":extensions" && d.group in extensionsOnlyGroups) {
                                problems += "${p.path} depends on ${d.group}:${d.name}, allowed only in :extensions"
                            }
                    }
                }
            }
            if (p.path != ":extensions") {
                p.fileTree("src") { include("**/*.kt") }.forEach { f ->
                    val text = f.readText()
                    extensionsOnlyImports.filter { it in text }.forEach { problems += "${f.relativeTo(rootDir)}: '$it' is allowed only in :extensions" }
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

tasks.register("checkVendoringMarkers") {
    group = "verification"
    description = "Fails on any file in a vendored package whose first line does not declare where it came from."
    doLast {
        val vendoredPackageDirs = listOf("/eu/kanade/", "/tachiyomi/")
        val problems = mutableListOf<String>()
        subprojects.forEach { p ->
            p.fileTree("src") { include("**/*.kt") }.forEach { f ->
                val path = f.absolutePath.replace(File.separatorChar, '/')
                if (vendoredPackageDirs.none { it in path }) return@forEach
                val first = f.useLines { it.firstOrNull() }.orEmpty()
                val declared = first.startsWith("// Vendored from upstream v") || first.startsWith("// Fresh:")
                if (!declared) {
                    problems += "${f.relativeTo(rootDir)}: first line must be '// Vendored from upstream v<version>: <path> (modified: yes|no)' or '// Fresh: <why it lives here>'"
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

tasks.register<Exec>("scanForbiddenWords") {
    group = "verification"
    description = "Runs tools/scan-forbidden-words.sh; SAIKOU_FORBIDDEN_WORDS_FILE must be set."
    workingDir = rootDir
    commandLine("bash", "tools/scan-forbidden-words.sh")
}
