package saikou.gradle.configurations

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import saikou.gradle.extensions.android
import saikou.gradle.extensions.libs

private val baseOptIns = listOf(
    "kotlin.time.ExperimentalTime",
    "kotlin.uuid.ExperimentalUuidApi",
    "kotlinx.coroutines.ExperimentalCoroutinesApi",
    "kotlinx.coroutines.FlowPreview",
    "kotlinx.serialization.ExperimentalSerializationApi",
    "coil3.annotation.ExperimentalCoilApi",
)

/** Java 17 for javac and the Kotlin JVM target; AGP's built-in Kotlin registers the `kotlin` extension when the Android plugin is applied. */
fun Project.configureKotlin() {
    val javaVersion = libs.versions.java.get()
    android {
        compileOptions.apply {
            sourceCompatibility = JavaVersion.toVersion(javaVersion)
            targetCompatibility = JavaVersion.toVersion(javaVersion)
        }
    }
    kotlinCompilerOptions {
        if (this is KotlinJvmCompilerOptions) {
            jvmTarget.set(JvmTarget.fromTarget(javaVersion))
        }
        freeCompilerArgs.addAll(baseOptIns.map { "-opt-in=$it" })
    }
}

fun Project.kotlinCompilerOptions(block: KotlinCommonCompilerOptions.() -> Unit) {
    extensions.configure<KotlinBaseExtension>("kotlin") {
        if (this is HasConfigurableKotlinCompilerOptions<*>) compilerOptions(block)
    }
}
