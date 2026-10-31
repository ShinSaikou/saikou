package com.shinsaikou.extensions

import android.content.pm.PackageInfo
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.extensions.loader.ExtensionMetadata
import com.shinsaikou.extensions.loader.ExtensionSignatures
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import com.shinsaikou.util.Args
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream

/**
 * The reference run of contract 03 section 15, both extensions, one process, one session.
 *
 * Phase 0 step 7 names this class on the `am instrument` command line. Until the file existed the
 * step passed while running nothing: AGP reports BUILD SUCCESSFUL with `tests="0"` for a class
 * filter that matches no class, so an absent test was indistinguishable from a green one. That is
 * the first reason this file exists.
 *
 * The second is that nothing else in the repository loads a real extension. Every other extension
 * test drives a synthetic APK or a fake ABI source, which proves the host's own logic and says
 * nothing about whether a real signed catalog entry downloads, verifies, loads under the private
 * class loader and answers four ABI calls. That chain has one failure mode per link and only a
 * device can walk it.
 *
 * Everything the run needs comes from instrumentation arguments through [Args], so no catalog
 * address and no package name is ever written down here. Without those arguments each test returns
 * early and the suite stays green on a machine that has none; under `saikou.strict` [Args] throws
 * instead, which is what stops the gate from passing having asserted nothing. Assertions match
 * patterns (a hex fingerprint, a language code, a set of ids read back from the index) rather than
 * literals, and nothing logged is ever a name, a package or an address.
 *
 * One `@Test` with ordered steps, because the order is the assertion: an install that succeeds
 * after a load would prove nothing about either.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionHostTest {

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    @Test
    fun theTwoReferenceExtensionsInstallLoadAndServePages() {
        val repoUrl = Args.repoUrl ?: return
        val ext14 = Args.refExt14Package ?: return
        val ext16 = Args.refExt16Package ?: return

        runBlocking(Dispatchers.IO) {
            withTimeout(RUN_TIMEOUT_MILLIS) {
                val networkBefore = graph.networkHelper

                registerTheIndex(repoUrl)
                val available = refreshAndFindBoth(ext14, ext16)

                // 1.4 first, then 1.6: the lib version policy accepts both, and running them in
                // ascending order is how the research loader's log reads.
                val installed = available.map { entry -> runOneExtension(entry) }

                // Step 5: one process, one client. A second NetworkHelper would mean a second
                // cookie jar and a second cache, which is the bug this identity check catches.
                assertSame(
                    step(5, "the network helper was rebuilt during the run"),
                    networkBefore,
                    graph.networkHelper,
                )

                assertCookiesAreSharedWithTheWebView(installed)

                uninstallBoth(available.map { it.packageName })
            }
        }
    }

    /**
     * Step 1 and 2. A repeat run finds the row already there, which [AddExtensionStore] refuses by
     * design, so the refusal is tolerated and only the end state is asserted: the store table has
     * to hold at least one index by the time this returns.
     */
    private suspend fun registerTheIndex(repoUrl: String) {
        val added = graph.addExtensionStore.await(repoUrl)
        val stores = graph.extensionStoreRepository.getAll()
        assertTrue(
            step(1, "no index is configured after registering one; ${added.exceptionOrNull()}"),
            stores.isNotEmpty(),
        )
    }

    /**
     * Step 3. Returns the two index entries in lib version order. The signing key is checked here
     * rather than at install time because it is the value step 4b compares the signer against: a
     * blank or malformed key would make that comparison vacuously pass.
     */
    private suspend fun refreshAndFindBoth(ext14: String, ext16: String): List<Extension.Available> {
        val refreshed = graph.extensionManager.refreshAvailable()
        assertTrue(
            step(3, "the index refresh failed: ${refreshed.exceptionOrNull()}"),
            refreshed.isSuccess,
        )

        val listed = graph.extensionManager.availableExtensions.value
        this.logcat(LogPriority.INFO) { "index entries: ${listed.size}" }

        val entries = listOf(LIB_14 to ext14, LIB_16 to ext16).map { (expectedLib, packageName) ->
            val entry = listed.firstOrNull { it.packageName == packageName }
            assertNotNull(
                step(3, "the index does not list the reference lib-$expectedLib extension"),
                entry,
            )
            entry!!
        }

        entries.forEachIndexed { index, entry ->
            val expectedLib = if (index == 0) LIB_14 else LIB_16
            assertEquals(
                step(3, "the reference lib-$expectedLib entry reports another lib version"),
                expectedLib,
                entry.libVersion,
                0.0001,
            )
            // Field 501 of the index. Upstream's client drops it; the compat job needs it, so an
            // entry that arrives without one means the protobuf field number drifted.
            assertNotNull(step(3, "the reference lib-$expectedLib entry carries no jar url"), entry.jarUrl)
            assertTrue(
                step(3, "the store behind the reference lib-$expectedLib entry has no usable signing key"),
                SIGNING_KEY.matches(entry.store.signingKey),
            )
            assertFalse(
                step(3, "the store behind the reference lib-$expectedLib entry resolved as legacy"),
                entry.store.isLegacy,
            )
            assertTrue(
                step(3, "the reference lib-$expectedLib entry lists no source"),
                entry.sources.isNotEmpty(),
            )
        }
        return entries
    }

    /** Steps 4a to 4g for one extension, in order. */
    private suspend fun runOneExtension(entry: Extension.Available): Extension.Installed {
        val label = "the reference lib-${entry.libVersion} extension"

        // 4a. Install. The job is the manager's own; joining it covers the reload that follows the
        // Installed step, so installedExtensions is already published when this returns.
        graph.extensionManager.install(entry).join()
        val step = graph.extensionManager.installSteps.value[entry.packageName]
        assertEquals(
            step(4, "$label did not reach Installed (untrusted: " +
                "${graph.extensionManager.untrustedExtensions.value.size}, " +
                "load errors: ${graph.extensionManager.loadErrors.value.size})"),
            InstallStep.Installed,
            step,
        )

        val installed = graph.extensionManager.installedExtensions.value
            .firstOrNull { it.packageName == entry.packageName }
        assertNotNull(step(4, "$label installed but did not load"), installed!!)

        assertTrue(step(4, "$label left no file on disk"), installed.extFile.isFile)
        assertTrue(step(4, "$label wrote a zero length file"), installed.extFile.length() > 0)
        // The private install is a read-only lockdown: a writable copy could be swapped under the
        // class loader between the signature check and the load.
        assertFalse(step(4, "$label stayed writable after install"), installed.extFile.canWrite())

        // 4b. The signer equals the index signing key, read back out of the file that was installed
        // rather than out of the download, so a swapped file fails here.
        val archive = packageArchiveInfo(installed.extFile.path)
        assertNotNull(step(4, "$label is not a readable package archive"), archive!!)
        val fingerprints = ExtensionSignatures.fingerprints(archive).orEmpty()
        assertTrue(step(4, "$label is unsigned"), fingerprints.isNotEmpty())
        assertTrue(
            step(4, "$label signer does not match the index signing key"),
            fingerprints.contains(entry.store.signingKey),
        )

        // 4c. The load. The ids are compared as a set against the index entry rather than against a
        // written constant: identity rule 2 says the loaded ids win, so this asserts they agree
        // with the index without ever naming one.
        assertEquals(
            step(4, "$label loaded another lib version than the index announced"),
            entry.libVersion,
            installed.libVersion,
            0.0001,
        )
        assertTrue(step(4, "$label loaded no source"), installed.sources.isNotEmpty())
        assertEquals(
            step(4, "$label loaded source ids that differ from the index entry"),
            entry.sources.map { it.id }.toSet(),
            installed.sources.map { it.id }.toSet(),
        )
        installed.sources.forEach { source ->
            assertTrue(
                step(4, "$label reports a language that is not a language code"),
                LANGUAGE.matches(source.lang),
            )
        }

        val source = installed.sources.firstOrNull() as? CatalogueSource
        assertNotNull(step(4, "$label source is not browsable"), source!!)

        // 4d. Popular page 1.
        val popular = source.getPopularManga(1)
        assertTrue(step(4, "$label returned an empty popular listing"), popular.mangas.isNotEmpty())
        assertTrue(
            step(4, "$label returned a listing entry with no key"),
            popular.mangas.all { it.url.isNotBlank() && it.title.isNotBlank() },
        )
        this.logcat(LogPriority.INFO) {
            "popular page 1: ${popular.mangas.size} entries, next page ${popular.hasNextPage}"
        }

        // 4e to 4g, over as many popular entries as it takes, up to three.
        servePagesFromAPopularEntry(label, source, popular.mangas)

        return installed
    }

    /**
     * Steps 4e to 4g: details and chapters for a popular entry, the page list of its first chapter,
     * and that list's first image.
     *
     * The page leg is the one request a live host has been seen to refuse outright: the reference
     * run passed everything up to it and then got HTTP 403 for the chapter's pages, with no
     * challenge in the body, and a host-side request for the same address got the same answer.
     * That is the third-party site declining one entry, not Saikou, so a refusal of the page leg
     * moves on to the next popular entry, up to [PAGE_LEG_ENTRIES], and the run passes on the first
     * that serves a page list and a page (ruling R63). Everything else fails where it did: an
     * entry's details and chapters are asserted for every entry tried, and a page list or an image
     * that arrives but is wrong is a failure, not a refusal.
     *
     * Only [HttpException] is a refusal. A Cloudflare failure is never retried, because the solver
     * owns that path and a retry would hide it: the interceptor rethrows both of its failures as a
     * plain `IOException` (see `ErrorText`), which is not an [HttpException] and so ends the run
     * here with its own message. When every entry tried refuses, the failure names the statuses,
     * the entry count and the two owner inputs, so it reads as an input to replace. It stays a
     * failure: a reference run that served no page did not prove the page leg (ruling R29).
     */
    private suspend fun servePagesFromAPopularEntry(
        label: String,
        source: CatalogueSource,
        popular: List<MangaInfo>,
    ) {
        val entries = popular.take(PAGE_LEG_ENTRIES)
        val refusals = mutableListOf<Int>()
        entries.forEachIndexed { index, entry ->
            val position = "popular entry ${index + 1} of ${entries.size}"

            // 4e. Details and chapters in one call, which is the path the library update pass uses.
            val update = source.getMangaUpdate(entry, chapters = emptyList(), fetchDetails = true, fetchChapters = true)
            assertTrue(step(4, "$label returned uninitialised details"), update.manga?.initialized == true)
            val chapters = update.chapters
            assertTrue(step(4, "$label returned no chapters"), !chapters.isNullOrEmpty())
            this.logcat(LogPriority.INFO) { "$position: chapters: ${chapters!!.size}" }

            try {
                // 4f. Pages.
                val pages = source.getPageList(chapters!!.first())
                assertTrue(step(4, "$label returned no pages"), pages.isNotEmpty())
                assertEquals(
                    step(4, "$label returned pages that are not in reading order"),
                    pages.indices.toList(),
                    pages.map(PageInfo::index),
                )
                this.logcat(LogPriority.INFO) { "$position: pages: ${pages.size}" }

                // 4g. The first image, sniffed with the host's own magic-byte reader rather than a
                // hand-written signature table, so this test and the reader agree on what an image is.
                assertImageBytes(label, source, pages.first())
                return
            } catch (refused: HttpException) {
                refusals += refused.code
                this.logcat(LogPriority.WARN) {
                    "$position: the host refused the page leg of $label with HTTP ${refused.code}"
                }
            }
        }
        throw AssertionError(
            step(
                4,
                "the host behind $label refused the page leg of every popular entry tried " +
                    "(${entries.size} entr${if (entries.size == 1) "y" else "ies"}, " +
                    "HTTP ${refusals.joinToString()}). A third-party site is declining these " +
                    "requests; this is not a Saikou defect. The reference extensions are the owner " +
                    "inputs $OWNER_INPUT_14 and $OWNER_INPUT_16 in local.properties: replace this " +
                    "lib version's one with another extension of the same lib version from the " +
                    "same index.",
            ),
        )
    }

    /**
     * Step 4g. Progress is logged rather than asserted: the hook only fires when the extension made
     * its request through the progress-aware call, and a source that did not is not broken.
     */
    private suspend fun assertImageBytes(
        label: String,
        source: CatalogueSource,
        page: PageInfo,
    ) {
        val bytes = source.getImage(page).use { it.bytes() }
        assertTrue(step(4, "$label served an empty image body"), bytes.isNotEmpty())
        val type = ImageUtil.findImageType(ByteArrayInputStream(bytes))
        assertNotNull(
            step(4, "$label served ${bytes.size} bytes that are not a recognised image"),
            type,
        )
        this.logcat(LogPriority.INFO) {
            "image: ${bytes.size} bytes, ${type?.mime}, progress ${page.progress.value}"
        }
    }

    /**
     * Step 6. The cookie store a WebView writes to and the jar OkHttp reads from are one store, so
     * a challenge solved in a WebView is a cookie the next request carries.
     *
     * A WebView is instantiated on the main thread before the write, because that is what proves
     * the device has a usable one; the cookie itself goes through [CookieManager], which is the
     * store a page load would have written to anyway. The read stops at
     * [AndroidCookieJar.loadForRequest], the exact method OkHttp's bridge calls to build a `Cookie`
     * header, rather than opening a socket: a real request would make this assertion depend on the
     * source being reachable, and what is under test is the store, not the network.
     *
     * The host address comes off the loaded source at runtime and is never logged.
     */
    private fun assertCookiesAreSharedWithTheWebView(installed: List<Extension.Installed>) {
        val homeUrl = installed.asSequence()
            .flatMap { it.sources.asSequence() }
            .mapNotNull { it.homeUrl }
            .mapNotNull { it.toHttpUrlOrNull() }
            .firstOrNull()
        if (homeUrl == null) {
            this.logcat(LogPriority.WARN) { "step 6 skipped: no loaded source reported a home address" }
            return
        }

        val name = "saikou_test_cookie"
        val value = System.currentTimeMillis().toString()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            WebView(instrumentation.targetContext)
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie(homeUrl.toString(), "$name=$value")
                flush()
            }
        }

        val jar = AndroidCookieJar()
        val seen = jar.loadForRequest(homeUrl)
        assertTrue(
            step(6, "a cookie written through the WebView store was not visible to the OkHttp jar"),
            seen.any { it.name == name && it.value == value },
        )
        this.logcat(LogPriority.INFO) {
            "RESULT: a cookie set via the WebView store was VISIBLE to OkHttp via AndroidCookieJar"
        }
        jar.remove(homeUrl, listOf(name))
    }

    /** Step 7. The session leaves the device as it found it, so a rerun installs from scratch. */
    private suspend fun uninstallBoth(packageNames: List<String>) {
        packageNames.forEach { packageName ->
            graph.extensionManager.uninstall(packageName)
        }
        val remaining = graph.extensionManager.installedExtensions.value.map { it.packageName }
        assertTrue(
            step(7, "${packageNames.count { it in remaining }} reference extension(s) survived uninstall"),
            packageNames.none { it in remaining },
        )
    }

    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(path: String): PackageInfo? =
        InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .getPackageArchiveInfo(path, ExtensionMetadata.PACKAGE_FLAGS)

    private fun step(number: Int, message: String): String = "step $number: $message"

    private companion object {
        /** Contract 03 section 15: the catalog signing key is a SHA-256 in lowercase hex. */
        val SIGNING_KEY = Regex("[0-9a-f]{64}")

        /**
         * A language tag as the catalog index gives it: ISO 639-1 or 639-2/3 letters, with
         * region or script subtags such as pt-BR, es-419 and zh-Hans, or all or other.
         */
        val LANGUAGE = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*|all|other")

        const val LIB_14 = 1.4
        const val LIB_16 = 1.6

        /** How many popular entries the page leg tries before the host counts as refusing (R63). */
        const val PAGE_LEG_ENTRIES = 3

        /** The owner inputs a refusing reference is replaced through; keys, never their values. */
        const val OWNER_INPUT_14 = "saikou.ref.ext14.pkg"
        const val OWNER_INPUT_16 = "saikou.ref.ext16.pkg"

        /**
         * Two installs, two index fetches and eight ABI calls over a real network. Generous, and
         * still a bound: a hung solve would otherwise park this test until the runner's own
         * timeout, which reports far less about where it stopped.
         */
        const val RUN_TIMEOUT_MILLIS = 300_000L
    }
}
