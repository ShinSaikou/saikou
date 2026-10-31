package com.shinsaikou.ui.browse

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallOutcome
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.i18n.R
import com.shinsaikou.source.SourceIds
import com.shinsaikou.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.Locale

/**
 * Upstream's Browse source filter screen on the real [MainActivity] and graph (phase 5 unit BF):
 * reached from the Sources page's Filter action, its toggles leave and return on the Sources page,
 * and with no catalogue source but the Local one it is upstream's empty screen.
 *
 * The toggles need catalogue sources in a language, which only an extension brings. The fixture
 * extension (`tools/fixtures/update-extension/`, twenty English sources "Update Fixture 01" to "20")
 * is installed through the application's private install from the path `saikou.download.fixture.apk`
 * names, as `GateRowsTest` installs it, and removed again; without the argument that method logs a
 * `Skipped:` line and returns. The empty screen is checked only when no extension is installed, and
 * logs a `Skipped:` line otherwise. Both preferences the screen writes are put back whatever happens.
 * Browse fetches every configured extension index when nothing is available, so each method returns
 * with a `Skipped:` line before Browse opens while a store other than the browse fixture's is
 * configured (phase 5 unit TG2).
 */
@RunWith(AndroidJUnit4::class)
class SourcesFilterDeviceTest {

    /** Outermost, as in `MainActivitySmokeTest`: the permission dialog would cover the activity. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /** The battery exemption dialog and a crash banner answered first, as in `MainActivitySmokeTest`. */
    @get:Rule(order = 1)
    val firstLaunchAnswered = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                graph.basePreferences.batteryExemptionRequested.set(true)
                graph.crashLog.markExported()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (context.applicationContext as SaikouApp).graph

    /** The library's title and then the end of its spinner, as `MainActivitySmokeTest` waits. */
    @Before
    fun awaitLibraryScreen() {
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.library_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * Upstream draws Filter on its Sources and Extensions tabs, each opening its own screen (the
     * Extensions page's since phase 5 unit BX, checked by `ExtensionFilterDeviceTest`), and none on
     * Migrate. One action per page, never the two at once.
     */
    @Test
    fun theFilterActionIsOnTheSourcesAndExtensionsPagesOnly() {
        if (aForeignStoreIsConfigured("theFilterActionIsOnTheSourcesAndExtensionsPagesOnly")) return
        openSourcesPage()
        assertEquals("Filter actions on the Sources page", 1, filterActions())

        composeRule.onNode(hasText(string(R.string.browse_tab_extensions)) and isSelectable()).performClick()
        composeRule.waitForIdle()
        assertEquals("Filter actions on the Extensions page", 1, filterActions())

        composeRule.onNode(hasText(string(R.string.label_migration)) and isSelectable()).performClick()
        composeRule.waitForIdle()
        assertEquals("Filter actions on the Migrate page", 0, filterActions())
    }

    @Test
    fun withNoExtensionInstalledTheFilterScreenSaysNoSourceIsInstalled() {
        if (aForeignStoreIsConfigured("withNoExtensionInstalledTheFilterScreenSaysNoSourceIsInstalled")) return
        val installed = runBlocking(Dispatchers.IO) {
            graph.sourceManager.isInitialized.first { it }
            graph.sourceManager.getCatalogueSources().first().filterNot { it.id == SourceIds.LOCAL }
        }
        if (installed.isNotEmpty()) {
            return skip(
                "withNoExtensionInstalledTheFilterScreenSaysNoSourceIsInstalled: an extension is installed, " +
                    "the empty screen was not exercised",
            )
        }

        openSourcesPage()
        openFilterScreen()
        awaitText(string(R.string.source_filter_empty_screen))

        goBack()
        awaitText(string(R.string.local_source))
    }

    /**
     * English switched off takes every fixture source off the Sources page and leaves the Local
     * source; switched back on, they return; one fixture source unchecked leaves alone. Each change
     * is read in the preference the screen writes and on the Sources page after back.
     */
    @Test
    fun aLanguageSwitchedOffAndASourceUncheckedLeaveTheSourcesPage() {
        val apk = InstrumentationRegistry.getArguments().getString(FIXTURE_APK_ARGUMENT)
        if (apk.isNullOrBlank()) {
            // Written out, so gate.sh's expected skip can be checked against this line.
            return skip(
                "aLanguageSwitchedOffAndASourceUncheckedLeaveTheSourcesPage: no saikou.download.fixture.apk argument",
            )
        }
        if (aForeignStoreIsConfigured("aLanguageSwitchedOffAndASourceUncheckedLeaveTheSourcesPage")) return
        val preferences = graph.sourcePreferences
        val oldLanguages = preferences.enabledLanguages.get()
        val oldDisabled = preferences.disabledSources.get()
        preferences.enabledLanguages.set(setOf(LANGUAGE_ALL, ENGLISH))
        preferences.disabledSources.set(emptySet())
        var packageName: String? = null
        try {
            val (installedPackage, sources) = runBlocking(Dispatchers.IO) { installTheFixtureExtension(apk) }
            packageName = installedPackage
            val first = sources[0]
            val second = sources[1]
            val english = Locale.forLanguageTag(ENGLISH).displayName

            openSourcesPage()
            awaitText(first.name)
            openFilterScreen()
            awaitText(english)
            awaitText(first.name)
            assertNoText(string(R.string.local_source))

            // English off: its sources leave the filter screen and the Sources page.
            composeRule.onNode(hasText(english) and isToggleable()).assertIsOn().performClick()
            awaitNoText(first.name)
            composeRule.onNode(hasText(english) and isToggleable()).assertIsOff()
            assertEquals(setOf(LANGUAGE_ALL), preferences.enabledLanguages.get())
            goBack()
            awaitText(string(R.string.local_source))
            awaitNoText(first.name)

            // English on again, then one source unchecked: only that source leaves.
            openFilterScreen()
            composeRule.onNode(hasText(english) and isToggleable()).performClick()
            awaitText(first.name)
            assertEquals(setOf(LANGUAGE_ALL, ENGLISH), preferences.enabledLanguages.get())
            composeRule.onNode(hasText(first.name) and isToggleable()).assertIsOn().performClick()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                preferences.disabledSources.get() == setOf(first.id.toString())
            }
            composeRule.onNode(hasText(first.name) and isToggleable()).assertIsOff()
            goBack()
            awaitText(second.name)
            awaitNoText(first.name)
        } finally {
            preferences.enabledLanguages.set(oldLanguages)
            preferences.disabledSources.set(oldDisabled)
            packageName?.let { runBlocking(Dispatchers.IO) { removeTheFixtureExtension(it) } }
        }
    }

    /**
     * Browse builds the Extensions page's view model, which fetches every configured index while
     * nothing is available, so a store other than the browse fixture's (whose fetch reaches only the
     * host, see `FixtureStore`) makes [method] log a `Skipped:` line and return before Browse opens
     * (phase 5 unit TG2, the guard `ExtensionFilterDeviceTest` has).
     */
    private fun aForeignStoreIsConfigured(method: String): Boolean {
        val foreign = runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }
        if (foreign.isEmpty()) return false
        skip(
            "$method: an extension store other than the browse fixture store is configured, " +
                "Browse was not opened so that nothing is fetched",
        )
        return true
    }

    private fun openSourcesPage() {
        composeRule.onNode(hasText(string(R.string.tab_browse)) and isSelectable()).performClick()
        composeRule.onNode(hasText(string(R.string.browse_tab_sources)) and isSelectable()).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 1 }
    }

    /**
     * The screen is up once Browse has left the composition: its bar's Filter action is gone (the
     * filter screen has none), and with it the Sources page's rows, which carry the same names.
     */
    private fun openFilterScreen() {
        composeRule.onNode(hasContentDescription(string(R.string.action_filter)) and hasClickAction()).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            filterActions() == 0 &&
                composeRule.onAllNodesWithText(string(R.string.label_sources)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun goBack() {
        composeRule.onAllNodes(hasContentDescription(string(R.string.action_back)) and hasClickAction())
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 1 }
    }

    private fun filterActions(): Int =
        composeRule.onAllNodes(hasContentDescription(string(R.string.action_filter)) and hasClickAction())
            .fetchSemanticsNodes()
            .size

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoText(text: String) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun assertNoText(text: String) {
        assertTrue("\"$text\" is on screen", composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
    }

    /**
     * The fixture extension from [apkPath], installed through the private install and trusted
     * through the manager as `GateRowsTest.installTheFixtureExtension` does, with its first two
     * sources by name once the registry answers for them. Returns the package name too, for
     * [removeTheFixtureExtension].
     */
    private suspend fun installTheFixtureExtension(apkPath: String): Pair<String, List<FixtureSource>> {
        val copy = File(context.cacheDir, FIXTURE_APK_COPY)
        File(apkPath).copyTo(copy, overwrite = true)
        try {
            val loader = ExtensionLoader(
                context = context,
                trustStore = TrustStore(graph.extensionStoreRepository, graph.extensionsPreferences),
                libVersionPolicy = LibVersionPolicy(graph.extensionsPreferences),
                injektBootstrap = graph.injektBootstrap,
            )
            val archive = loader.readArchive(copy)
            assertNotNull("$apkPath is not a readable extension package", archive)
            val manager = graph.extensionManager
            manager.isInitialized.first { it }
            val current = manager.installedExtensions.value.firstOrNull { it.packageName == archive!!.packageName }
            if (current == null || current.versionCode != archive!!.versionCode) {
                val outcome = loader.installPrivateExtensionFile(copy)
                assertTrue("the private install refused the fixture: $outcome", outcome is InstallOutcome.Installed)
                val result = manager.reload(archive!!.packageName)
                if (result is LoadResult.Untrusted) manager.trust(result.extension)
            }
            val packageName = archive.packageName
            val installed = manager.installedExtensions.first { list -> list.any { it.packageName == packageName } }
                .first { it.packageName == packageName }
            val sources = installed.sources.sortedBy { it.name }.take(2).map { FixtureSource(it.id, it.name) }
            assertEquals("the fixture extension has fewer than two sources", 2, sources.size)
            graph.sourceManager.isInitialized.first { it }
            graph.sourceManager.getCatalogueSources().first { list -> sources.all { s -> list.any { it.id == s.id } } }
            return installed.packageName to sources
        } finally {
            copy.delete()
        }
    }

    /** Uninstalls [packageName] through the manager and drops the trust the install recorded. */
    private suspend fun removeTheFixtureExtension(packageName: String) {
        graph.extensionManager.uninstall(packageName)
        val trusted = graph.extensionsPreferences.trustedExtensions
        trusted.set(trusted.get().filterNot { it.substringBefore(':') == packageName }.toSet())
    }

    /** What the test needs of a fixture source, so no live source outlives the install. */
    private data class FixtureSource(val id: Long, val name: String)

    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: $reason")
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        const val LOG_TAG = "SourcesFilterDeviceTest"

        /** The fixture extension's APK on the device, the argument `GateRowsTest` reads. */
        const val FIXTURE_APK_ARGUMENT = "saikou.download.fixture.apk"
        const val FIXTURE_APK_COPY = "saikou-sources-filter-fixture.apk"

        const val LANGUAGE_ALL = "all"
        const val ENGLISH = "en"

        /** Empty below API 33, where `POST_NOTIFICATIONS` is not a runtime permission. */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        /** A cold start opens the graph and the database before the library flow can emit. */
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        /** A screen's first load, the source registry's included. */
        const val SCREEN_TIMEOUT_MILLIS = 15_000L
    }
}
