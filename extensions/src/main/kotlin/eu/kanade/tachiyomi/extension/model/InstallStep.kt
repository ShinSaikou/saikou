// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/extension/model/InstallStep.kt (modified: no)
package eu.kanade.tachiyomi.extension.model

enum class InstallStep {
    Idle,
    Pending,
    Downloading,
    Installing,
    Installed,
    Error,
    ;

    fun isCompleted(): Boolean {
        return this == Installed || this == Error || this == Idle
    }
}
