package com.shinsaikou.bench

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.extensions.loader.ExtensionArchive
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallOutcome
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.util.Fixtures
import com.shinsaikou.util.StagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Installs and removes the extensions PLAN.md's cold start row is measured with: "no ANR with 76
 * extensions installed; zero PackageManager or OkHttpClient work on the main thread in the trace".
 *
 * ### What is installed, and how
 *
 * The first [EXTENSION_COUNT] packages of the anonymized compatibility sample, `compat/0000.apk`
 * onward under `saikou.fixtures.dir`, by file name. Each goes through the application's own private
 * install: the loader copies it into `filesDir/exts`, the extension manager loads it, and the one
 * that comes back untrusted is trusted through the manager, which is the action the extension list
 * offers the user. Nothing here reaches `PackageInstaller`, and nothing in the product exists for
 * this class (R39). The sample was signature-checked against its index when it was recorded, which
 * is why trusting it adds no hole; `CompatReplayTest` says the same for the whole sample.
 *
 * Idempotent: a package the manager already has loaded at the same version is left alone, so a
 * second seed on the same device costs the wait for the manager and nothing else. No package, source
 * or address is named in any message here, only the fixture entry's index.
 *
 * ### The two ways this runs
 *
 * As a benchmark step, with `saikou.bench.keepExtensions=true`, [seedTheExtensions] leaves the
 * packages installed, because the cold starts that need them run after it in other processes.
 * `tools/ci/phase0.sh` step 8 on the 2 GB AVD runs [removeTheExtensions] before the plain cold
 * start, so `cold_start_ms` keeps the definition its upstream baseline was taken under and an
 * interrupted run cannot leak extensions into it, then at the end of the device's iteration runs
 * [seedTheExtensions], the cold start pass with extensions, and [removeTheExtensions] again.
 *
 * Without that argument both methods are ordinary tests, which is what they are inside a connected
 * test run: the seed installs, loads and counts, then removes what it installed, so no later test in
 * the same process runs beside 76 extra extensions.
 *
 * Either way the seed reports how many of the packages the manager loaded, as the instrumentation
 * status `saikou.bench.extensionsLoaded`, which `am instrument -r` prints and step 8 reads back into
 * both rows' method notes, and fails when fewer than all of them loaded.
 */
@RunWith(AndroidJUnit4::class)
class BenchExtensionsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = instrumentation.targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /**
     * Built here rather than taken off the graph, as `CompatReplayTest` does: the loader is not an
     * [AppGraph] root, and it is stateless over the private directory, so a second instance writes
     * exactly where the manager's own one reads.
     */
    private val loader by lazy {
        ExtensionLoader(
            context = context,
            trustStore = TrustStore(graph.extensionStoreRepository, graph.extensionsPreferences),
            libVersionPolicy = LibVersionPolicy(graph.extensionsPreferences),
            injektBootstrap = graph.injektBootstrap,
        )
    }

    /**
     * The seed trusts each package as it goes, the last one milliseconds before it returns, and with
     * `saikou.bench.keepExtensions` the cold starts that load them run in later processes, so every
     * preference set is on disk before this process is killed (phase 5 unit TG4).
     */
    @After
    fun commitPreferences() = StagePreferences.commit()

    @Test
    fun seedTheExtensions() {
        val archives = fixtureArchives("seedTheExtensions") ?: return
        val packages = archives.mapTo(HashSet()) { it.packageName }
        try {
            val loaded = runBlocking(Dispatchers.IO) {
                withTimeout(SEED_TIMEOUT_MILLIS) {
                    graph.extensionManager.isInitialized.first { it }
                    archives.forEachIndexed { index, archive -> install(index, archive) }
                    graph.extensionManager.installedExtensions.value.count { it.packageName in packages }
                }
            }
            instrumentation.sendStatus(
                STATUS_IN_PROGRESS,
                Bundle().apply { putString(STATUS_LOADED, loaded.toString()) },
            )
            Log.i(LOG_TAG, "$loaded of ${archives.size} extensions loaded")
            assertEquals("extensions the manager loaded out of the ones installed", archives.size, loaded)
        } finally {
            if (!keepExtensions) remove(packages)
            Fixtures.clearCache()
        }
    }

    @Test
    fun removeTheExtensions() {
        val archives = fixtureArchives("removeTheExtensions") ?: return
        val packages = archives.mapTo(HashSet()) { it.packageName }
        Fixtures.clearCache()
        val remaining = remove(packages)
        Log.i(LOG_TAG, "removed ${archives.size} extensions, $remaining left")
        assertEquals("extensions still installed after the removal", 0, remaining)
    }

    /**
     * Reads the fixture files the seed installs. Null, after logging why, when the run was given no
     * fixture directory; under `saikou.strict` [Fixtures] fails instead.
     */
    private fun fixtureArchives(method: String): List<ExtensionArchive>? {
        val archives = mutableListOf<ExtensionArchive>()
        Fixtures.clearCache()
        for (index in 0 until EXTENSION_COUNT) {
            val name = String.format(Locale.ROOT, FIXTURE_NAME, index)
            val file = Fixtures.copyToCache("$SAMPLE_DIRECTORY/$name")
            if (file == null) {
                Log.i(LOG_TAG, "Skipped: $method: no saikou.fixtures.dir argument")
                return null
            }
            val archive = runBlocking { loader.readArchive(file) }
            assertNotNull("fixture entry #$index is not a readable extension package", archive)
            archives += archive!!
        }
        assertEquals(
            "the first $EXTENSION_COUNT fixture entries do not name $EXTENSION_COUNT distinct packages",
            EXTENSION_COUNT,
            archives.mapTo(HashSet()) { it.packageName }.size,
        )
        return archives
    }

    /** Leaves one package installed, loaded and trusted, by the application's own install path. */
    private suspend fun install(index: Int, archive: ExtensionArchive) {
        val manager = graph.extensionManager
        val current = manager.installedExtensions.value.firstOrNull { it.packageName == archive.packageName }
        if (current != null && current.versionCode == archive.versionCode) return

        val outcome = loader.installPrivateExtensionFile(archive.file)
        val refusal = (outcome as? InstallOutcome.Rejected)?.reason
        assertTrue(
            "fixture entry #$index was refused by the private install: $refusal",
            outcome is InstallOutcome.Installed,
        )
        val result = manager.reload(archive.packageName)
        if (result is LoadResult.Untrusted) manager.trust(result.extension)
    }

    /**
     * Uninstalls every package in [packages] through the manager and drops the manual trust the seed
     * recorded for them. Returns how many are still installed afterwards, on disk or in the manager.
     */
    private fun remove(packages: Set<String>): Int = runBlocking(Dispatchers.IO) {
        withTimeout(SEED_TIMEOUT_MILLIS) {
            val manager = graph.extensionManager
            manager.isInitialized.first { it }
            packages.forEach { manager.uninstall(it) }
            val trusted = graph.extensionsPreferences.trustedExtensions
            trusted.set(trusted.get().filterNot { entry -> entry.substringBefore(':') in packages }.toSet())
            packages.count { loader.extFileFor(it).exists() } +
                manager.installedExtensions.value.count { it.packageName in packages } +
                manager.untrustedExtensions.value.count { it.packageName in packages }
        }
    }

    /** Set by the benchmark step, which needs the packages installed for the cold starts after it. */
    private val keepExtensions: Boolean
        get() = InstrumentationRegistry.getArguments().getString(KEEP_ARGUMENT) == "true"

    private companion object {
        const val LOG_TAG = "SaikouBenchExtensions"

        /** PLAN.md's cold start row: "no ANR with 76 extensions installed". */
        const val EXTENSION_COUNT = 76

        /** The subdirectory of `saikou.fixtures.dir` holding the anonymized sample. */
        const val SAMPLE_DIRECTORY = "compat"

        const val FIXTURE_NAME = "%04d.apk"

        const val KEEP_ARGUMENT = "saikou.bench.keepExtensions"

        /** Printed by `am instrument -r` as `INSTRUMENTATION_STATUS: saikou.bench.extensionsLoaded=<n>`. */
        const val STATUS_LOADED = "saikou.bench.extensionsLoaded"

        /**
         * The status code a test reports a value under while it runs, the one the runner's result
         * parsers read as a metric rather than as the start or end of a test.
         */
        const val STATUS_IN_PROGRESS = 2

        /** 76 installs and 76 loads on the 2 GB AVD, with room for a cold dex verification of each. */
        const val SEED_TIMEOUT_MILLIS = 300_000L
    }
}
