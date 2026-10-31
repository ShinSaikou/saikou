package saikou.gradle.extensions

import com.android.build.api.dsl.CommonExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.plugins.PluginManager
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType

internal val Project.libs: LibrariesForLibs get() = the<LibrariesForLibs>()

internal fun Project.plugins(block: PluginManager.() -> Unit) {
    pluginManager.apply(block)
}

internal fun Project.android(block: CommonExtension.() -> Unit) {
    extensions.configure(block)
}

/**
 * JUnit Platform for every unit test task; Gradle 9 needs the launcher on the test runtime classpath (see the base plugin).
 *
 * Discovery issues fail the run from `WARNING` up (R89 carry-over 8). Jupiter reports a `@Test`
 * method it will not run, such as one returning a value (a Kotlin expression body like
 * `= runTest { }`), as a `WARNING`, and the launcher's default threshold is `ERROR`, so such a
 * method was skipped without a word and no JUnit XML could show it. build-logic's own tests set the
 * same property in `build-logic/build.gradle.kts`.
 */
internal fun Project.configureTest() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
        maxHeapSize = "1g"
        testLogging {
            events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
            showStandardStreams = false
        }
    }
}
