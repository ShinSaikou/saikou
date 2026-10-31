import org.gradle.api.Plugin
import org.gradle.api.Project
import saikou.gradle.extensions.alias
import saikou.gradle.extensions.libs
import saikou.gradle.extensions.plugins

@Suppress("UNUSED")
class SaikouAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.library)
            alias(libs.plugins.saikou.android.base)
        }
    }
}
