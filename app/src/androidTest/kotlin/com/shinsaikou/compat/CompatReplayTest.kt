package com.shinsaikou.compat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.extensions.loader.ExtensionArchive
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.util.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The device half of the compatibility job (contract 01 section 7.2 step 10).
 *
 * The job's host half samples the index, downloads each package and proves by `jdeps` that every
 * class it references resolves against the runtime classpath. That is a static answer, and the row
 * it feeds (`compat_sample_loaded_ratio`) claims something stronger: that the sample **loads**. A
 * package whose bytecode resolves on paper can still fail on a device, because the private class
 * loader, the signature check, the lib version policy and the entry-point construction are four
 * more places to fail and none of them exists on the host. This class is where the sample meets
 * them.
 *
 * What it replays is the recorded sample itself. The host writes the sampled packages into the
 * fixture directory it pushes, and the optional `expected.json` beside them carries what the host
 * recorded for each one. A package that loads but now reports another lib version, or fewer
 * sources than the index announced, is a compatibility regression that a load check alone would
 * report as a pass, which is the reason the expectation file exists at all.
 *
 * ### What the fixture directory has to hold
 *
 * `<fixtures>/compat/` with one extension package per file, and optionally `expected.json`:
 *
 * ```json
 * { "<file name>": { "libVersion": 1.6, "sources": 3 } }
 * ```
 *
 * A file the map does not mention is loaded and checked for a non-empty source list only. The map
 * is keyed by file name because nothing else about a package may be written down: no name of a
 * package, a source or an address is ever logged or asserted here, and the failure messages say
 * "sample entry #4" where a name would be the obvious thing to print.
 *
 * Every entry is loaded under its own class loader and none is released until the run ends, which
 * is what the host's ratio needs (each entry either loaded or did not) and what bounds how large a
 * sample one device run can take. A sample too large for the device's heap should be pushed in
 * parts rather than made to fit by loading fewer classes per entry.
 *
 * ### Why the trust store is written and then put back
 *
 * The loader refuses an untrusted package, and trust is rooted in the signing key of a registered
 * index. A gate device need not have one registered, so every entry would load as `Untrusted` and
 * the replay would measure the trust store instead of the sample. The host already verified every
 * signature against the index fingerprint before it recorded the sample (7.2 step 5), so trusting
 * the recorded entries here adds no hole: the bytes were checked before they were written down.
 * The previous manual trust set is restored afterwards, so a gate run leaves the device's trust
 * decisions as it found them.
 *
 * Without `saikou.fixtures.dir` there is no sample and the test returns early, which keeps the suite
 * green on a machine that has none. Under `saikou.strict` [Fixtures] fails first instead (R28), and
 * a fixtures directory that exists without a `compat/` subdirectory fails rather than returning,
 * because a run that was meant to replay and replayed nothing is a failing run (R29).
 */
@RunWith(AndroidJUnit4::class)
class CompatReplayTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val app = instrumentation.targetContext.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    /**
     * Built here rather than taken off the graph: neither the loader nor the trust store is an
     * [AppGraph] root, and both are stateless over their collaborators, so a second instance reads
     * and writes exactly what the process's own one does.
     */
    private val trustStore by lazy {
        TrustStore(graph.extensionStoreRepository, graph.extensionsPreferences)
    }

    private val loader by lazy {
        ExtensionLoader(
            context = instrumentation.targetContext,
            trustStore = trustStore,
            libVersionPolicy = LibVersionPolicy(graph.extensionsPreferences),
            injektBootstrap = graph.injektBootstrap,
        )
    }

    private var manualTrustBefore: Set<String> = emptySet()

    @Before
    fun rememberTheManualTrustSet() {
        manualTrustBefore = graph.extensionsPreferences.trustedExtensions.get()
        Fixtures.clearCache()
    }

    @After
    fun restoreTheManualTrustSet() {
        // delete() rather than set(emptySet()), so a device that had no manual overrides ends the
        // run with the key unset rather than with an empty set written over it.
        if (manualTrustBefore.isEmpty()) {
            graph.extensionsPreferences.trustedExtensions.delete()
        } else {
            graph.extensionsPreferences.trustedExtensions.set(manualTrustBefore)
        }
        Fixtures.clearCache()
    }

    /**
     * A pure check of [LANGUAGE] itself, independent of the fixture directory: every value the
     * contract allows must pass, and the check must still catch a source that reports something
     * that plainly is not a language at all.
     */
    @Test
    fun theLanguagePatternAcceptsEveryContractConformantValue() {
        // fil and ceb are the three-letter codes a 320 entry sample actually reported; they are
        // here so the widening that accepted them cannot be reverted without a red test.
        val conformant = listOf("", "all", "other", "en", "ja", "pt-BR", "zh-Hans", "zh-Hant-TW", "fil", "ceb")
        conformant.forEach { lang ->
            assertTrue("'$lang' should match the language contract", LANGUAGE.matches(lang))
        }

        // "engl" holds the upper bound: BCP 47 reserves four-letter primary subtags and no source
        // reports one, so widening past three letters would be widening past the evidence.
        val notALanguage = listOf("English", "engl", "1a", "EN", "-BR")
        notALanguage.forEach { lang ->
            assertTrue("'$lang' should not match the language contract", !LANGUAGE.matches(lang))
        }
    }

    @Test
    fun theRecordedSampleStillLoadsAndStillReportsWhatWasRecorded() {
        val directory = Fixtures.copyToCache(SAMPLE_DIRECTORY) ?: return

        val expectations = File(directory, EXPECTATIONS_FILE)
            .takeIf { it.isFile }
            ?.let { graph.json.parseToJsonElement(it.readText()).jsonObject }

        val entries = directory.listFiles()
            ?.filter { it.isFile && it.name != EXPECTATIONS_FILE }
            ?.sortedBy { it.name }
            ?: emptyList()

        assertTrue(
            "the recorded sample under $SAMPLE_DIRECTORY holds no package to replay",
            entries.isNotEmpty(),
        )

        // Every failure is collected before the test fails. One broken entry in a 320 package
        // sample is a ratio, and a report naming only the first would need one run per entry to
        // learn what the ratio is.
        val failures = runBlocking(Dispatchers.IO) {
            entries.mapIndexedNotNull { index, file -> replay(index, file, expectations) }
        }

        logcat(LOG_TAG, LogPriority.INFO) {
            "replayed ${entries.size} recorded entries, ${failures.size} failed"
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    /** Returns null when the entry replayed cleanly, or the one line describing what went wrong. */
    private suspend fun replay(index: Int, file: File, expectations: JsonObject?): String? {
        val label = "sample entry #$index"

        // From API 26 the platform refuses to load a dex file the process can still write to, which
        // is why the production install makes its private copy read only. The copy under the cache
        // has to be marked the same way, or every entry fails for a reason about this test rather
        // than about the entry.
        if (!file.setReadOnly()) return "$label could not be made read only for loading"

        val archive = loader.readArchive(file) ?: return "$label is not a readable extension package"
        if (archive.signerFingerprints.isEmpty()) return "$label is unsigned"

        trustStore.trust(archive.packageName, archive.versionCode, archive.signerFingerprints.last())

        return when (val result = loader.load(archive)) {
            is LoadResult.Untrusted ->
                "$label loaded as untrusted with ${archive.signerFingerprints.size} signer(s)"
            is LoadResult.Error ->
                "$label failed to load: ${result.reason}${result.cause?.let { " (${it::class.java.simpleName})" }.orEmpty()}"
            is LoadResult.Success -> checkAgainstTheRecording(label, archive, result, expectations)
        }
    }

    /**
     * The loaded result against the recording. The lib version is compared with a tolerance because
     * it is a float on the wire and an exact comparison of 1.4 would be a comparison of the decimal
     * literal's rounding, not of the version.
     */
    private fun checkAgainstTheRecording(
        label: String,
        archive: ExtensionArchive,
        result: LoadResult.Success,
        expectations: JsonObject?,
    ): String? {
        val sources = result.extension.sources
        if (sources.isEmpty()) return "$label loaded and published no source"

        val wrongLanguage = sources.count { !LANGUAGE.matches(it.lang) }
        if (wrongLanguage > 0) {
            return "$label reports $wrongLanguage source(s) whose language is not a language code"
        }

        val recorded = expectations?.get(archive.file.name)?.jsonObject ?: return null

        recorded["libVersion"]?.jsonPrimitive?.double?.let { expected ->
            val loaded = result.extension.libVersion
            if (kotlin.math.abs(loaded - expected) > LIB_VERSION_TOLERANCE) {
                return "$label loaded lib version $loaded where $expected was recorded"
            }
        }
        recorded["sources"]?.jsonPrimitive?.int?.let { expected ->
            if (sources.size != expected) {
                return "$label loaded ${sources.size} source(s) where $expected were recorded"
            }
        }
        return null
    }

    private companion object {
        const val LOG_TAG = "SaikouCompatReplay"

        /** The subdirectory of `saikou.fixtures.dir` the host writes the sample into. */
        const val SAMPLE_DIRECTORY = "compat"

        /** Optional, and keyed by file name so no package name is ever written down. */
        const val EXPECTATIONS_FILE = "expected.json"

        /**
         * design/contract/02-source-contract.md:88 spells `Source.lang` out as "ISO 639-1 language
         * code ('en'), 'all' for multi-language sources, 'other' or '' when unknown", so the empty
         * string is a contract-conformant value, not a violation. A bare two-letter code is also
         * not the whole of ISO 639-1 in practice: real packages report a region or script subtag
         * alongside it ("pt-BR", "zh-Hans"), which is exactly what
         * GlobalSearchViewModel.languageName feeds straight into `Locale.forLanguageTag` rather
         * than rejecting. A recorded sample carrying any of these must not be flagged as a
         * regression by this replay.
         *
         * The primary subtag is two OR THREE letters for the same reason. A 320 entry sample had
         * four failures and all four were the same thing: sources reporting `fil` (Filipino) and
         * `ceb` (Cebuano). Neither language has a two-letter ISO 639-1 code to report, so its
         * ISO 639-3 code is the only thing a source can honestly say, and `Locale.forLanguageTag`
         * accepts both. Rejecting them measured this regex rather than the sample.
         */
        val LANGUAGE = Regex("|all|other|[a-z]{2,3}(-[A-Za-z0-9]+)*")

        const val LIB_VERSION_TOLERANCE = 0.0001
    }
}
