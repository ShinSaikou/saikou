package com.shinsaikou.ui.manga

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.source.toDomainChapter
import com.shinsaikou.domain.source.toDomainManga
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallOutcome
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.i18n.R
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.ui.chapter.ChapterTextAlphaKey
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import com.shinsaikou.util.EvidenceCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The chapter row of plan 2.6 on a device (unit ID3): the marks a row carries, the download
 * control's states and upstream's taps on each, the selection bar's rules, the Updates row's control,
 * and a Local entry's rows, which have none.
 *
 * Every entry is the test's own, on a source id no extension carries (or the Local source), with
 * `initialized` set so the screen starts no fetch, and `favorite` set so a download asks nothing.
 * A chapter on a missing source fails at once when the engine picks it up, which is how a failed
 * row is made and how a retry is seen to have queued again. Each test removes what it seeded,
 * queue rows and folders included, whatever the outcome.
 *
 * Every absence asserted here comes after a presence on the same screen (R131 item 2a): a row whose
 * marks are loaded, a control that is drawn, a selection bar that shows an action.
 *
 * The downloading state needs a page that stays in flight, so
 * [aDownloadingChapterDrawsItsProgressAndItsTapsAreTheQueuedOnes] installs the fixture extension
 * the gate's force-stop row uses and is served by `download_hold_server.py` on port 18794; without
 * `saikou.download.fixture.apk` it logs a `Skipped:` line and returns, as `SourcesFilterDeviceTest`
 * does.
 */
@RunWith(AndroidJUnit4::class)
class ChapterRowDeviceTest {

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

    /** The theme an evidence run names, before the launch; nothing on any other run. */
    @get:Rule(order = 2)
    val evidenceTheme: TestRule = EvidenceCapture.themeRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /** What the rule launched the activity with; see [handTheLaunchIntentBack]. */
    private lateinit var launchIntent: Intent

    /**
     * Every test opens its screen with an intent of the app's own, which `MainActivity.onNewIntent`
     * sets as the activity's intent; `ActivityScenario` then no longer recognises the activity and
     * fails the teardown, as `TwoPaneLayoutTest.handTheLaunchIntentBack` explains. The original goes
     * back before the rule closes the scenario.
     */
    @After
    fun handTheLaunchIntentBack() {
        if (::launchIntent.isInitialized) {
            composeRule.runOnUiThread { composeRule.activity.intent = launchIntent }
        }
    }

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
     * One entry with a chapter in each state: unread and bookmarked, read, read part of the way,
     * queued, downloaded and failed. Each row says what it is by description ("Unread",
     * "Bookmarked", the control's state), a read row's text is drawn at upstream's disabled alpha, and
     * a row read part of the way shows "Page: N" with N one past the stored page.
     */
    @Test
    fun eachRowDescribesItsMarksAndItsDownloadStateAndAReadRowIsFaded() {
        val fixture = seedStatesEntry()
        try {
            openEntry(fixture.mangaId)
            awaitRow(UNREAD_BOOKMARKED)
            awaitControl(UNREAD_BOOKMARKED, string(R.string.action_download))
            awaitControl(PARTLY_READ, string(R.string.chapter_download_queued))
            awaitControl(DOWNLOADED, string(R.string.chapter_downloaded))
            awaitControl(FAILED, string(R.string.chapter_download_failed))
            awaitControl(READ, string(R.string.action_download))

            val unread = string(R.string.chapter_unread)
            val bookmarked = string(R.string.action_filter_bookmarked)
            composeRule.onAllNodes(row(UNREAD_BOOKMARKED) and hasContentDescription(unread)).assertCountEquals(1)
            composeRule.onAllNodes(row(UNREAD_BOOKMARKED) and hasContentDescription(bookmarked)).assertCountEquals(1)
            // Absences, after the presences above on the same screen.
            composeRule.onAllNodes(row(READ) and hasContentDescription(unread)).assertCountEquals(0)
            composeRule.onAllNodes(row(READ) and hasContentDescription(bookmarked)).assertCountEquals(0)
            // None of the per-row buttons plan 2.6 removed is left on any row: the "<" (mark previous
            // as read, a bare "<" text on every unread row, with no description), the bookmark
            // button and the read button. Nothing is selected, so no selection bar names them either.
            composeRule.onAllNodesWithText("<").assertCountEquals(0)
            composeRule.onAllNodesWithContentDescription(string(R.string.action_bookmark)).assertCountEquals(0)
            composeRule.onAllNodesWithText(string(R.string.action_mark_as_read)).assertCountEquals(0)
            composeRule.onAllNodesWithText(string(R.string.action_mark_as_unread)).assertCountEquals(0)

            assertEquals("the unread row's text alpha", 1f, textAlphaOf(UNREAD_BOOKMARKED))
            assertEquals("the read row's text alpha", DISABLED_ALPHA, textAlphaOf(READ))

            val progress = context.getString(R.string.chapter_progress, PARTLY_READ_PAGE + 1)
            composeRule.onAllNodes(row(PARTLY_READ) and hasText(progress, substring = true)).assertCountEquals(1)
            composeRule.onAllNodes(hasText(progress, substring = true)).assertCountEquals(1)

            capture("entry")
            EvidenceCapture.accessibilityDump(EVIDENCE_UNIT, "entry")
            composeRule.onNode(control(DOWNLOADED, string(R.string.chapter_downloaded))).performClick()
            awaitMenuItem(R.string.action_delete)
            capture("entry-download-menu")
        } finally {
            fixture.remove()
        }
    }

    /**
     * Upstream's `ChapterDownloadIndicator` taps, state by state. Downloaded: a tap opens Delete alone,
     * which deletes the folder and the manifest row. Queued: a tap opens Start downloading now and
     * Cancel, Cancel takes the chapter off the queue, and a long press cancels with no menu. Failed:
     * a tap queues the chapter again (its stored error is cleared, so the engine's own failure on
     * this missing source replaces the marker this test stored). Not downloaded: a tap queues the
     * chapter, and a long press starts it now, at the front of the queue.
     */
    @Test
    fun theDownloadControlsTapsAreUpstreamsInEveryState() {
        val fixture = seedStatesEntry()
        try {
            openEntry(fixture.mangaId)
            awaitControl(DOWNLOADED, string(R.string.chapter_downloaded))
            awaitControl(PARTLY_READ, string(R.string.chapter_download_queued))
            awaitControl(FAILED, string(R.string.chapter_download_failed))

            // Downloaded: Delete, and nothing else.
            composeRule.onNode(control(DOWNLOADED, string(R.string.chapter_downloaded))).performClick()
            awaitMenuItem(R.string.action_delete)
            menuItems(R.string.action_start_downloading_now).assertCountEquals(0)
            menuItems(R.string.action_cancel).assertCountEquals(0)
            composeRule.onNode(menuItem(R.string.action_delete)).performClick()
            awaitTrue("Delete did not delete the downloaded chapter") {
                !fixture.downloadedDir.exists() && manifestRowOf(fixture.id(DOWNLOADED)) == null
            }
            awaitControl(DOWNLOADED, string(R.string.action_download))

            // Queued: Start downloading now and Cancel; Cancel takes it off the queue.
            composeRule.onNode(control(PARTLY_READ, string(R.string.chapter_download_queued))).performClick()
            awaitMenuItem(R.string.action_start_downloading_now)
            awaitMenuItem(R.string.action_cancel)
            menuItems(R.string.action_delete).assertCountEquals(0)
            composeRule.onNode(menuItem(R.string.action_cancel)).performClick()
            awaitTrue("Cancel left the chapter queued") { queueRowOf(fixture.id(PARTLY_READ)) == null }
            awaitControl(PARTLY_READ, string(R.string.action_download))

            // Queued again by hand: a long press cancels with no menu.
            runBlocking { graph.downloadQueue.enqueue(fixture.manga, listOf(fixture.chapter(PARTLY_READ)), autoStart = false) }
            awaitControl(PARTLY_READ, string(R.string.chapter_download_queued))
            composeRule.onNode(control(PARTLY_READ, string(R.string.chapter_download_queued)))
                .performTouchInput { longClick() }
            awaitTrue("a long press left the chapter queued") { queueRowOf(fixture.id(PARTLY_READ)) == null }
            menuItems(R.string.action_cancel).assertCountEquals(0)

            // Failed: a tap queues it again, which clears the stored error.
            assertEquals(FAILED_MARKER, queueRowOf(fixture.id(FAILED))?.errorMessage)
            composeRule.onNode(control(FAILED, string(R.string.chapter_download_failed))).performClick()
            awaitTrue("a tap on the failed chapter did not queue it again") {
                queueRowOf(fixture.id(FAILED)).let { it != null && it.errorMessage != FAILED_MARKER }
            }

            // Not downloaded: a tap queues it.
            composeRule.onNode(control(UNREAD_BOOKMARKED, string(R.string.action_download))).performClick()
            awaitTrue("a tap on Download did not queue the chapter") { queueRowOf(fixture.id(UNREAD_BOOKMARKED)) != null }

            // Not downloaded: a long press starts it now, at the front of the queue.
            awaitControl(READ, string(R.string.action_download))
            composeRule.onNode(control(READ, string(R.string.action_download))).performTouchInput { longClick() }
            awaitTrue("a long press on Download did not start the chapter at the front of the queue") {
                queueOrderOf(fixture.mangaId).firstOrNull() == fixture.id(READ)
            }
        } finally {
            fixture.remove()
        }
    }

    /**
     * The selection bar shows what applies to the selection and nothing else, by upstream's rules, and
     * the rows' download controls are off while selecting. One unread chapter not downloaded, then
     * with a read, bookmarked, downloaded one added, then that one alone.
     */
    @Test
    fun theSelectionBarShowsOnlyTheActionsThatApply() {
        val fixture = seedStatesEntry()
        try {
            openEntry(fixture.mangaId)
            awaitControl(UNREAD_BOOKMARKED, string(R.string.action_download))
            awaitControl(DOWNLOADED, string(R.string.chapter_downloaded))

            // The read chapter, unbookmarked and not downloaded.
            composeRule.onNode(row(READ)).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
            awaitText(selectedCount(1))
            assertActions(
                shown = listOf(
                    R.string.action_bookmark,
                    R.string.action_mark_as_unread,
                    R.string.action_mark_previous_as_read,
                    R.string.action_download,
                ),
                hidden = listOf(R.string.action_remove_bookmark, R.string.action_mark_as_read, R.string.action_delete_downloads),
            )
            // The controls are off while selecting: none of the six rows draws one.
            composeRule.onAllNodes(controlOfAnyRow()).assertCountEquals(0)
            capture("entry-selection-bar")

            // Add the unread, bookmarked chapter: each action any of the two needs.
            composeRule.onNode(row(UNREAD_BOOKMARKED)).performClick()
            awaitText(selectedCount(2))
            assertActions(
                shown = listOf(
                    R.string.action_bookmark,
                    R.string.action_mark_as_read,
                    R.string.action_mark_as_unread,
                    R.string.action_download,
                ),
                hidden = listOf(
                    R.string.action_remove_bookmark,
                    R.string.action_mark_previous_as_read,
                    R.string.action_delete_downloads,
                ),
            )
            capture("entry-selection-bar-two")

            // The read one off: the bookmarked one alone.
            composeRule.onNode(row(READ)).performClick()
            awaitText(selectedCount(1))
            assertActions(
                shown = listOf(
                    R.string.action_remove_bookmark,
                    R.string.action_mark_as_read,
                    R.string.action_mark_previous_as_read,
                    R.string.action_download,
                ),
                hidden = listOf(R.string.action_bookmark, R.string.action_mark_as_unread, R.string.action_delete_downloads),
            )

            // The downloaded chapter alone (added before the other goes, so the selection never
            // empties and ends selection mode): Delete, and no Download.
            composeRule.onNode(row(DOWNLOADED)).performClick()
            awaitText(selectedCount(2))
            composeRule.onNode(row(UNREAD_BOOKMARKED)).performClick()
            awaitText(selectedCount(1))
            assertActions(
                shown = listOf(R.string.action_delete_downloads, R.string.action_mark_as_read),
                hidden = listOf(R.string.action_download, R.string.action_mark_as_unread),
            )
            capture("entry-selection-bar-downloaded")
        } finally {
            fixture.remove()
        }
    }

    /**
     * The entry screen's sheets and dialogs open from their controls on a longer entry, and its
     * chapter list scrolls (unit ID3): the chapter settings sheet from the Filter
     * button, the scanlator filter dialog from its row in that sheet, listing both of the entry's
     * groups, and the reading mode sheet from the overflow menu, each closed again without a
     * change. The tracking sheet opens only with a tracker signed in, so it is
     * `TrackSheetDeviceTest`'s, against the MyAnimeList fake.
     */
    @Test
    fun theEntryScreensSheetsAndDialogsOpenAndItsChapterListScrolls() {
        val title = "$PREFIX${System.nanoTime()}"
        var mangaId = -1L
        try {
            runBlocking {
                mangaId = insertEntry(SOURCE_ID, title, dateAdded = System.currentTimeMillis())
                graph.chapterRepository.addAll(
                    (1..LONG_CHAPTER_COUNT).map { number ->
                        chapterRow(mangaId, title, number).copy(
                            sourceOrder = (LONG_CHAPTER_COUNT - number).toLong(),
                            scanlator = if (number % 2 == 0) SCANLATOR_A else SCANLATOR_B,
                        )
                    },
                )
            }
            openEntry(mangaId)
            val first = chapterName(1)
            val last = chapterName(LONG_CHAPTER_COUNT)
            composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(row(first)).fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodes(row(last)).fetchSemanticsNodes().isNotEmpty()
            }
            capture("entry-long-top")

            // Whichever end the sort put at the bottom is not composed yet; the list scrolls to it.
            val shown = if (composeRule.onAllNodes(row(first)).fetchSemanticsNodes().isNotEmpty()) first else last
            val hidden = if (shown == first) last else first
            composeRule.onAllNodes(row(hidden)).assertCountEquals(0)
            // Held by node id: a matcher on the shown row would lose the list once that row scrolls out.
            val listId = composeRule.onNode(hasScrollToNodeAction() and hasAnyDescendant(row(shown))).fetchSemanticsNode().id
            composeRule.onNode(SemanticsMatcher("the chapter list") { it.id == listId }).performScrollToNode(row(hidden))
            composeRule.onNode(row(hidden)).assertIsDisplayed()
            capture("entry-scrolled")

            // The chapter settings sheet, from the Filter button.
            composeRule.onNode(hasContentDescription(string(R.string.action_filter)) and hasClickAction()).performClick()
            awaitText(string(R.string.action_sort))
            awaitText(string(R.string.chapter_filter_scanlators))
            capture("sheet-chapter-settings")

            // The scanlator filter dialog, from the sheet's Scanlators row, with both groups.
            composeRule.onNode(hasText(string(R.string.chapter_filter_scanlators)) and hasClickAction()).performClick()
            awaitText(string(R.string.scanlator_filter_title))
            awaitNode(hasText(SCANLATOR_A) and hasAnyAncestor(isDialog()))
            awaitNode(hasText(SCANLATOR_B) and hasAnyAncestor(isDialog()))
            capture("dialog-scanlator-filter")
            composeRule.onNode(hasText(string(R.string.action_cancel)) and hasClickAction()).performClick()
            awaitGone(hasText(string(R.string.scanlator_filter_title)))

            // The reading mode sheet, from the overflow menu, closed with back.
            composeRule.onNodeWithTag(MANGA_OVERFLOW_TAG).performClick()
            composeRule.onNode(hasText(string(R.string.pref_category_reading_mode)) and hasClickAction()).performClick()
            awaitText(string(R.string.rotation_type))
            capture("sheet-reading-mode")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitGone(hasText(string(R.string.rotation_type)))
        } finally {
            if (mangaId > 0) runBlocking { graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis()) }
        }
    }

    /**
     * A Local entry's chapters are on disk by definition: its rows draw no download control, and its
     * selection bar offers neither Download nor Delete.
     */
    @Test
    fun aLocalEntrysRowsHaveNoDownloadControlAndItsSelectionNoDownloadActions() {
        val title = "$PREFIX${System.nanoTime()}"
        var mangaId = -1L
        try {
            runBlocking {
                mangaId = insertEntry(SourceIds.LOCAL, title, dateAdded = System.currentTimeMillis())
                graph.chapterRepository.addAll(listOf(chapterRow(mangaId, title, 1)))
            }
            openEntry(mangaId)
            awaitRow(chapterName(1))
            composeRule.onAllNodes(row(chapterName(1)) and hasContentDescription(string(R.string.chapter_unread)))
                .assertCountEquals(1)
            composeRule.onAllNodes(controlOfAnyRow()).assertCountEquals(0)

            composeRule.onNode(row(chapterName(1))).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
            awaitText(selectedCount(1))
            awaitAction(R.string.action_mark_as_read)
            composeRule.onAllNodesWithContentDescription(string(R.string.action_download)).assertCountEquals(0)
            composeRule.onAllNodesWithContentDescription(string(R.string.action_delete_downloads)).assertCountEquals(0)
        } finally {
            if (mangaId > 0) runBlocking { graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis()) }
        }
    }

    /**
     * Updates rows take the same control in place of their per-row buttons: a tap on Download
     * queues the chapter, a Local entry's row has no control, and selecting turns the controls off.
     * A row tap opens the reader and the cover opens the entry, as upstream's Updates do; neither is
     * tapped here, so the screen stays put.
     */
    @Test
    fun anUpdatesRowsControlQueuesTheChapterAndALocalUpdateHasNone() {
        val now = System.currentTimeMillis()
        val networkTitle = "$PREFIX${System.nanoTime()}"
        val localTitle = "$PREFIX${System.nanoTime()}"
        var networkId = -1L
        var localId = -1L
        var chapterId = -1L
        try {
            runBlocking {
                networkId = insertEntry(UPDATES_SOURCE_ID, networkTitle, dateAdded = now - UPDATE_AGE_MILLIS)
                chapterId = graph.chapterRepository.addAll(listOf(chapterRow(networkId, networkTitle, 1, now)))
                    .single().id
                localId = insertEntry(SourceIds.LOCAL, localTitle, dateAdded = now - UPDATE_AGE_MILLIS)
                graph.chapterRepository.addAll(listOf(chapterRow(localId, localTitle, 1, now)))
            }
            context.startActivity(MainIntents.newIntent(context, MainDestination.UPDATES))
            awaitText(networkTitle)
            awaitText(localTitle)
            val download = string(R.string.action_download)
            awaitNode(updateControl(networkTitle, download))
            composeRule.onAllNodes(updateControl(localTitle, download)).assertCountEquals(0)
            capture("updates")

            composeRule.onNode(updateControl(networkTitle, download)).performClick()
            awaitTrue("a tap on an Updates row's Download did not queue the chapter") { queueRowOf(chapterId) != null }

            composeRule.onNode(updateRow(localTitle)).performTouchInput { longClick(percentOffset(0.5f, 0.5f)) }
            awaitText(selectedCount(1))
            composeRule.onAllNodes(controlOfAnyUpdate(networkTitle)).assertCountEquals(0)
        } finally {
            runBlocking {
                if (chapterId > 0) graph.downloadQueue.remove(listOf(chapterId))
                if (networkId > 0) graph.mangaRepository.softDelete(networkId, System.currentTimeMillis())
                if (localId > 0) graph.mangaRepository.softDelete(localId, System.currentTimeMillis())
            }
        }
    }

    /**
     * A chapter the engine is downloading, its later pages held open by the fixture server, draws
     * "Downloading, page N of M" and takes the queued taps: Start downloading now and Cancel on a
     * tap, and Cancel takes it off the queue.
     */
    @Test
    fun aDownloadingChapterDrawsItsProgressAndItsTapsAreTheQueuedOnes() {
        val apk = InstrumentationRegistry.getArguments().getString(FIXTURE_APK_ARGUMENT)
        if (apk.isNullOrBlank()) {
            return skip("aDownloadingChapterDrawsItsProgressAndItsTapsAreTheQueuedOnes: no saikou.download.fixture.apk argument")
        }
        var packageName: String? = null
        var source: CatalogueSource? = null
        var manga: Manga? = null
        var chapters: List<Chapter> = emptyList()
        try {
            runBlocking(Dispatchers.IO) {
                val (installed, first) = installTheFixtureExtension(apk)
                packageName = installed
                source = first
                val status = holdStatus()
                assertTrue("the fixture server is not holding page requests: $status", status.getBoolean("holding"))
                val info = first.getPopularManga(1).mangas.firstOrNull()
                assertNotNull("the fixture source listed no entry", info)
                val stored = graph.mangaRepository.insertNetworkManga(
                    listOf(info!!.toDomainManga(first.id).copy(favorite = true, initialized = true)),
                ).single()
                manga = stored
                val listed = first.getChapterList(info).sortedBy { it.chapterNumber }.take(1)
                chapters = graph.chapterRepository.addAll(listed.map { it.toDomainChapter(stored.id) })
                graph.downloadQueue.enqueue(stored, chapters, autoStart = true)
            }
            val entry = checkNotNull(manga)
            val chapter = chapters.single()
            awaitTrue("the chapter never stood mid-flight", timeoutMillis = DOWNLOAD_TIMEOUT_MILLIS) {
                queueRowOf(chapter.id)?.let {
                    it.state == DownloadState.DOWNLOADING && it.downloadedPages in 1 until it.totalPages
                } == true
            }
            openEntry(entry.id)
            val row = queueRowOf(chapter.id)!!
            val downloading = context.getString(
                R.string.chapter_download_progress_format,
                row.downloadedPages.toInt(),
                row.totalPages.toInt(),
            )
            awaitNode(control(chapter.name, downloading))
            capture("entry-downloading")

            composeRule.onNode(control(chapter.name, downloading)).performClick()
            awaitMenuItem(R.string.action_start_downloading_now)
            awaitMenuItem(R.string.action_cancel)
            composeRule.onNode(menuItem(R.string.action_cancel)).performClick()
            awaitTrue("Cancel left the downloading chapter queued") { queueRowOf(chapter.id) == null }
        } finally {
            runBlocking(Dispatchers.IO) {
                graph.downloadQueue.remove(chapters.map { it.id })
                source?.let { graph.downloadRoot.sourceDir(it.name).deleteRecursively() }
                manga?.let { graph.mangaRepository.softDelete(it.id, System.currentTimeMillis()) }
                packageName?.let { removeTheFixtureExtension(it) }
            }
        }
    }

    // region Fixture

    /** An entry on [SOURCE_ID] with one chapter per state, and where its downloaded chapter lives. */
    private inner class StatesEntry(val manga: Manga, private val chapters: Map<String, Chapter>, val downloadedDir: File) {
        val mangaId get() = manga.id
        fun chapter(name: String): Chapter = chapters.getValue(name)
        fun id(name: String): Long = chapter(name).id

        fun remove() {
            runBlocking {
                graph.downloadQueue.remove(chapters.values.map { it.id })
                graph.downloadManifestRepository.remove(chapters.values.map { it.id })
                graph.mangaRepository.softDelete(manga.id, System.currentTimeMillis())
            }
            graph.downloadRoot.sourceDir(SOURCE_DIR).deleteRecursively()
        }
    }

    /**
     * Six chapters: [UNREAD_BOOKMARKED], [READ], [PARTLY_READ] (queued, not started), [DOWNLOADED]
     * (a folder with a page and its manifest row), [FAILED] (a queue row in ERROR with
     * [FAILED_MARKER] as its message) and [UNTOUCHED]. The queue is paused first, so nothing the
     * test did not ask for starts.
     */
    private fun seedStatesEntry(): StatesEntry = runBlocking {
        assertNull("the fixture source id is installed", graph.sourceManager.get(SOURCE_ID))
        graph.downloadQueue.pause()
        val title = "$PREFIX${System.nanoTime()}"
        val mangaId = insertEntry(SOURCE_ID, title, dateAdded = System.currentTimeMillis())
        val manga = graph.mangaRepository.getMangaById(mangaId)
        val names = listOf(UNREAD_BOOKMARKED, READ, PARTLY_READ, DOWNLOADED, FAILED, UNTOUCHED)
        val rows = names.mapIndexed { index, name ->
            chapterRow(mangaId, title, index + 1).copy(
                name = name,
                read = name == READ,
                bookmark = name == UNREAD_BOOKMARKED,
                lastPageRead = if (name == PARTLY_READ) PARTLY_READ_PAGE.toLong() else 0L,
            )
        }
        val chapters = graph.chapterRepository.addAll(rows).associateBy { it.name }
        assertEquals("the chapter rows were not all written", names.size, chapters.size)

        graph.downloadQueue.enqueue(manga, listOf(chapters.getValue(PARTLY_READ)), autoStart = false)
        graph.downloadQueue.enqueue(manga, listOf(chapters.getValue(FAILED)), autoStart = false)
        graph.downloadQueueRepository.setState(
            chapters.getValue(FAILED).id,
            DownloadState.ERROR,
            FAILED_MARKER,
            System.currentTimeMillis(),
        )

        val downloaded = chapters.getValue(DOWNLOADED)
        val dir = File(graph.downloadRoot.mangaDir(SOURCE_DIR, title), DOWNLOADED)
        assertTrue("could not create the chapter folder", dir.mkdirs())
        val page = File(dir, "001.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        graph.downloadManifestRepository.recordDownload(
            DownloadManifestEntry(
                id = 0,
                chapterId = downloaded.id,
                mangaId = mangaId,
                sourceId = SOURCE_ID,
                mangaTitle = title,
                mangaUrl = manga.url,
                chapterUrl = downloaded.url,
                chapterName = downloaded.name,
                scanlator = null,
                chapterDirName = DOWNLOADED,
                pageCount = 1L,
                sizeBytes = page.length(),
                dateDownloaded = System.currentTimeMillis(),
            ),
        )
        StatesEntry(manga, chapters, dir)
    }

    private suspend fun insertEntry(source: Long, title: String, dateAdded: Long): Long =
        graph.mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = source,
                    url = "/fixture/$title",
                    title = title,
                    favorite = true,
                    initialized = true,
                    dateAdded = dateAdded,
                ),
            ),
        ).single().id

    private fun chapterRow(mangaId: Long, title: String, number: Int, fetched: Long = System.currentTimeMillis()) =
        Chapter.create().copy(
            mangaId = mangaId,
            url = "/fixture/$title/$number",
            name = chapterName(number),
            chapterNumber = number.toDouble(),
            sourceOrder = (CHAPTER_COUNT - number).toLong(),
            dateFetch = fetched,
            dateUpload = fetched,
        )

    /** The fixture extension, installed and trusted as `SourcesFilterDeviceTest` does, and its first source. */
    private suspend fun installTheFixtureExtension(apkPath: String): Pair<String, CatalogueSource> {
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
            val id = installed.sources.minBy { it.name }.id
            graph.sourceManager.isInitialized.first { it }
            val live = graph.sourceManager.getSources().first { list -> list.any { it.id == id } }.first { it.id == id }
            assertTrue("fixture source $id is not a catalogue source", live is CatalogueSource)
            return packageName to live as CatalogueSource
        } finally {
            copy.delete()
        }
    }

    private suspend fun removeTheFixtureExtension(packageName: String) {
        graph.extensionManager.uninstall(packageName)
        val trusted = graph.extensionsPreferences.trustedExtensions
        trusted.set(trusted.get().filterNot { it.substringBefore(':') == packageName }.toSet())
    }

    /** The fixture server's hold, as `download_hold_server.py` reports it. */
    private fun holdStatus(): JSONObject {
        val connection = URL("$FIXTURE_SERVER$HOLD_STATUS_PATH").openConnection() as HttpURLConnection
        connection.connectTimeout = FIXTURE_SERVER_TIMEOUT_MILLIS
        connection.readTimeout = FIXTURE_SERVER_TIMEOUT_MILLIS
        return try {
            assertEquals("the fixture server answered $HOLD_STATUS_PATH with", HttpURLConnection.HTTP_OK, connection.responseCode)
            JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            connection.disconnect()
        }
    }

    // endregion

    // region Screen

    private fun openEntry(mangaId: Long) {
        context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, mangaId))
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A chapter row: the one clickable node that merges [name]. */
    private fun row(name: String): SemanticsMatcher = hasText(name) and hasClickAction()

    /** The download control inside [name]'s row, described [description]. */
    private fun control(name: String, description: String): SemanticsMatcher =
        hasContentDescription(description) and hasClickAction() and hasAnyAncestor(row(name))

    /** Any row's download control, whatever its state, on the entry screen. */
    private fun controlOfAnyRow(): SemanticsMatcher =
        isDownloadControl() and hasAnyAncestor(hasText(CHAPTER_NAME_PREFIX, substring = true) and hasClickAction())

    /** A node described as one of the control's five states. */
    private fun isDownloadControl(): SemanticsMatcher {
        val descriptions = controlDescriptions()
        return SemanticsMatcher("a download control") { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { it in descriptions }
        } and hasClickAction()
    }

    private fun controlDescriptions(): Set<String> = setOf(
        string(R.string.action_download),
        string(R.string.chapter_download_queued),
        string(R.string.chapter_download_starting),
        string(R.string.chapter_downloaded),
        string(R.string.chapter_download_failed),
    )

    /** An Updates row: the clickable node that merges the entry's [title]. */
    private fun updateRow(title: String): SemanticsMatcher = hasText(title) and hasClickAction()

    private fun updateControl(title: String, description: String): SemanticsMatcher =
        hasContentDescription(description) and hasClickAction() and hasAnyAncestor(updateRow(title))

    private fun controlOfAnyUpdate(title: String): SemanticsMatcher = isDownloadControl() and hasAnyAncestor(updateRow(title))

    private fun menuItem(label: Int): SemanticsMatcher = hasText(string(label)) and hasClickAction()

    private fun menuItems(label: Int) = composeRule.onAllNodes(menuItem(label))

    private fun awaitMenuItem(label: Int) = awaitNode(menuItem(label))

    /** The selection bar's action, an icon button described by its label. */
    private fun action(label: Int): SemanticsMatcher = hasContentDescription(string(label)) and hasClickAction()

    private fun awaitAction(label: Int) = awaitNode(action(label))

    private fun assertActions(shown: List<Int>, hidden: List<Int>) {
        shown.forEach(::awaitAction)
        hidden.forEach { label ->
            assertEquals(
                "the selection bar shows \"${string(label)}\"",
                0,
                composeRule.onAllNodes(action(label)).fetchSemanticsNodes().size,
            )
        }
    }

    /** The alpha the row's title was drawn at, off the text node's own semantics. */
    private fun textAlphaOf(name: String): Float =
        composeRule.onNode(hasText(name), useUnmergedTree = true).fetchSemanticsNode().config[ChapterTextAlphaKey]

    private fun awaitRow(name: String) = awaitNode(row(name))

    private fun awaitControl(name: String, description: String) = awaitNode(control(name, description))

    private fun awaitNode(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun selectedCount(count: Int): String = context.getString(R.string.selection_count_format, count)

    // endregion

    private fun queueRowOf(chapterId: Long) = runBlocking {
        graph.downloadQueue.subscribe().first().firstOrNull { it.chapterId == chapterId }
    }

    private fun queueOrderOf(mangaId: Long): List<Long> = runBlocking {
        graph.downloadQueue.subscribe().first().filter { it.mangaId == mangaId }.map { it.chapterId }
    }

    private fun manifestRowOf(chapterId: Long): DownloadManifestEntry? = runBlocking {
        graph.downloadManifestRepository.getByChapterId(chapterId)
    }

    /** Polls [condition] off the main thread, failing with [message] once [timeoutMillis] have passed. */
    private fun awaitTrue(message: String, timeoutMillis: Long = ACTION_TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$message (after $timeoutMillis ms)")
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * An evidence screenshot once the screen's animations are done: a menu, sheet or dialog is in
     * the tree before its enter animation has drawn it, and the compose rule's idle waits that out
     * where the instrumentation's does not.
     */
    private fun capture(name: String) {
        composeRule.waitForIdle()
        EvidenceCapture.screenshot(EVIDENCE_UNIT, name)
    }

    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: $reason")
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        const val LOG_TAG = "ChapterRowDeviceTest"
        const val EVIDENCE_UNIT = "phase5-chrome-2"
        const val PREFIX = "saikou-chapter-row-"

        /** Source ids no extension carries, after `TwoPaneLayoutTest`'s and the gate's fixtures. */
        const val SOURCE_ID = 7_310_000_000_000_021L
        const val SOURCE_DIR = "Fixture Chapter Row Source"
        const val UPDATES_SOURCE_ID = 7_310_000_000_000_022L

        const val CHAPTER_NAME_PREFIX = "Chapter "
        const val UNREAD_BOOKMARKED = "Chapter 1"
        const val READ = "Chapter 2"
        const val PARTLY_READ = "Chapter 3"
        const val DOWNLOADED = "Chapter 4"
        const val FAILED = "Chapter 5"
        const val UNTOUCHED = "Chapter 6"
        const val CHAPTER_COUNT = 6

        /** Enough rows that the list scrolls on the tablet too, in two scanlator groups. */
        const val LONG_CHAPTER_COUNT = 40
        const val SCANLATOR_A = "Fixture Group A"
        const val SCANLATOR_B = "Fixture Group B"

        /** Stored 0-based, so the row shows "Page: 5". */
        const val PARTLY_READ_PAGE = 4
        const val FAILED_MARKER = "phase5-chrome-2 fixture failure"

        /** An hour, so the chapter fetched now counts as an update of the entry. */
        const val UPDATE_AGE_MILLIS = 3_600_000L

        /** The fixture extension's APK on the device, the argument `GateRowsTest` reads. */
        const val FIXTURE_APK_ARGUMENT = "saikou.download.fixture.apk"
        const val FIXTURE_APK_COPY = "saikou-chapter-row-fixture.apk"
        const val FIXTURE_SERVER = "http://10.0.2.2:18794"
        const val HOLD_STATUS_PATH = "/hold"
        const val FIXTURE_SERVER_TIMEOUT_MILLIS = 10_000

        const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        const val SCREEN_TIMEOUT_MILLIS = 20_000L
        const val ACTION_TIMEOUT_MILLIS = 10_000L
        const val DOWNLOAD_TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 50L

        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        fun chapterName(number: Int) = "$CHAPTER_NAME_PREFIX$number"
    }
}
