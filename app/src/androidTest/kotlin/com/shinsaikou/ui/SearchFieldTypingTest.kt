package com.shinsaikou.ui

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeTimeoutException
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.browse.FixtureStore
import com.shinsaikou.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.domain.manga.model.Manga

/**
 * The search fields of the app's own screens, typed into one key at a time (see
 * `SearchFieldTyping.kt`), on the real [MainActivity] and graph. The Extensions page has its own
 * class, `ExtensionsSearchFieldTest`, because a test device has no extension to put a row on it.
 *
 * The Sources page had the Extensions page's fault (its query came back through a flow on the IO
 * dispatcher). History already held the query in the screen's own state; a source's listing and
 * global search update the view model's state on the main thread inside the key's own call. Each is
 * typed into here so that none of them can gain a late copy unnoticed. The library's field holds the
 * query in its own saved state, the pattern the fix copied, and is typed into as well, over entries
 * the test seeds and removes, so that its grid can be checked against the text too. Nothing else
 * here depends on what the device holds: only the field's text and cursor are read.
 *
 * Browse builds the Extensions page's view model, which fetches every configured extension index
 * while nothing is available, so the three methods that open Browse return with a `Skipped:` line
 * before it while a store other than the browse fixture's is configured (phase 5 unit TG2).
 */
@RunWith(AndroidJUnit4::class)
class SearchFieldTypingTest {

    /** Outermost, as in `MainActivitySmokeTest`: the permission dialog would cover the activity. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /** The battery exemption dialog and a crash banner answered first, as in `MainActivitySmokeTest`. */
    @get:Rule(order = 1)
    val firstLaunchAnswered = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val graph = (context.applicationContext as SaikouApp).graph
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

    /** The ids of the library entries a test seeded, removed after it. */
    private val seeded = mutableListOf<Long>()

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
    fun theSourcesPageKeepsEveryKeyInOrder() {
        if (aForeignStoreIsConfigured("theSourcesPageKeepsEveryKeyInOrder")) return
        openSourcesPage()
        composeRule.typeThenDelete("Sources")
    }

    /** The Local source is the one source every device lists. */
    @Test
    fun aSourceListingKeepsEveryKeyInOrder() {
        if (aForeignStoreIsConfigured("aSourceListingKeepsEveryKeyInOrder")) return
        openSourcesPage()
        composeRule.onAllNodes(hasText(string(R.string.local_source)) and hasClickAction()).onFirst().performClick()
        awaitOneField(R.string.source_search_hint)
        composeRule.typeThenDelete("Local source listing")
    }

    @Test
    fun globalSearchKeepsEveryKeyInOrder() {
        if (aForeignStoreIsConfigured("globalSearchKeepsEveryKeyInOrder")) return
        tabItem(R.string.tab_browse).performClick()
        composeRule.onNode(hasContentDescription(string(R.string.action_global_search)) and hasClickAction())
            .performClick()
        awaitOneField(R.string.global_search_hint)
        composeRule.typeThenDelete("Global search")
    }

    @Test
    fun theHistorySearchKeepsEveryKeyInOrder() {
        tabItem(R.string.tab_history).performClick()
        composeRule.onNode(hasContentDescription(string(R.string.action_search)) and hasClickAction()).performClick()
        awaitOneField(R.string.history_search_hint)
        composeRule.typeThenDelete("History")
    }

    /**
     * The library's field, over three entries seeded on the Local source: one whose title holds
     * both typed words, one holding only "fixture", and one holding neither. A query's words must
     * all match, so after each check of the field the grid shows exactly the entries its text
     * matches, which is what shows the view model filtered by the text the field holds.
     */
    @Test
    fun theLibrarySearchKeepsEveryKeyInOrder() {
        val run = System.nanoTime()
        val bothWords = "$TYPED_QUERY $run"
        val firstWord = "${TYPED_QUERY.dropLast(DELETED_KEYS)} $run"
        val neither = "$UNMATCHED_PREFIX$run"
        val titles = listOf(bothWords, firstWord, neither)
        seedLibrary(titles)
        awaitGridShowing("Library before the search", titles, of = titles)

        composeRule.onNode(hasContentDescription(string(R.string.action_search)) and hasClickAction()).performClick()
        awaitOneField(R.string.library_search_hint)
        composeRule.typeThenDelete("Library") { text ->
            val shown = if (text == TYPED_QUERY) listOf(bothWords) else listOf(bothWords, firstWord)
            awaitGridShowing("Library searched for \"$text\"", shown, of = titles)
        }
    }

    /**
     * Soft deleted, the only delete the repository offers per entry, as `TwoPaneLayoutTest` removes
     * its fixture: the rows stay in the database and out of the library.
     */
    @After
    fun removeTheSeededEntries() {
        runBlocking { seeded.forEach { graph.mangaRepository.softDelete(it, System.currentTimeMillis()) } }
    }

    /** Library entries on the Local source, initialized so that nothing fetches for them. */
    private fun seedLibrary(titles: List<String>) {
        runBlocking {
            titles.forEach { title ->
                seeded += graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = LOCAL_SOURCE_ID,
                            url = title,
                            title = title,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single().id
            }
        }
    }

    /**
     * Waits until, of the covers titled [of], the grid shows exactly [shown], then checks it, so a
     * grid that never gets there fails on the titles it does show. A cover is its title with a click
     * action, the search field (which holds the typed text and has one too) left out, as in
     * `TwoPaneLayoutTest.fixtureCover`.
     */
    private fun awaitGridShowing(where: String, shown: List<String>, of: List<String>) {
        fun showing() = of.filter { title ->
            composeRule.onAllNodes(hasText(title) and hasClickAction() and hasSetTextAction().not())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        try {
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { showing() == shown }
        } catch (e: ComposeTimeoutException) {
            // The assertion below names what the grid shows instead.
        }
        assertEquals("$where: the seeded covers shown", shown, showing())
    }

    /** The guard `SourcesFilterDeviceTest` has, in its words, so step 5c's one pattern covers both. */
    private fun aForeignStoreIsConfigured(method: String): Boolean {
        val foreign = runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }
        if (foreign.isEmpty()) return false
        Log.i(
            LOG_TAG,
            "Skipped: $method: an extension store other than the browse fixture store is configured, " +
                "Browse was not opened so that nothing is fetched",
        )
        return true
    }

    private fun openSourcesPage() {
        tabItem(R.string.tab_browse).performClick()
        composeRule.onNode(hasText(string(R.string.browse_tab_sources)) and isSelectable()).performClick()
        awaitOneField(R.string.action_search)
    }

    /**
     * The screen is up once exactly one editable field shows and its label is on screen; during a
     * transition the screen being left can still hold one.
     */
    private fun awaitOneField(labelRes: Int) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithText(string(labelRes), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    /** The bar item, which of the nodes with its label is the only selectable one. */
    private fun tabItem(labelRes: Int) = composeRule.onNode(hasText(string(labelRes)) and isSelectable())

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        const val LOG_TAG = "SearchFieldTypingTest"

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

        /** The local source's id, which is the one source every install has. */
        const val LOCAL_SOURCE_ID = 0L

        /** The seeded title that matches neither typed word; it sorts ahead of other fixtures. */
        const val UNMATCHED_PREFIX = "a typing check "
    }
}
