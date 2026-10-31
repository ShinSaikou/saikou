import com.android.build.api.dsl.BuildFeatures
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import saikou.gradle.configurations.kotlinCompilerOptions
import saikou.gradle.extensions.alias
import saikou.gradle.extensions.android
import saikou.gradle.extensions.api
import saikou.gradle.extensions.debugApi
import saikou.gradle.extensions.implementation
import saikou.gradle.extensions.libs
import saikou.gradle.extensions.plugins

private val composeOptIns = listOf(
    "androidx.compose.animation.ExperimentalAnimationApi",
    "androidx.compose.foundation.ExperimentalFoundationApi",
    "androidx.compose.foundation.layout.ExperimentalLayoutApi",
    "androidx.compose.material3.ExperimentalMaterial3Api",
    "androidx.compose.ui.ExperimentalComposeUiApi",
)

@Suppress("UNUSED")
class SaikouComposePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.kotlin.compose.compiler)
        }

        android {
            buildFeatures {
                compose = true
            }
        }

        kotlinCompilerOptions {
            freeCompilerArgs.addAll(composeOptIns.map { "-opt-in=$it" })
        }

        dependencies {
            implementation(platform(libs.androidx.compose.bom))
            implementation(libs.bundles.compose)
            api(libs.androidx.compose.uiToolingPreview)
            debugApi(libs.androidx.compose.uiTooling)
        }
    }
}

private fun CommonExtension.buildFeatures(block: BuildFeatures.() -> Unit) {
    buildFeatures.apply(block)
}
