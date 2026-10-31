plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.samWithReceiver)
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    compileOnly(libs.android.gradle)
    compileOnly(libs.kotlin.gradle)
    compileOnly(libs.kotlin.compose.compiler.gradle)

    // Lets the compiled plugins reference the version catalog through the generated accessor class.
    compileOnly(files(libs::class.java.superclass.protectionDomain.codeSource.location))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The release signing tests (phase 3 unit R1) sign throwaway APKs with the SDK's apksigner and make
// throwaway keys with the JDK's keytool. The SDK comes from the environment, as the gate exports it;
// a machine without one fails those tests rather than skipping them.
tasks.test {
    useJUnitPlatform()
    // A @Test that JUnit will not run fails the run instead of vanishing (see configureTest()).
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
    (System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME"))
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("saikou.sdk.dir", it) }
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

gradlePlugin {
    plugins {
        register("saikou-android-application") {
            id = "saikou.android.application"
            implementationClass = "SaikouAndroidApplicationPlugin"
        }
        register("saikou-android-base") {
            id = "saikou.android.base"
            implementationClass = "SaikouAndroidBasePlugin"
        }
        register("saikou-android-library") {
            id = "saikou.android.library"
            implementationClass = "SaikouAndroidLibraryPlugin"
        }
        register("saikou-android-test") {
            id = "saikou.android.test"
            implementationClass = "SaikouAndroidTestPlugin"
        }
        register("saikou-compose") {
            id = "saikou.compose"
            implementationClass = "SaikouComposePlugin"
        }
    }
}
