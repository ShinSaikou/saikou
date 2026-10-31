import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DefaultConfig
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import saikou.gradle.configurations.configureKotlin
import saikou.gradle.extensions.android
import saikou.gradle.extensions.configureTest
import saikou.gradle.extensions.coreLibraryDesugaring
import saikou.gradle.extensions.libs
import saikou.gradle.extensions.testImplementation
import saikou.gradle.extensions.testRuntimeOnly

@Suppress("UNUSED")
class SaikouAndroidBasePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        configureKotlin()
        configureTest()

        android {
            compileSdk = libs.versions.android.sdk.compile.get().toInt()

            defaultConfig {
                minSdk = libs.versions.android.sdk.min.get().toInt()
                if (this is ApplicationDefaultConfig) {
                    targetSdk = libs.versions.android.sdk.target.get().toInt()
                }
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            compileOptions.apply {
                isCoreLibraryDesugaringEnabled = true
            }
        }

        dependencies {
            coreLibraryDesugaring(libs.android.desugar)
            // A com.android.test module has no unit test source set.
            if (!pluginManager.hasPlugin("com.android.test")) {
                testImplementation(libs.bundles.test)
                testRuntimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

private fun CommonExtension.defaultConfig(block: DefaultConfig.() -> Unit) {
    defaultConfig.apply(block)
}
