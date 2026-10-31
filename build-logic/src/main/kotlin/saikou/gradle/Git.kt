package saikou.gradle

import org.gradle.api.Project

fun Project.gitShortSha(): String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
}.getOrDefault("unknown")
