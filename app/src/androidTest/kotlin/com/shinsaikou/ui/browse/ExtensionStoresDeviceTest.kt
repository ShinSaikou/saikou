package com.shinsaikou.ui.browse

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The extension store screens over the fixture store (phase 5 unit TG2): [FixtureStore] adds the
 * update fixture server's repository, the Extensions page lists its extension, and R111's removal
 * confirmation names the store and does what each of its answers says. A store added or removed on
 * the stores screen changes the Extensions page with no refresh.
 *
 * Both methods need `tools/bench/browse_fixture_server.py` on the host and `saikou.browse.fixture=true`
 * ([FixtureStore] says how it is started); without the argument each logs a `Skipped:` line and
 * returns. A store other than the fixture's makes each return before Browse opens too, since the
 * page fetches every configured index. The store is removed and the device left as found whatever
 * happens.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionStoresDeviceTest {

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
     * Item 1's proof of the helper: the store added as the stores screen adds one, the Extensions
     * page listing the fixture extension from it with the store counted in its bar's menu, the add's two
     * requests and the page's index fetch in the server's log, and no store and no available
     * extension left once the helper restores the device.
     */
    @Test
    fun theFixtureStoreIsListedOnTheExtensionsPageWithEveryRequestInTheServersLog() {
        val method = "theFixtureStoreIsListedOnTheExtensionsPageWithEveryRequestInTheServersLog"
        if (!FixtureStore.isServed()) return skip("$method: no saikou.browse.fixture argument")
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            return skip("$method: an extension store other than the browse fixture store is configured")
        }
        val configuredBefore = runBlocking(Dispatchers.IO) { graph.extensionStoreRepository.get(FixtureStore.INDEX_URL) } != null
        val fixture = FixtureStore(graph)
        val teardown = Teardown()
        teardown.step {
            runBlocking(Dispatchers.IO) { fixture.add() }
            openExtensionsPage()
            awaitText(FixtureStore.EXTENSION_NAME)
            openOverflow()
            awaitText(string(R.string.ext_stores_count_format, 1))

            runBlocking(Dispatchers.IO) {
                fixture.assertOnlyTheFixtureWasAsked(FixtureStore.INDEX_PATH, FixtureStore.DESCRIPTOR_PATH)
            }
            val served = fixture.served()
            logServed(method, served)
            if (!configuredBefore) {
                // The add reads the legacy index, then the descriptor it names, in that order.
                assertEquals(
                    "the add's requests",
                    listOf(FixtureStore.INDEX_PATH, FixtureStore.DESCRIPTOR_PATH),
                    served.take(2).map { it.path },
                )
            }
            // The page's own fetch on opening: the store's descriptor, then its legacy list.
            assertTrue(
                "the Extensions page did not fetch the store's index: ${served.map { it.path }}",
                served.count { it.path == FixtureStore.INDEX_PATH } >= 2 && served.count { it.path == FixtureStore.DESCRIPTOR_PATH } >= 2,
            )
        }
        teardown.step { runBlocking(Dispatchers.IO) { fixture.restore() } }
        teardown.finish()
        if (!configuredBefore) {
            assertNull(
                "the fixture store is still configured",
                runBlocking(Dispatchers.IO) { graph.extensionStoreRepository.get(FixtureStore.INDEX_URL) },
            )
            assertTrue("an available extension outlived the store", graph.extensionManager.availableExtensions.value.isEmpty())
        }
    }

    /**
     * R111 item 8 and its carry-over 1: removing a store asks first, in upstream's dialog naming the store
     * by name and index address. Cancel closes it and keeps the store; OK removes it, and the screen
     * says no store is configured. Reached as the owner reaches it, from the Extensions page's bar.
     */
    @Test
    fun removingTheFixtureStoreAsksFirstNamingItAndCancelKeepsItWhileOkRemovesIt() {
        val method = "removingTheFixtureStoreAsksFirstNamingItAndCancelKeepsItWhileOkRemovesIt"
        if (!FixtureStore.isServed()) return skip("$method: no saikou.browse.fixture argument")
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            return skip("$method: an extension store other than the browse fixture store is configured")
        }
        val fixture = FixtureStore(graph)
        val teardown = Teardown()
        teardown.step {
            runBlocking(Dispatchers.IO) { fixture.add() }
            openExtensionsPage()
            awaitText(FixtureStore.EXTENSION_NAME)
            openStores(storeCount = 1)
            awaitText(FixtureStore.STORE_NAME)
            awaitText(FixtureStore.INDEX_URL)

            val title = string(R.string.ext_stores_remove_title)
            val body = string(R.string.ext_stores_remove_body, FixtureStore.STORE_NAME, FixtureStore.INDEX_URL)
            val remove = hasText(string(R.string.action_remove)) and hasClickAction()
            val ok = hasText(string(R.string.action_ok)) and hasClickAction()
            val cancel = hasText(string(R.string.action_cancel)) and hasClickAction()

            // Cancel: the dialog goes, and the store stays in the table and on the screen.
            composeRule.onNode(remove).performClick()
            awaitText(title)
            awaitText(body)
            composeRule.onNode(ok).assertExists("the removal dialog has no OK")
            composeRule.onNode(cancel).performClick()
            awaitNoText(title)
            assertTrue(
                "Cancel removed the fixture store",
                runBlocking(Dispatchers.IO) { graph.extensionStoreRepository.get(FixtureStore.INDEX_URL) } != null,
            )
            awaitText(FixtureStore.INDEX_URL)

            // OK: the store leaves the table, then the screen, which says none is configured.
            composeRule.onNode(remove).performClick()
            awaitText(body)
            composeRule.onNode(cancel).assertExists("the removal dialog has no Cancel")
            composeRule.onNode(ok).performClick()
            awaitNoText(title)
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                runBlocking(Dispatchers.IO) { graph.extensionStoreRepository.get(FixtureStore.INDEX_URL) } == null
            }
            awaitText(string(R.string.ext_stores_empty))
            awaitNoText(FixtureStore.INDEX_URL)
            logServed(method, fixture.served())
        }
        teardown.step { runBlocking(Dispatchers.IO) { fixture.restore() } }
        teardown.finish()
    }

    /**
     * The owner's way in: a repository typed on the stores screen lists its extension on the
     * Extensions page as soon as the screen is left, with no pull, and removing it there takes the
     * extension off the page the same way. The page was opened with no store, so its own fetch on
     * opening had nothing to read; what lists the extension is the add's read of the one index.
     */
    @Test
    fun aStoreAddedOnTheStoresScreenListsItsExtensionWithNoRefreshAndRemovingItTakesItOff() {
        val method = "aStoreAddedOnTheStoresScreenListsItsExtensionWithNoRefreshAndRemovingItTakesItOff"
        if (!FixtureStore.isServed()) return skip("$method: no saikou.browse.fixture argument")
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            return skip("$method: an extension store other than the browse fixture store is configured")
        }
        val fixture = FixtureStore(graph)
        val teardown = Teardown()
        teardown.step {
            runBlocking(Dispatchers.IO) { fixture.expectTheScreensAdd() }
            openExtensionsPage()
            awaitText(string(R.string.ext_empty_no_stores))
            openStores(storeCount = 0)
            awaitText(string(R.string.ext_stores_empty))

            composeRule.onNode(hasSetTextAction()).performTextInput(FixtureStore.ADDRESS)
            composeRule.onNode(hasText(string(R.string.action_add)) and hasClickAction()).performClick()
            awaitText(FixtureStore.INDEX_URL)
            back()
            awaitText(FixtureStore.EXTENSION_NAME)
            runBlocking(Dispatchers.IO) {
                fixture.assertOnlyTheFixtureWasAsked(FixtureStore.INDEX_PATH, FixtureStore.DESCRIPTOR_PATH)
            }
            logServed(method, fixture.served())

            openStores(storeCount = 1)
            composeRule.onNode(hasText(string(R.string.action_remove)) and hasClickAction()).performClick()
            awaitText(string(R.string.ext_stores_remove_title))
            composeRule.onNode(hasText(string(R.string.action_ok)) and hasClickAction()).performClick()
            awaitText(string(R.string.ext_stores_empty))
            back()
            awaitText(string(R.string.ext_empty_no_stores))
            awaitNoText(FixtureStore.EXTENSION_NAME)
            assertTrue("an available extension outlived the store", graph.extensionManager.availableExtensions.value.isEmpty())
        }
        teardown.step { runBlocking(Dispatchers.IO) { fixture.restore() } }
        teardown.finish()
    }

    /** The Extensions page bar's menu, then its repositories, named with [storeCount]. */
    private fun openStores(storeCount: Int) {
        openOverflow()
        composeRule.onNode(hasText(string(R.string.ext_stores_count_format, storeCount)) and hasClickAction()).performClick()
        awaitText(string(R.string.ext_stores_title))
    }

    private fun openOverflow() {
        composeRule.onNode(hasContentDescription(string(R.string.action_menu_overflow_description)) and hasClickAction())
            .performClick()
    }

    private fun back() {
        composeRule.onAllNodes(hasContentDescription(string(R.string.action_back)) and hasClickAction())
            .onFirst()
            .performClick()
    }

    private fun awaitNoText(text: String) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openExtensionsPage() {
        composeRule.onNode(hasText(string(R.string.tab_browse)) and isSelectable()).performClick()
        composeRule.onNode(hasText(string(R.string.browse_tab_extensions)) and isSelectable()).performClick()
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun logServed(method: String, served: List<FixtureStore.Served>) {
        Log.i(LOG_TAG, "$method: the fixture server logged ${served.size} requests: ${served.map { "${it.status} ${it.path}" }}")
    }

    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: $reason")
    }

    private fun string(resourceId: Int, vararg args: Any): String = context.getString(resourceId, *args)

    private companion object {
        const val LOG_TAG = "ExtensionStoresDeviceTest"

        /** Empty below API 33, where `POST_NOTIFICATIONS` is not a runtime permission. */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        /** A cold start opens the graph and the database before the library flow can emit. */
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        /** A screen's first load, an index fetch from the host included. */
        const val SCREEN_TIMEOUT_MILLIS = 15_000L
    }
}
