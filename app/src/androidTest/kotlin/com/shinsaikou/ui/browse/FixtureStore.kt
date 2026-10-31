package com.shinsaikou.ui.browse

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.di.AppGraph
import com.shinsaikou.extensions.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.net.HttpURLConnection
import java.net.URL

/**
 * The update fixture server's synthetic repository as an extension store, for the device tests of
 * Browse and the extension screens that need a store and must fetch nothing from anywhere but the
 * host (phase 5 unit TG2).
 *
 * **The server.** `tools/bench/browse_fixture_server.py`, R66's frozen update fixture server wrapped
 * with its log readable over HTTP and a listing page that can fail. The fixture extension only ever
 * calls port 18794, so whoever runs a class that uses this takes the port 18794 lock, checks that
 * nothing listens there, and starts it on the host for the class's whole run:
 *
 *     python3 tools/bench/browse_fixture_server.py --log <file> --repo-dir tools/out/update-fixture
 *
 * (`tools/out/update-fixture` holds unit A's APK and signer digest, as `gate.sh` step 5f reads them),
 * then passes the runner argument `saikou.browse.fixture=true`. Without it every method that needs the
 * server logs a `Skipped:` line and returns before anything opens. The server is stopped and the lock
 * released when the class ends.
 *
 * **The store.** [add] adds [ADDRESS] through `AddExtensionStore`, the interactor behind the "Extension
 * stores" screen's Add, and [restore] removes it through `ExtensionStoreRepository.remove`, the call
 * behind that screen's remove confirmation; each only if the store was not already configured. The
 * legacy index resolves to its descriptor, so the row's address is [INDEX_URL]. [restore] also
 * uninstalls the fixture extension if [installTheExtension] installed it, empties the available
 * list (an index fetch with no store left), and puts back the two preferences an index fetch writes.
 *
 * **Nothing else is asked.** A device test cannot see the requests the app makes: the shared
 * `OkHttpClient` is built in the product with no hook, and adding one would be a test-only product
 * path (R39). What stands in is [assertOnlyTheFixtureWasAsked]: every address the app holds for a
 * store (each configured store's index, each available extension's APK, icon and jar) is on the
 * fixture server, so every request the app can make of a store goes there; and every request the
 * test made the app make is in the server's log, read from `/control/log`, with nothing the server
 * does not serve. A request sent anywhere else would be missing from the log, and the test fails.
 */
class FixtureStore(private val graph: AppGraph) {

    private var addedTheStore = false
    private var installedPackage: String? = null
    private var keptLastIndexRefresh: Long? = null
    private var keptUpdatesCount: Int? = null

    /** Whether [installTheExtension] installed the extension, which [restore] then uninstalls. */
    val installedTheExtension: Boolean get() = installedPackage != null

    /** How many requests the server had logged when [add] began; the class's earlier methods' are before it. */
    private var firstRequest = 0

    /**
     * Adds the fixture store unless it is configured already, as the stores screen's Add does, after
     * keeping what an index fetch will overwrite.
     */
    suspend fun add() {
        firstRequest = allServed().size
        val preferences = graph.extensionsPreferences
        keptLastIndexRefresh = preferences.lastIndexRefresh.takeIf { it.isSet() }?.get()
        keptUpdatesCount = preferences.extensionUpdatesCount.takeIf { it.isSet() }?.get()
        if (graph.extensionStoreRepository.get(INDEX_URL) != null) return
        val store = graph.addExtensionStore.await(ADDRESS).getOrElse { error ->
            throw AssertionError("the fixture store at $ADDRESS was not added; is browse_fixture_server.py running?", error)
        }
        addedTheStore = true
        assertEquals("the fixture store's row address", INDEX_URL, store.indexUrl)
    }

    /** The fixture extension as the store's index lists it, after a fetch of every configured index. */
    suspend fun availableExtension(): Extension.Available {
        graph.extensionManager.refreshAvailable().getOrElse { throw AssertionError("the fixture store's index did not load", it) }
        return graph.extensionManager.availableExtensions.value.firstOrNull { it.packageName == EXTENSION_PACKAGE }
            ?: throw AssertionError("the fixture store lists no $EXTENSION_PACKAGE")
    }

    /**
     * The fixture extension installed from the store, as the Extensions page's Install installs it:
     * downloaded from the store's APK address, checked against the store's signing key and loaded.
     * An extension already installed is used as it is and left installed by [restore].
     */
    suspend fun installTheExtension(): Extension.Installed {
        val manager = graph.extensionManager
        manager.isInitialized.first { it }
        manager.installedExtensions.value.firstOrNull { it.packageName == EXTENSION_PACKAGE }?.let { return it }
        manager.install(availableExtension()).join()
        assertEquals(
            "the fixture extension's install from the store ended on",
            InstallStep.Installed,
            manager.installSteps.value[EXTENSION_PACKAGE],
        )
        installedPackage = EXTENSION_PACKAGE
        val installed = manager.installedExtensions.value.firstOrNull { it.packageName == EXTENSION_PACKAGE }
            ?: throw AssertionError(
                "the fixture extension did not load as trusted after its install; untrusted: " +
                    manager.untrustedExtensions.value.map { it.packageName },
            )
        graph.sourceManager.isInitialized.first { it }
        graph.sourceManager.getCatalogueSources().first { list -> installed.sources.all { s -> list.any { it.id == s.id } } }
        return installed
    }

    /**
     * Leaves the device as [add] found it: the extension uninstalled if this installed it (with any
     * trust entry for it), the store removed if this added it, the available list emptied when no
     * store is left, and the two index preferences back. Safe to call after a failure anywhere; each
     * step runs whatever the one before it did, and the first failure is thrown after the last.
     */
    suspend fun restore() {
        val teardown = Teardown()
        val packageName = installedPackage
        if (packageName != null) {
            teardown.step { graph.extensionManager.uninstall(packageName) }
            teardown.step {
                val trusted = graph.extensionsPreferences.trustedExtensions
                trusted.set(trusted.get().filterNot { it.substringBefore(':') == packageName }.toSet())
            }
            installedPackage = null
        }
        if (addedTheStore) {
            teardown.step { graph.extensionStoreRepository.remove(INDEX_URL) }
            addedTheStore = false
        }
        // With no store configured this fetches nothing and empties the list, so a later class in
        // the same process does not find this store's extension still listed.
        teardown.step {
            if (graph.extensionStoreRepository.getAll().isEmpty()) graph.extensionManager.refreshAvailable()
        }
        val preferences = graph.extensionsPreferences
        teardown.step {
            val lastIndexRefresh = keptLastIndexRefresh
            if (lastIndexRefresh != null) preferences.lastIndexRefresh.set(lastIndexRefresh) else preferences.lastIndexRefresh.delete()
        }
        teardown.step {
            val updatesCount = keptUpdatesCount
            if (updatesCount != null) preferences.extensionUpdatesCount.set(updatesCount) else preferences.extensionUpdatesCount.delete()
        }
        teardown.finish()
    }

    /**
     * Every address the app holds for a store is on the fixture server, and each of [paths] is in the
     * server's log, answered 200; nothing in the log is a request the server does not serve. See the
     * class comment for why this is the check.
     */
    suspend fun assertOnlyTheFixtureWasAsked(vararg paths: String) {
        val stores = graph.extensionStoreRepository.getAll()
        assertTrue("no extension store is configured", stores.isNotEmpty())
        stores.forEach { assertTrue("a store outside the fixture is configured: ${it.indexUrl}", it.indexUrl.startsWith("$SERVER/")) }
        val available = graph.extensionManager.availableExtensions.value
        assertTrue("no store lists any extension", available.isNotEmpty())
        available.forEach { extension ->
            listOfNotNull(extension.apkUrl, extension.iconUrl, extension.jarUrl).forEach { url ->
                assertTrue("${extension.packageName} has an address outside the fixture: $url", url.startsWith("$SERVER/"))
            }
        }
        val served = awaitServed(*paths)
        val unknown = served.filter { it.kind == "unknown" }
        assertTrue("the app asked the fixture server for what it does not serve: $unknown", unknown.isEmpty())
    }

    /**
     * The server's log once each of [paths] is in it with a 200, or a failure naming what is missing.
     * The server logs a request after its response is flushed (R140 item 3), so the log is read again
     * until the lines are there.
     */
    fun awaitServed(vararg paths: String, timeoutMillis: Long = LOG_TIMEOUT_MILLIS): List<Served> {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            val served = served()
            val missing = paths.filterNot { path -> served.any { it.path == path && it.status == HttpURLConnection.HTTP_OK } }
            if (missing.isEmpty()) return served
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError(
                    "the fixture server's log has no 200 for $missing; it holds ${served.map { "${it.status} ${it.path}" }}",
                )
            }
            Thread.sleep(LOG_POLL_MILLIS)
        }
    }

    /** The requests the server has logged since [add] began, in arrival order, its control requests left out. */
    fun served(): List<Served> = allServed().drop(firstRequest)

    private fun allServed(): List<Served> {
        val requests = JSONObject(control(LOG_PATH)).getJSONArray("requests")
        return (0 until requests.length()).map { i ->
            val record = requests.getJSONObject(i)
            Served(
                kind = record.getString("kind"),
                path = record.getString("path"),
                status = if (record.isNull("status")) null else record.getInt("status"),
            )
        }
    }

    /** From now on page [page] and later of every fixture source's listing are answered 500. */
    fun failListingPagesFrom(page: Int) {
        val status = JSONObject(control("$FAIL_PATH?from=$page"))
        assertEquals("the fixture server's failing page", page, status.getInt("failFromPage"))
    }

    /** Every listing page is served again. */
    fun answerAgain() {
        val status = JSONObject(control(ANSWER_PATH))
        assertTrue("the fixture server still fails a page: $status", status.isNull("failFromPage"))
    }

    private fun control(path: String): String {
        val connection = URL("$SERVER$path").openConnection() as HttpURLConnection
        connection.connectTimeout = CONTROL_TIMEOUT_MILLIS
        connection.readTimeout = CONTROL_TIMEOUT_MILLIS
        return try {
            assertEquals("the fixture server answered $path with", HttpURLConnection.HTTP_OK, connection.responseCode)
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    /** One line of the server's log, what a test compares. */
    data class Served(val kind: String, val path: String, val status: Int?)

    companion object {
        /** The runner argument that says the server is up; any other value, or none, skips. */
        const val ARGUMENT = "saikou.browse.fixture"

        /** The emulator's name for the host's loopback, on the one port the fixture extension calls. */
        const val SERVER = "http://10.0.2.2:18794"

        /** What the owner would type into the stores screen: the legacy flat index. */
        const val ADDRESS = "$SERVER/repo/index.min.json"

        /** The store row's address, the descriptor the legacy index resolves to. */
        const val INDEX_URL = "$SERVER/repo/repo.json"

        /** `tools/bench/update_fixture.py`'s names, which the repository serves. */
        const val EXTENSION_PACKAGE = "app.fixture.libraryupdate"
        const val EXTENSION_NAME = "Library Update Fixture"
        const val STORE_NAME = "Library update fixture"

        const val INDEX_PATH = "/repo/index.min.json"
        const val DESCRIPTOR_PATH = "/repo/repo.json"
        const val APK_PATH = "/repo/apk/update-fixture.apk"
        const val ICON_PATH = "/repo/icon/$EXTENSION_PACKAGE.png"

        private const val LOG_PATH = "/control/log"
        private const val FAIL_PATH = "/control/fail"
        private const val ANSWER_PATH = "/control/answer"
        private const val CONTROL_TIMEOUT_MILLIS = 10_000
        private const val LOG_TIMEOUT_MILLIS = 10_000L
        private const val LOG_POLL_MILLIS = 200L

        /** Whether the run says the browse fixture server is up on the host. */
        fun isServed(): Boolean = InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true"

        /**
         * The configured stores other than the fixture's. Opening Browse fetches every configured
         * index when nothing is available, so a class returns before Browse opens while this is not
         * empty: the fixture's own store reaches only the host, any other may reach the internet.
         */
        suspend fun foreignStores(graph: AppGraph): List<String> =
            graph.extensionStoreRepository.getAll().map { it.indexUrl }.filterNot { it == INDEX_URL }
    }
}
