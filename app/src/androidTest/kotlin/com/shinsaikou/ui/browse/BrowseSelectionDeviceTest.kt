package com.shinsaikou.ui.browse

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-select in a source's listing (R148) and the first library update after a bulk add from it
 * (R147), on the screen, over the fixture extension installed from [FixtureStore]'s store (wave 2
 * unit browse-selection-device-test). `SourceBrowseSelectionTest` drives the view model on the JVM
 * and `FirstListBacklogTest` the rule over a real database; what neither covers is the long press,
 * the selection bar and its snackbar, and the path the owner saw fail: a bulk add from the listing
 * followed by a library update on a device.
 *
 * Every method needs `tools/bench/browse_fixture_server.py` on the host and `saikou.browse.fixture=true`
 * ([FixtureStore] says how the server is started); without the argument it logs a `Skipped:` line and
 * returns, and so it does when a store other than the fixture's is configured, since Browse fetches
 * every configured index. Neither return fails under `saikou.strict`: gate.sh step 5c runs this class
 * strict with no server and expects the line, and step 5i runs it with the server and fails on any
 * `Skipped:` line, as for `FixtureSourcesDeviceTest`.
 *
 * Each method adds the store, installs the extension from it, opens one fixture source's listing in
 * the list layout (one row per entry, so the 50 entries of page 1 never bring the listing near its
 * end and nothing asks for page 2 unless a test scrolls there), and [restore] puts back whatever it
 * changed: the preferences it set, the entries it added to the library and every entry the listings
 * stored, the category it made, the extension and the store.
 */
@RunWith(AndroidJUnit4::class)
class BrowseSelectionDeviceTest {

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

    /** What [restore] puts back; each is set before the change it undoes. */
    private var fixture: FixtureStore? = null
    private var sourceIds = emptyList<Long>()
    private var entriesBefore = emptySet<Long>()
    private var favoritesBefore = emptySet<Long>()
    private var categoryId: Long? = null
    private val kept = mutableListOf<KeptPreference<*>>()

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
     * Leaves the device as the method found it, whatever happened: the extension and the store
     * first, then the entries (those the method put in the library taken out of it, so that every
     * entry the listings stored goes as Browse's own cleanup removes them), the category, and last
     * the preferences, in the reverse of the order they were set. Each step runs whatever the ones
     * before it did; the first failure is thrown after the last. A method that skipped changed
     * nothing, and nothing runs.
     */
    @After
    fun restore() {
        val teardown = Teardown()
        fixture?.let { store -> teardown.step { runBlocking(Dispatchers.IO) { store.restore() } } }
        fixture = null
        val ids = sourceIds
        if (ids.isNotEmpty()) {
            teardown.step {
                runBlocking(Dispatchers.IO) {
                    val added = graph.mangaRepository.getFavorites().filter { it.source in ids }.map { it.id } - favoritesBefore
                    if (added.isNotEmpty()) {
                        graph.mangaRepository.updateAll(added.map { MangaUpdate(id = it, favorite = false) })
                    }
                }
            }
            teardown.step {
                runBlocking(Dispatchers.IO) {
                    val stored = graph.mangaRepository.getLiveNonLibraryMangaIds(ids) - entriesBefore
                    graph.mangaRepository.deleteNonLibraryMangaByIds(stored, keepReadManga = false)
                }
            }
        }
        sourceIds = emptyList()
        categoryId?.let { id -> teardown.step { runBlocking(Dispatchers.IO) { graph.categoryRepository.delete(id) } } }
        categoryId = null
        kept.asReversed().forEach { preference -> teardown.step { preference.restore() } }
        kept.clear()
        teardown.finish()
    }

    /**
     * R148 items 2 and 3: a long press on the first entry and one on the fourth select the four
     * between them, and the bar says so; Add to library puts exactly those four in the library, in
     * one write with one date added and each with the chapter defaults (upstream's add from browse),
     * says how many in the snackbar, and leaves selection mode. The default category is the
     * built-in one, so no picker is asked; the default chapter display is set to "by number", so the
     * defaults differ from the flags a listed entry is stored with.
     */
    @Test
    fun aSecondLongPressSelectsTheRangeAndAddToLibraryAddsThoseFourWithOneDateAddedAndTheChapterDefaults() {
        withAFixtureListing("aSecondLongPressSelectsTheRangeAndAddToLibraryAddsThoseFourWithOneDateAddedAndTheChapterDefaults", 1) { source ->
            val ids = entryIds(source, 1..RANGE_END)
            val after = entryIds(source, listOf(RANGE_END + 1)).single()
            assertTrue("an entry of the range is in the library before the add", favorites(source).intersect(ids.toSet()).isEmpty())

            selectTheRange(source)
            addToLibrary()
            awaitText(context.resources.getQuantityString(R.plurals.source_added_to_library_count, RANGE_END, RANGE_END))
            awaitNoText(string(R.string.selection_count_format, RANGE_END))
            awaitText(source.name)

            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { favorites(source).containsAll(ids) }
            assertEquals("the entries the add put in the library", ids.toSet(), favorites(source) - favoritesBefore)
            assertFalse("the entry after the range was added", after in favorites(source))
            val added = runBlocking(Dispatchers.IO) { ids.map { graph.mangaRepository.getMangaById(it) } }
            val dates = added.map { it.dateAdded }.toSet()
            assertEquals("the four's dates added: $dates", 1, dates.size)
            assertTrue("the shared date added is not set: $dates", dates.single() > 0L)
            val defaults = chapterDefaults()
            added.forEach { manga ->
                assertEquals(
                    "${manga.title}'s chapter flags under the defaults mask",
                    defaults,
                    manga.chapterFlags and Manga.CHAPTER_DEFAULTS_MASK,
                )
            }
        }
    }

    /**
     * R148 item 2: Select all takes the entries loaded so far, the 50 of page 1, and asks for no
     * other page; Invert selection then selects none, which leaves selection mode; and with an entry
     * selected, Back clears the selection and keeps the listing on screen.
     */
    @Test
    fun selectAllTakesThePageLoadedInvertSelectsNoneAndBackClearsTheSelectionBeforeTheListing() {
        withAFixtureListing("selectAllTakesThePageLoadedInvertSelectsNoneAndBackClearsTheSelectionBeforeTheListing", 2) { source ->
            val store = fixture!!
            longPress(entryTitle(source.number, 1))
            awaitText(string(R.string.selection_count_format, 1))

            barAction(R.string.action_select_all).performClick()
            awaitText(string(R.string.selection_count_format, PAGE_SIZE))
            assertOnlyPageOneWasAsked(store)

            barAction(R.string.action_invert_selection).performClick()
            awaitNoText(string(R.string.selection_count_format, PAGE_SIZE))
            awaitText(source.name)
            assertNoText(string(R.string.selection_count_format, 0))
            assertEquals("the selection bar is still up", 0, barActions(R.string.action_select_all))

            longPress(entryTitle(source.number, 3))
            awaitText(string(R.string.selection_count_format, 1))
            composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            awaitNoText(string(R.string.selection_count_format, 1))
            awaitText(source.name)
            composeRule.onNode(hasText(string(R.string.source_mode_popular)) and isSelectable())
                .assertExists("Back left the listing")
            composeRule.onNode(entryRow(entryTitle(source.number, 1))).assertExists("Back left the listing")
            assertOnlyPageOneWasAsked(store)
        }
    }

    /**
     * R147 with R148 item 4: four entries added from the listing (into a category of this test's,
     * the default one, so the add asks nothing and the update reaches nothing else), then a manual
     * library update of that category, as `GateRowsTest`'s cancel row runs it. The fixture lists 50
     * chapters per entry, uploaded from 2026-01-01 a day apart, all before the add: each entry's whole
     * list is stored with its fetch date at the shared date added, the update returns no new chapter
     * and counts none, and the Updates query lists no chapter of the four.
     */
    @Test
    fun aBulkAddThenALibraryUpdateFilesTheBackCatalogueUnderTheDateAddedAndUpdatesListsNoneOfIt() {
        withAFixtureListing("aBulkAddThenALibraryUpdateFilesTheBackCatalogueUnderTheDateAddedAndUpdatesListsNoneOfIt", 3) { source ->
            val store = fixture!!
            val category = createTheCategory()
            graph.libraryPreferences.defaultCategory.set(category.toInt())
            val ids = entryIds(source, 1..RANGE_END)

            selectTheRange(source)
            addToLibrary()
            awaitText(context.resources.getQuantityString(R.plurals.source_added_to_library_count, RANGE_END, RANGE_END))
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { favorites(source).containsAll(ids) }
            val added = runBlocking(Dispatchers.IO) {
                ids.associateWith { id ->
                    assertEquals(
                        "entry $id's categories after the add",
                        listOf(category),
                        graph.categoryRepository.getCategoriesByMangaId(id).map { it.id },
                    )
                    // The rule's condition: an entry with any chapter row is not on its first list.
                    assertTrue("entry $id has chapters before its first update", graph.chapterRepository.getChapterByMangaId(id).isEmpty())
                    assertTrue("entry $id has deleted chapters", graph.chapterRepository.getDeletedChaptersByMangaId(id).isEmpty())
                    graph.mangaRepository.getMangaById(id)
                }
            }
            val dates = added.values.map { it.dateAdded }.toSet()
            assertEquals("the four's dates added: $dates", 1, dates.size)
            val dateAdded = dates.single()
            val newUpdatesBefore = graph.libraryPreferences.newUpdatesCount.get()

            // Each progress report names the entry the update starts and the size of its queue
            // (the pre-run queue callback went with unit update-site-notice).
            val started = Collections.synchronizedList(mutableListOf<Long>())
            val queueSize = AtomicInteger(-1)
            val outcome = runBlocking(Dispatchers.IO) {
                graph.libraryUpdate.run(
                    categoryId = category,
                    automatic = false,
                    refreshMetadata = false,
                ) { progress ->
                    queueSize.set(progress.total)
                    progress.current?.let { started += it.id }
                }
            }

            assertEquals("the update's queue size", ids.size, queueSize.get())
            assertEquals("the update's queue", ids.toSet(), started.toSet())
            assertFalse("the update was cancelled", outcome.cancelled)
            assertTrue(
                "the update failed: ${outcome.failures.map { "${it.manga.title}: ${it.error}" }}",
                outcome.failures.isEmpty(),
            )
            assertEquals("entries the update returned new chapters for", emptySet<Long>(), outcome.updated.keys.intersect(ids.toSet()))
            assertEquals("the new chapters count", newUpdatesBefore, graph.libraryPreferences.newUpdatesCount.get())
            store.awaitServed(*(1..RANGE_END).map { "/manga/$it/chapters" }.toTypedArray())

            runBlocking(Dispatchers.IO) {
                ids.forEach { id ->
                    val chapters = graph.chapterRepository.getChapterByMangaId(id)
                    assertEquals("entry $id's chapters after the update", CHAPTERS_PER_LIST, chapters.size)
                    chapters.forEach { chapter ->
                        assertTrue("${chapter.url} is dated after the add: ${chapter.dateUpload}", chapter.dateUpload <= dateAdded)
                        assertEquals("${chapter.url}'s fetch date", dateAdded, chapter.dateFetch)
                    }
                }
                val updates = graph.updatesRepository.subscribeAll(
                    after = 0L,
                    limit = UPDATES_LIMIT,
                    unread = null,
                    started = null,
                    bookmarked = null,
                    hideExcludedScanlators = false,
                    includedCategories = listOf(category),
                    excludedCategories = emptyList(),
                ).first()
                assertEquals(
                    "the Updates rows of the four",
                    emptyList<String>(),
                    updates.filter { it.mangaId in ids }.map { it.chapterUrl },
                )
            }
        }
    }

    /**
     * Skips as the class comment says, or keeps and sets the preferences the listing reads, adds the
     * store, installs the extension, opens fixture source [number]'s listing in the list layout and
     * waits for its first page, runs [body], and checks that only the fixture was asked. [restore]
     * undoes it all.
     */
    private fun withAFixtureListing(method: String, number: Int, body: (FixtureSource) -> Unit) {
        if (!FixtureStore.isServed()) return skip("$method: no saikou.browse.fixture argument")
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            return skip("$method: an extension store other than the browse fixture store is configured")
        }
        val preferences = graph.sourcePreferences
        val library = graph.libraryPreferences
        kept += listOf(
            KeptPreference(preferences.enabledLanguages),
            KeptPreference(preferences.disabledSources),
            KeptPreference(preferences.pinnedSources),
            KeptPreference(preferences.lastUsedSource),
            KeptPreference(preferences.sourceDisplayMode),
            KeptPreference(preferences.hideInLibraryItems),
            KeptPreference(library.defaultCategory),
            KeptPreference(library.displayChapterByNameOrNumber),
            KeptPreference(library.newUpdatesCount),
        )
        preferences.enabledLanguages.set(setOf(LANGUAGE_ALL, ENGLISH))
        preferences.disabledSources.set(emptySet())
        preferences.pinnedSources.set(emptySet())
        preferences.lastUsedSource.set(-1L)
        preferences.sourceDisplayMode.set(LibraryDisplayMode.List)
        preferences.hideInLibraryItems.set(false)
        // The built-in category answers an add with no picker (R148 item 3); the third method sets
        // its own category instead.
        library.defaultCategory.set(Category.UNCATEGORIZED_ID.toInt())
        library.displayChapterByNameOrNumber.set(Manga.CHAPTER_DISPLAY_NUMBER)

        val store = FixtureStore(graph)
        fixture = store
        val installed = runBlocking(Dispatchers.IO) {
            store.add()
            store.installTheExtension()
        }
        val sources = installed.sources.sortedBy { it.name }
        assertEquals("the fixture extension's sources", FIXTURE_SOURCES, sources.size)
        sourceIds = sources.map { it.id }
        runBlocking(Dispatchers.IO) {
            entriesBefore = graph.mangaRepository.getLiveNonLibraryMangaIds(sourceIds).toSet()
            favoritesBefore = graph.mangaRepository.getFavorites().filter { it.source in sourceIds }.map { it.id }.toSet()
        }
        val source = sources[number - 1].let { FixtureSource(number, it.id, it.name) }

        openSourcesPage()
        val row = hasText(source.name) and hasClickAction()
        scrollTo(row)
        composeRule.onNode(row).performClick()
        awaitText(entryTitle(number, 1))
        store.awaitServed(FIRST_PAGE)
        body(source)

        val served = store.served()
        Log.i(LOG_TAG, "$method: the fixture server logged ${served.size} requests: ${served.map { "${it.status} ${it.path}" }}")
        val asked = listOfNotNull(FixtureStore.INDEX_PATH, FixtureStore.APK_PATH.takeIf { store.installedTheExtension })
        runBlocking(Dispatchers.IO) { store.assertOnlyTheFixtureWasAsked(*asked.toTypedArray()) }
    }

    /** Entries 1 and [RANGE_END] long pressed, in that order: the bar counts the range between. */
    private fun selectTheRange(source: FixtureSource) {
        longPress(entryTitle(source.number, 1))
        awaitText(string(R.string.selection_count_format, 1))
        longPress(entryTitle(source.number, RANGE_END))
        awaitText(string(R.string.selection_count_format, RANGE_END))
    }

    private fun addToLibrary() {
        barAction(R.string.action_add_to_library).performClick()
    }

    /** A category of this method's own, which [restore] deletes. */
    private fun createTheCategory(): Long = runBlocking(Dispatchers.IO) {
        val name = "$CATEGORY_PREFIX${System.nanoTime()}"
        assertEquals(CreateCategoryWithName.Result.Success, graph.createCategoryWithName.await(name))
        graph.categoryRepository.getAll().single { it.name == name }.id
    }.also { categoryId = it }

    /** The stored ids of [source]'s entries [entries], which its listing stored as it loaded them. */
    private fun entryIds(source: FixtureSource, entries: Iterable<Int>): List<Long> = runBlocking(Dispatchers.IO) {
        entries.map { entry ->
            val manga = graph.mangaRepository.getMangaByUrlAndSourceId("/manga/$entry", source.id)
            assertNotNull("${entryTitle(source.number, entry)} was not stored by the listing", manga)
            manga!!.id
        }
    }

    private fun favorites(source: FixtureSource): Set<Long> = runBlocking(Dispatchers.IO) {
        graph.mangaRepository.getFavorites().filter { it.source == source.id }.mapTo(mutableSetOf()) { it.id }
    }

    /**
     * The flag word `SetMangaDefaultChapterFlags` builds from the six chapter defaults, over the
     * bits of an entry with none set, as `SetMangaChapterFlags.awaitSetAllFlags` builds it.
     */
    private fun chapterDefaults(): Long = with(graph.libraryPreferences) {
        listOf(
            filterChapterByRead.get() to Manga.CHAPTER_UNREAD_MASK,
            filterChapterByDownloaded.get() to Manga.CHAPTER_DOWNLOADED_MASK,
            filterChapterByBookmarked.get() to Manga.CHAPTER_BOOKMARKED_MASK,
            sortChapterBySourceOrNumber.get() to Manga.CHAPTER_SORTING_MASK,
            sortChapterByAscendingOrDescending.get() to Manga.CHAPTER_SORT_DIR_MASK,
            displayChapterByNameOrNumber.get() to Manga.CHAPTER_DISPLAY_MASK,
        ).fold(0L) { flags, (value, mask) -> flags and mask.inv() or (value and mask) }
    }

    /** The listing's requests since the store was added are page 1's alone. */
    private fun assertOnlyPageOneWasAsked(store: FixtureStore) {
        composeRule.waitForIdle()
        val pages = store.served().map { it.path }.filter { it.startsWith(LISTING_PATH) }.distinct()
        assertEquals("the listing pages asked for", listOf(FIRST_PAGE), pages)
    }

    private fun longPress(title: String) {
        val row = entryRow(title)
        scrollTo(row)
        composeRule.onNode(row).performTouchInput { longClick() }
    }

    private fun entryRow(title: String): SemanticsMatcher = hasText(title) and hasClickAction()

    /** One of the selection bar's buttons, by the words it carries for a screen reader. */
    private fun barAction(label: Int) = composeRule.onNode(hasContentDescription(string(label)) and hasClickAction())

    private fun barActions(label: Int): Int =
        composeRule.onAllNodes(hasContentDescription(string(label)) and hasClickAction()).fetchSemanticsNodes().size

    private fun openSourcesPage() {
        composeRule.onNode(hasText(string(R.string.tab_browse)) and isSelectable()).performClick()
        composeRule.onNode(hasText(string(R.string.browse_tab_sources)) and isSelectable()).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasContentDescription(string(R.string.action_filter)) and hasClickAction())
                .fetchSemanticsNodes()
                .size == 1
        }
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
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

    private fun assertNoText(text: String) {
        composeRule.waitForIdle()
        assertEquals("\"$text\" on screen", 0, composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size)
    }

    /** A fixture entry's title, as `tools/bench/update_fixture.py` writes it. */
    private fun entryTitle(source: Int, entry: Int): String = "Fixture %02d-%03d".format(source, entry)

    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: $reason")
    }

    private fun string(resourceId: Int, vararg args: Any): String = context.getString(resourceId, *args)

    /** What the test needs of a fixture source: its number (01 to 20, its titles' prefix), id and name. */
    data class FixtureSource(val number: Int, val id: Long, val name: String)

    /** A preference's value as found, set or not, and putting it back. */
    private class KeptPreference<T>(private val preference: Preference<T>) {
        private val wasSet = preference.isSet()
        private val value = preference.get()

        fun restore() {
            if (wasSet) preference.set(value) else preference.delete()
        }
    }

    private companion object {
        const val LOG_TAG = "BrowseSelectionDeviceTest"

        const val LANGUAGE_ALL = "all"
        const val ENGLISH = "en"
        const val FIXTURE_SOURCES = 20

        /** `tools/bench/update_fixture.py`: the listing's page size and each entry's chapter list. */
        const val PAGE_SIZE = 50
        const val CHAPTERS_PER_LIST = 50

        /** The listing's request as the fixture server logs it, and its first page's. */
        const val LISTING_PATH = "/popular"
        const val FIRST_PAGE = "/popular?page=1"

        /** The second long press's entry: the range is entries 1 to this one. */
        const val RANGE_END = 4

        const val CATEGORY_PREFIX = "Browse selection "

        /** Above the 50 chapters of each of the four, so the query's limit cannot hide one of theirs. */
        const val UPDATES_LIMIT = 1_000L

        /** Empty below API 33, where `POST_NOTIFICATIONS` is not a runtime permission. */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        /** A cold start opens the graph and the database before the library flow can emit. */
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        /** A screen's first load, a listing from the host (400 to 800 ms a request) included. */
        const val SCREEN_TIMEOUT_MILLIS = 20_000L
    }
}
