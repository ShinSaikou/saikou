package com.shinsaikou.ui.browse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.source.SourceIds
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import com.shinsaikou.ui.manga.MANGA_OVERFLOW_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * Browse with the fixture extension installed from [FixtureStore]'s store (phase 5 unit TG2): upstream's
 * source-row affordances on the Sources page (R130 carry-over 2), the "Add to library?" prompt after
 * a download from the selection bar and from a swipe (the two paths R130 left on JVM tests), and a
 * later page's failure in a snackbar (R126 carry-over 3).
 *
 * Every method needs `tools/bench/browse_fixture_server.py` on the host and `saikou.browse.fixture=true`
 * ([FixtureStore] says how the server is started); without the argument it logs a `Skipped:` line and
 * returns, and so it does when a store other than the fixture's is configured, since Browse fetches
 * every configured index. Each method adds the store, installs the extension from it (twenty English
 * sources, "Update Fixture 01" to "20", whose listings, entries and pages the server answers), and
 * whatever happens puts back the preferences it set (enabled languages, disabled and pinned sources,
 * the last used source, the swipe action and the default category), removes the entries and
 * downloads it made, uninstalls the extension and removes the store.
 *
 * The fixture's sources have no latest listing (R66's frozen extension says `supportsLatest = false`),
 * so the Latest button is driven on the Local source, the one installed source that has one, and the
 * fixture's rows are checked to carry none.
 */
@RunWith(AndroidJUnit4::class)
class FixtureSourcesDeviceTest {

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
    private lateinit var launchIntent: Intent

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
        launchIntent = composeRule.activity.intent
    }

    /**
     * The prompt's two methods send the activity an intent of the app's own, which `onNewIntent`
     * makes the activity's; handed back before the rule closes the scenario, as `TwoPaneLayoutTest`
     * does and for its reason (the scenario would ignore the activity from then on).
     */
    @After
    fun handTheLaunchIntentBack() {
        if (::launchIntent.isInitialized) {
            composeRule.runOnUiThread { composeRule.activity.intent = launchIntent }
        }
    }

    /**
     * R130 items 1 and 2: a long press opens the source's options, titled with its name and language,
     * offering Pin and Disable. Pin puts the source under "Pinned" above "Other", and the options then
     * offer Unpin, which takes it back with the labels; Disable takes it off the page, and the source
     * filter's checkbox brings it back.
     */
    @Test
    fun aLongPressOffersPinAndDisablePinMovesTheSourceUnderPinnedAndTheFilterBringsADisabledOneBack() {
        withTheFixtureExtension("aLongPressOffersPinAndDisablePinMovesTheSourceUnderPinnedAndTheFilterBringsADisabledOneBack") { sources ->
            val source = sources[0]
            val row = hasText(source.name) and hasClickAction()
            val preferences = graph.sourcePreferences
            openSourcesPage()
            scrollTo(row)
            assertTrue("a label is drawn while nothing is pinned or last used", labelTops().isEmpty())

            // Pin.
            composeRule.onNode(row).performTouchInput { longClick(percentOffset(0.3f, 0.5f)) }
            awaitText("${source.name} (EN)")
            awaitText(string(R.string.action_disable))
            composeRule.onNode(hasText(string(R.string.action_pin)) and hasClickAction()).performClick()
            awaitNoText("${source.name} (EN)")
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                preferences.pinnedSources.get() == setOf(source.id.toString())
            }
            awaitText(string(R.string.pinned_sources))
            scrollTo(row)
            val tops = labelTops()
            val rowTop = top(row)
            val pinned = tops.getValue(string(R.string.pinned_sources))
            val other = tops.getValue(string(R.string.other_source))
            assertTrue("the pinned source is not between Pinned ($pinned) and Other ($other): $rowTop", rowTop in pinned..other)

            // Unpin, from the same options, which now say so.
            composeRule.onNode(row).performTouchInput { longClick(percentOffset(0.3f, 0.5f)) }
            awaitText("${source.name} (EN)")
            composeRule.onNode(hasText(string(R.string.action_unpin)) and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { preferences.pinnedSources.get().isEmpty() }
            awaitNoText(string(R.string.pinned_sources))

            // Disable: off the page, then back on from the source filter.
            composeRule.onNode(row).performTouchInput { longClick(percentOffset(0.3f, 0.5f)) }
            awaitText("${source.name} (EN)")
            composeRule.onNode(hasText(string(R.string.action_disable)) and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                preferences.disabledSources.get() == setOf(source.id.toString())
            }
            awaitNoText(source.name)
            awaitText(sources[1].name)

            composeRule.onNode(hasContentDescription(string(R.string.action_filter)) and hasClickAction()).performClick()
            val checkbox = hasText(source.name) and isToggleable()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 0 }
            scrollTo(checkbox)
            composeRule.onNode(checkbox).assertIsOff().performClick()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { preferences.disabledSources.get().isEmpty() }
            composeRule.onNode(checkbox).assertIsOn()
            goBack()
            scrollTo(row)
            awaitText(source.name)
        }
    }

    /**
     * R130 item 3: a source with a latest listing carries Latest on its row, opening that listing;
     * one without carries none. The Local source has one, the fixture's sources do not.
     */
    @Test
    fun theLatestButtonIsOnTheLocalSourcesRowAloneAndOpensItsLatestListing() {
        withTheFixtureExtension("theLatestButtonIsOnTheLocalSourcesRowAloneAndOpensItsLatestListing") { sources ->
            val latest = string(R.string.source_mode_latest)
            val local = string(R.string.local_source)
            openSourcesPage()
            awaitText(sources[0].name)
            val localLatest = hasText(latest) and hasClickAction() and hasParent(hasText(local))
            scrollTo(hasText(local) and hasClickAction())
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(localLatest).fetchSemanticsNodes().size == 1
            }
            sources.take(VISIBLE_SOURCES).forEach { source ->
                scrollTo(hasText(source.name) and hasClickAction())
                assertEquals(
                    "Latest buttons on ${source.name}'s row",
                    0,
                    composeRule.onAllNodes(hasText(latest) and hasParent(hasText(source.name))).fetchSemanticsNodes().size,
                )
            }

            scrollTo(hasText(local) and hasClickAction())
            composeRule.onNode(localLatest).performClick()
            val latestChip = hasText(latest) and isSelectable()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(latestChip).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(latestChip).assertIsSelected()
            composeRule.onNode(hasText(string(R.string.source_mode_popular)) and isSelectable()).assertIsNotSelected()
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                graph.sourcePreferences.lastUsedSource.get() == SourceIds.LOCAL
            }
            goBack()
        }
    }

    /**
     * R130 items 1 and 4: a source whose listing has been opened heads the Sources page under "Last
     * used", listed a second time in its own place under "Other".
     */
    @Test
    fun aSourceOpenedHeadsTheSourcesPageUnderLastUsed() {
        withTheFixtureExtension("aSourceOpenedHeadsTheSourcesPageUnderLastUsed") { sources ->
            val source = sources[1]
            val row = hasText(source.name) and hasClickAction()
            openSourcesPage()
            scrollTo(row)
            assertEquals("the last used source before any listing opened", -1L, graph.sourcePreferences.lastUsedSource.get())
            composeRule.onNode(row).performClick()
            awaitText(entryTitle(2, 1))
            goBack()

            awaitText(string(R.string.last_used_source))
            assertEquals("the last used source", source.id, graph.sourcePreferences.lastUsedSource.get())
            composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(string(R.string.last_used_source)))
            val tops = labelTops()
            val lastUsed = tops.getValue(string(R.string.last_used_source))
            val other = tops.getValue(string(R.string.other_source))
            assertEquals("labels above Last used", 0, tops.values.count { it < lastUsed })
            val rows = composeRule.onAllNodes(row).fetchSemanticsNodes().map { it.boundsInRoot.top }
            assertTrue("${source.name} is not listed under Last used: rows at $rows, labels $tops", rows.any { it in lastUsed..other })
        }
    }

    /**
     * R130 item 5 through the selection bar: a download of an entry not in the library asks "Add to
     * library?" with Add and a dismiss action; a second download while it is up asks nothing more;
     * Add puts the entry in the library. The chapters download from the fixture server.
     */
    @Test
    fun aSelectionBarDownloadOfAnEntryNotInTheLibraryAsksOnceAndAddAddsIt() {
        withTheFixtureExtension("aSelectionBarDownloadOfAnEntryNotInTheLibraryAsksOnceAndAddAddsIt") { sources ->
            withAnEntryNotInTheLibrary(sources[3], entry = SELECTION_ENTRY) { manga, chapters ->
                download(selectingChapter = 1)
                awaitPrompt()
                download(selectingChapter = 2)
                assertOnePrompt()

                composeRule.onNode(hasText(string(R.string.action_add)) and hasClickAction()).performClick()
                composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                    runBlocking(Dispatchers.IO) { graph.mangaRepository.getMangaById(manga.id) }.favorite
                }
                awaitText(string(R.string.manga_added_to_library))
                assertNoSecondPrompt()
                awaitDownloads(chapters.take(2))
            }
        }
    }

    /**
     * R130 item 5 through a swipe set to Download (start now): the same prompt once; a second swipe
     * while it is up asks nothing more, and its dismiss leaves the entry out of the library.
     */
    @Test
    fun aSwipeDownloadOfAnEntryNotInTheLibraryAsksOnceAndTheDismissKeepsItOut() {
        withTheFixtureExtension("aSwipeDownloadOfAnEntryNotInTheLibraryAsksOnceAndTheDismissKeepsItOut") { sources ->
            val swipe = KeptPreference(graph.libraryPreferences.swipeToStartAction)
            val teardown = Teardown()
            teardown.step {
                graph.libraryPreferences.swipeToStartAction.set(LibraryPreferences.ChapterSwipeAction.Download)
                withAnEntryNotInTheLibrary(sources[4], entry = SWIPE_ENTRY) { manga, chapters ->
                    swipeToDownload(chapter = 1)
                    awaitPrompt()
                    swipeToDownload(chapter = 2)
                    assertOnePrompt()

                    composeRule.onNode(hasContentDescription(snackbarDismiss()) and hasClickAction()).performClick()
                    awaitNoText(string(R.string.snack_add_to_library))
                    assertNoSecondPrompt()
                    assertFalse(
                        "the dismiss put the entry in the library",
                        runBlocking(Dispatchers.IO) { graph.mangaRepository.getMangaById(manga.id) }.favorite,
                    )
                    awaitDownloads(chapters.take(2))
                }
            }
            teardown.step { swipe.restore() }
            teardown.finish()
        }
    }

    /**
     * R126 item 4 and its carry-over 3: a listing whose second page fails keeps its first page and
     * says the failure in a snackbar with Retry; once the server answers again, Retry loads page 2.
     */
    @Test
    fun aLaterPagesFailureKeepsTheFirstPageAndItsSnackbarsRetryLoadsIt() {
        withTheFixtureExtension("aLaterPagesFailureKeepsTheFirstPageAndItsSnackbarsRetryLoadsIt") { sources ->
            val fixture = currentFixture
            val source = sources[2]
            val row = hasText(source.name) and hasClickAction()
            fixture.failListingPagesFrom(2)
            val teardown = Teardown()
            teardown.step {
                openSourcesPage()
                scrollTo(row)
                composeRule.onNode(row).performClick()
                awaitText(entryTitle(3, 1))

                // The end of page 1 asks for page 2, which fails: the snackbar, and page 1 kept.
                scrollTo(hasText(entryTitle(3, PAGE_SIZE)))
                val message = string(R.string.error_http_format, FAILED_STATUS)
                awaitText(message)
                val retry = hasText(string(R.string.action_retry)) and hasClickAction()
                composeRule.onNode(retry).assertExists("the snackbar has no Retry")
                composeRule.onNode(hasText(entryTitle(3, PAGE_SIZE))).assertExists("page 1 left the listing")
                fixture.awaitFailed("/popular?page=2")

                // Answered again, Retry loads page 2 and the snackbar goes.
                fixture.answerAgain()
                composeRule.onNode(retry).performClick()
                awaitNoText(message)
                scrollTo(hasText(entryTitle(3, PAGE_SIZE + 1)))
                fixture.awaitServed("/popular?page=2")
                val pageTwo = fixture.served().filter { it.path == "/popular?page=2" }
                assertEquals("page 2's requests, in order", listOf("failed", "popular"), pageTwo.map { it.kind }.distinct())
                Log.i(LOG_TAG, "page 2 was asked ${pageTwo.size} times: ${pageTwo.map { "${it.status} ${it.kind}" }}")
            }
            // A server already gone fails this too, under the test's own failure rather than over it.
            teardown.step { fixture.answerAgain() }
            teardown.finish()
        }
    }

    private lateinit var currentFixture: FixtureStore

    /**
     * Skips as the class comment says, or adds the store, installs the extension, sets the source
     * preferences the page reads to a known state, runs [body] with the fixture's sources by name,
     * and leaves the device as it found it.
     */
    private fun withTheFixtureExtension(method: String, body: (List<FixtureSource>) -> Unit) {
        if (!FixtureStore.isServed()) return skip("$method: no saikou.browse.fixture argument")
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            return skip("$method: an extension store other than the browse fixture store is configured")
        }
        val preferences = graph.sourcePreferences
        val kept = listOf(
            KeptPreference(preferences.enabledLanguages),
            KeptPreference(preferences.disabledSources),
            KeptPreference(preferences.pinnedSources),
            KeptPreference(preferences.lastUsedSource),
            KeptPreference(graph.libraryPreferences.defaultCategory),
        )
        val fixture = FixtureStore(graph)
        currentFixture = fixture
        var sourceIds = emptyList<Long>()
        var entriesBefore = emptySet<Long>()
        val teardown = Teardown()
        teardown.step {
            preferences.enabledLanguages.set(setOf(LANGUAGE_ALL, ENGLISH))
            preferences.disabledSources.set(emptySet())
            preferences.pinnedSources.set(emptySet())
            preferences.lastUsedSource.set(-1L)
            val installed = runBlocking(Dispatchers.IO) {
                fixture.add()
                fixture.installTheExtension()
            }
            val sources = installed.sources.sortedBy { it.name }.map { FixtureSource(it.id, it.name) }
            assertEquals("the fixture extension's sources", FIXTURE_SOURCES, sources.size)
            sourceIds = sources.map { it.id }
            entriesBefore = runBlocking(Dispatchers.IO) { graph.mangaRepository.getLiveNonLibraryMangaIds(sourceIds) }.toSet()
            body(sources)
            val served = fixture.served()
            Log.i(LOG_TAG, "$method: the fixture server logged ${served.size} requests: ${served.map { "${it.status} ${it.path}" }}")
            // The index, and the APK when this method installed the extension from the store.
            val asked = listOfNotNull(FixtureStore.INDEX_PATH, FixtureStore.APK_PATH.takeIf { fixture.installedTheExtension })
            runBlocking(Dispatchers.IO) { fixture.assertOnlyTheFixtureWasAsked(*asked.toTypedArray()) }
        }
        // Each step runs whatever the ones before it did, the store and the extension first.
        teardown.step { runBlocking(Dispatchers.IO) { fixture.restore() } }
        if (sourceIds.isNotEmpty()) {
            teardown.step {
                // The listings opened here add entries outside the library, as Browse does.
                runBlocking(Dispatchers.IO) {
                    val added = graph.mangaRepository.getLiveNonLibraryMangaIds(sourceIds) - entriesBefore
                    graph.mangaRepository.deleteNonLibraryMangaByIds(added, keepReadManga = false)
                }
            }
        }
        kept.forEach { preference -> teardown.step { preference.restore() } }
        teardown.finish()
    }

    /**
     * An entry of [source] outside the library, with chapters 1 to [PROMPT_CHAPTERS] at the fixture's
     * addresses for entry [entry], opened on its own screen. Afterwards its downloads and folder go,
     * and it leaves the library if the prompt put it there, so the class's cleanup deletes it.
     */
    private fun withAnEntryNotInTheLibrary(source: FixtureSource, entry: Int, body: (Manga, List<Chapter>) -> Unit) {
        val categories = runBlocking(Dispatchers.IO) { graph.getCategories.await() }.filterNot { it.isSystemCategory }
        if (categories.isNotEmpty() && graph.libraryPreferences.defaultCategory.get() < 0) {
            // A device with categories would answer Add with the category choice (R130 item 5); the
            // default category, put back afterwards, keeps the add to the one tap this test makes.
            graph.libraryPreferences.defaultCategory.set(Category.UNCATEGORIZED_ID.toInt())
        }
        var manga: Manga? = null
        var chapters = emptyList<Chapter>()
        val teardown = Teardown()
        teardown.step {
            val inserted = runBlocking(Dispatchers.IO) {
                graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = source.id,
                            url = "/manga/$entry",
                            title = "$PROMPT_PREFIX${System.nanoTime()}",
                            favorite = false,
                            initialized = true,
                        ),
                    ),
                ).single()
            }
            manga = inserted
            assertFalse("the prompt's entry is in the library before the test", inserted.favorite)
            val now = System.currentTimeMillis()
            chapters = runBlocking(Dispatchers.IO) {
                graph.chapterRepository.addAll(
                    (1..PROMPT_CHAPTERS).map { number ->
                        Chapter.create().copy(
                            mangaId = inserted.id,
                            url = "/manga/$entry/chapter/$number",
                            name = "Chapter $number",
                            chapterNumber = number.toDouble(),
                            sourceOrder = (PROMPT_CHAPTERS - number).toLong(),
                            dateFetch = now,
                            dateUpload = now,
                        )
                    },
                ).sortedBy { it.chapterNumber }
            }
            context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, inserted.id))
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(chapterRow(1)).fetchSemanticsNodes().isNotEmpty()
            }
            body(inserted, chapters)
        }
        val ids = chapters.map { it.id }
        teardown.step { runBlocking(Dispatchers.IO) { graph.downloadQueue.remove(ids) } }
        teardown.step { runBlocking(Dispatchers.IO) { graph.downloadManifest.delete(ids) } }
        val entry = manga
        if (entry != null) {
            teardown.step {
                graph.downloadRoot.mangaDir(source.name, entry.title).deleteRecursively()
                // Only when it is empty, so a folder this test did not make stays.
                graph.downloadRoot.sourceDir(source.name).delete()
            }
            teardown.step { runBlocking(Dispatchers.IO) { graph.mangaRepository.update(MangaUpdate(id = entry.id, favorite = false)) } }
        }
        teardown.finish()
    }

    private fun download(selectingChapter: Int) {
        composeRule.onNode(chapterRow(selectingChapter)).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.selection_count_format, 1)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(string(R.string.action_download)).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.selection_count_format, 1)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun swipeToDownload(chapter: Int) {
        composeRule.onNode(chapterRow(chapter)).performTouchInput { swipeLeft(startX = left + width * 0.4f, endX = left) }
    }

    private fun chapterRow(number: Int): SemanticsMatcher = hasText("Chapter $number") and hasClickAction()

    /** The prompt, in upstream's words, with its Add and its dismiss. */
    private fun awaitPrompt() {
        awaitText(string(R.string.snack_add_to_library))
        composeRule.onNode(hasText(string(R.string.action_add)) and hasClickAction()).assertExists("the prompt has no Add")
        composeRule.onNode(hasContentDescription(snackbarDismiss()) and hasClickAction()).assertExists("the prompt has no dismiss")
    }

    private fun assertOnePrompt() {
        composeRule.waitForIdle()
        assertEquals(
            "prompts on screen after the second download",
            1,
            composeRule.onAllNodesWithText(string(R.string.snack_add_to_library)).fetchSemanticsNodes().size,
        )
    }

    /** A second prompt queued behind the first would show as soon as the first is answered. */
    private fun assertNoSecondPrompt() {
        val until = System.currentTimeMillis() + SECOND_PROMPT_WATCH_MILLIS
        while (System.currentTimeMillis() < until) {
            composeRule.waitForIdle()
            assertTrue(
                "a second download asked again",
                composeRule.onAllNodesWithText(string(R.string.snack_add_to_library)).fetchSemanticsNodes().isEmpty(),
            )
            Thread.sleep(PROMPT_POLL_MILLIS)
        }
    }

    /** Both downloads finished from the fixture server, so nothing is in flight during the cleanup. */
    private fun awaitDownloads(chapters: List<Chapter>) {
        val ids = chapters.map { it.id }.toSet()
        composeRule.waitUntil(timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS) {
            runBlocking(Dispatchers.IO) { graph.downloadManifestRepository.getAll() }.count { it.chapterId in ids } == ids.size
        }
    }

    /** Material's dismiss action on a snackbar, in the device's language. */
    private fun snackbarDismiss(): String {
        val id = context.resources.getIdentifier(SNACKBAR_DISMISS_STRING, "string", context.packageName)
        assertNotEquals("no $SNACKBAR_DISMISS_STRING string in the app", 0, id)
        return context.getString(id)
    }

    private fun openSourcesPage() {
        composeRule.onNode(hasText(string(R.string.tab_browse)) and isSelectable()).performClick()
        composeRule.onNode(hasText(string(R.string.browse_tab_sources)) and isSelectable()).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) { filterActions() == 1 }
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

    private fun scrollTo(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The Sources page's section labels on screen, by text, with where each starts. */
    private fun labelTops(): Map<String, Float> =
        listOf(R.string.last_used_source, R.string.pinned_sources, R.string.other_source)
            .map(::string)
            .mapNotNull { label ->
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().firstOrNull()?.let { label to it.boundsInRoot.top }
            }
            .toMap()

    private fun top(matcher: SemanticsMatcher): Float = composeRule.onNode(matcher).fetchSemanticsNode().boundsInRoot.top

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

    private fun FixtureStore.awaitFailed(path: String) {
        val deadline = System.currentTimeMillis() + SCREEN_TIMEOUT_MILLIS
        while (served().none { it.path == path && it.kind == "failed" }) {
            assertTrue("the fixture server never failed $path: ${served().map { "${it.status} ${it.path}" }}", System.currentTimeMillis() < deadline)
            Thread.sleep(PROMPT_POLL_MILLIS)
        }
    }

    /** A fixture entry's title, as `tools/bench/update_fixture.py` writes it. */
    private fun entryTitle(source: Int, entry: Int): String = "Fixture %02d-%03d".format(source, entry)

    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: $reason")
    }

    private fun string(resourceId: Int, vararg args: Any): String = context.getString(resourceId, *args)

    /** What the test needs of a fixture source, so no live source outlives the install. */
    data class FixtureSource(val id: Long, val name: String)

    /** A preference's value as found, set or not, and putting it back. */
    private class KeptPreference<T>(private val preference: Preference<T>) {
        private val wasSet = preference.isSet()
        private val value = preference.get()

        fun restore() {
            if (wasSet) preference.set(value) else preference.delete()
        }
    }

    private companion object {
        const val LOG_TAG = "FixtureSourcesDeviceTest"

        const val LANGUAGE_ALL = "all"
        const val ENGLISH = "en"
        const val FIXTURE_SOURCES = 20

        /** Rows checked for a Latest button; the first few sit on any screen with the Local source. */
        const val VISIBLE_SOURCES = 3

        /** The fixture's listing page size and the status its failing page answers. */
        const val PAGE_SIZE = 50
        const val FAILED_STATUS = 500

        /** Entries of their own, far from the listing's first pages, for the two prompt methods. */
        const val SELECTION_ENTRY = 241
        const val SWIPE_ENTRY = 242
        const val PROMPT_CHAPTERS = 3
        const val PROMPT_PREFIX = "TG2 prompt "

        /** Material 3's own string for a snackbar's dismiss action. */
        const val SNACKBAR_DISMISS_STRING = "m3c_snackbar_dismiss"

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

        /** Three pages a chapter at 400 to 800 ms each, two chapters, on a loaded host. */
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L

        /** Longer than a Short snackbar (4 s), which the add's own message is, queued behind the prompt. */
        const val SECOND_PROMPT_WATCH_MILLIS = 5_000L
        const val PROMPT_POLL_MILLIS = 250L
    }
}
