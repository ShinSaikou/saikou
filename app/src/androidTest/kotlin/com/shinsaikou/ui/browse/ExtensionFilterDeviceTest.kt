package com.shinsaikou.ui.browse

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
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
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.Locale

/**
 * Upstream's Extensions filter screen on the real [MainActivity] and graph (phase 5 unit BX): the
 * Extensions page's Filter action opens it, and with no extension store configured it is upstream's
 * empty screen; back returns to the Extensions page.
 *
 * Nothing here fetches an index but the fixture's. With a store configured, opening the Extensions
 * page would fetch it, so the empty screen's method checks the stores first and, finding one, logs a
 * `Skipped:` line and returns before the page opens. The screen's rows and toggles are checked on
 * made-up states by [ExtensionFilterContentTest][com.shinsaikou.ui.browse.extension.ExtensionFilterContentTest],
 * and over a real index from [FixtureStore]'s store on the host by the second method (phase 5 unit TG2).
 */
@RunWith(AndroidJUnit4::class)
class ExtensionFilterDeviceTest {

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

    @Test
    fun theExtensionsPagesFilterOpensTheExtensionFilterWithItsEmptyScreen() {
        val stores = runBlocking(Dispatchers.IO) { graph.extensionStoreRepository.getAll() }
        if (stores.isNotEmpty()) {
            return skip(
                "theExtensionsPagesFilterOpensTheExtensionFilterWithItsEmptyScreen: an extension store is configured, " +
                    "the page was not opened so that nothing is fetched",
            )
        }

        composeRule.onNode(hasText(string(R.string.tab_browse)) and isSelectable()).performClick()
        composeRule.onNode(hasText(string(R.string.browse_tab_extensions)) and isSelectable()).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 1 }

        // The screen is up once Browse has left the composition: its bar's Filter action is gone
        // (the filter screen has none), and the filter's own title and empty screen are drawn.
        composeRule.onNode(hasContentDescription(string(R.string.action_filter)) and hasClickAction()).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            filterActions() == 0 &&
                composeRule.onAllNodesWithText(string(R.string.label_extensions)).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(string(R.string.empty_screen)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "the empty screen lists no language",
            composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().isEmpty(),
        )

        // Back to the Extensions page, still selected, with its Filter action again.
        composeRule.onAllNodes(hasContentDescription(string(R.string.action_back)) and hasClickAction())
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 1 }
        composeRule.onNode(hasText(string(R.string.browse_tab_extensions)) and isSelectable()).assertIsSelected()
    }

    /**
     * R140 item 7's carry-over (phase 5 unit TG2): over [FixtureStore]'s store, the filter lists the
     * fixture extension's one language; switched off, the extension leaves the Extensions page on
     * return, and switched on again it comes back. Needs the browse fixture server and
     * `saikou.browse.fixture=true`, and returns with a `Skipped:` line without them or when another
     * store is configured. The enabled languages and the store are put back whatever happens.
     */
    @Test
    fun overTheFixtureStoreTheFilterListsEnglishAndItsSwitchHidesAndShowsTheExtension() {
        val method = "overTheFixtureStoreTheFilterListsEnglishAndItsSwitchHidesAndShowsTheExtension"
        if (!FixtureStore.isServed()) return skip("$method: no saikou.browse.fixture argument")
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            return skip("$method: an extension store other than the browse fixture store is configured")
        }
        val languages = graph.sourcePreferences.enabledLanguages
        val keptLanguages = languages.takeIf { it.isSet() }?.get()
        val fixture = FixtureStore(graph)
        val english = Locale.forLanguageTag(ENGLISH).displayName
        val englishSwitch = hasText(english) and isToggleable()
        val teardown = Teardown()
        teardown.step {
            languages.set(setOf(LANGUAGE_ALL, ENGLISH))
            runBlocking(Dispatchers.IO) { fixture.add() }

            composeRule.onNode(hasText(string(R.string.tab_browse)) and isSelectable()).performClick()
            composeRule.onNode(hasText(string(R.string.browse_tab_extensions)) and isSelectable()).performClick()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 1 }
            awaitText(FixtureStore.EXTENSION_NAME)

            // The filter lists English alone, the one language the fixture extension's index entry has.
            openFilter()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(englishSwitch).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("language switches on the filter", 1, composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().size)

            // Off: written to the preference, and the extension is gone from the page on return.
            composeRule.onNode(englishSwitch).assertIsOn().performClick()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { languages.get() == setOf(LANGUAGE_ALL) }
            composeRule.onNode(englishSwitch).assertIsOff()
            goBack()
            awaitNoText(FixtureStore.EXTENSION_NAME)

            // On again: back on the page.
            openFilter()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(englishSwitch).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(englishSwitch).assertIsOff().performClick()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { languages.get() == setOf(LANGUAGE_ALL, ENGLISH) }
            goBack()
            awaitText(FixtureStore.EXTENSION_NAME)

            runBlocking(Dispatchers.IO) {
                fixture.assertOnlyTheFixtureWasAsked(FixtureStore.INDEX_PATH, FixtureStore.DESCRIPTOR_PATH)
            }
            val served = fixture.served()
            Log.i(LOG_TAG, "$method: the fixture server logged ${served.size} requests: ${served.map { "${it.status} ${it.path}" }}")
        }
        teardown.step { runBlocking(Dispatchers.IO) { fixture.restore() } }
        teardown.step { if (keptLanguages != null) languages.set(keptLanguages) else languages.delete() }
        teardown.finish()
    }

    /** The filter screen is up once Browse has left the composition, taking its Filter action along. */
    private fun openFilter() {
        composeRule.onNode(hasContentDescription(string(R.string.action_filter)) and hasClickAction()).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            filterActions() == 0 &&
                composeRule.onAllNodesWithText(string(R.string.label_extensions)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun goBack() {
        composeRule.onAllNodes(hasContentDescription(string(R.string.action_back)) and hasClickAction())
            .onFirst()
            .performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 1 }
    }

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

    private fun filterActions(): Int =
        composeRule.onAllNodes(hasContentDescription(string(R.string.action_filter)) and hasClickAction())
            .fetchSemanticsNodes()
            .size

    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: $reason")
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        const val LOG_TAG = "ExtensionFilterDeviceTest"

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

        /** A screen's first load. */
        const val SCREEN_TIMEOUT_MILLIS = 15_000L
    }
}
