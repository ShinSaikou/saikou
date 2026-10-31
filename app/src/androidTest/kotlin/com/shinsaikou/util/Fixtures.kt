package com.shinsaikou.util

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Device-side access to the fixture directory named by the `saikou.fixtures.dir` instrumentation
 * argument (contract 01 section 8.3).
 *
 * Infrastructure for the instrumentation tests rather than a test itself, and it has no JVM
 * equivalent: the argument bundle, the pushed directory and the cache directory only exist inside
 * an instrumentation run. The tools push fixtures to a world-readable path under
 * `/data/local/tmp/`, which the app process is not guaranteed to keep read access to across API
 * levels, so [copyToCache] copies a fixture into the target context's cache and every test reads
 * the copy.
 *
 * [dir] and [copyToCache] are nullable for the reason [Args] gives: a device test cannot skip, so a
 * run without fixtures returns null and the test body returns with it. Under `saikou.strict` the
 * null never happens, because [Args] fails first.
 */
object Fixtures {

    /** Instrumentation argument holding the device path of the pushed fixture directory. */
    private const val FIXTURES_DIR_ARGUMENT = "saikou.fixtures.dir"

    /** One cache subdirectory for every copy, so [clearCache] can drop them all in one call. */
    private const val CACHE_DIRECTORY_NAME = "saikou-fixtures"

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The pushed fixture directory, or null when the run was given none. Reading it goes through
     * [Args], so a strict run fails here instead of returning null.
     */
    val dir: File? get() = Args.fixturesDir?.let(::File)

    /**
     * Whether the fixture directory is present and readable by this process.
     *
     * This reads the argument bundle directly instead of [dir], because [Args] fails a strict run
     * the moment it is touched. A caller that wants to make that decision itself, to combine it
     * with another condition, or to report the absence its own way needs an answer back rather than
     * a thrown error.
     */
    fun available(): Boolean {
        val path = InstrumentationRegistry.getArguments().getString(FIXTURES_DIR_ARGUMENT)
        if (path.isNullOrBlank()) return false
        val directory = File(path)
        return directory.isDirectory && directory.canRead()
    }

    /**
     * Resolves [relativePath] under [dir], copies it into [cacheDir] keeping its relative shape,
     * and returns the copy, or null when the run was given no fixture directory at all. A fixture
     * the run was supposed to push and did not push fails the test rather than returning null: the
     * directory was there, so the test was meant to run.
     */
    fun copyToCache(relativePath: String): File? {
        val fixturesDir = dir ?: return null
        val source = File(fixturesDir, relativePath)
        if (!source.exists()) {
            throw AssertionError("fixture $relativePath is missing from ${fixturesDir.path}")
        }
        val target = File(cacheDir(), relativePath)
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        // A directory fixture has to be walked: File.copyTo on a directory creates an empty
        // directory and reports success, which would surface much later as an empty page list.
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
        } else {
            source.copyTo(target, overwrite = true)
        }
        return target
    }

    /** The cache subdirectory the copies land in, created if this is the first call of the run. */
    fun cacheDir(): File = File(targetContext.cacheDir, CACHE_DIRECTORY_NAME).apply { mkdirs() }

    /**
     * Deletes every copy made by [copyToCache]. Call it from `@Before` or `@After` so one test
     * cannot read a fixture a previous test left behind under the same name.
     */
    fun clearCache() {
        File(targetContext.cacheDir, CACHE_DIRECTORY_NAME).deleteRecursively()
    }
}
