package com.shinsaikou.tracking

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.di.AppGraph
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.ui.library.LIBRARY_TWO_PANE_TAG
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.manga.MANGA_OVERFLOW_TAG
import com.shinsaikou.ui.manga.MANGA_TRACKING_TAG
import com.shinsaikou.ui.manga.TRACK_SHEET_CONFIRM_REMOVE_TAG
import com.shinsaikou.ui.manga.TRACK_SHEET_HOME_TAG
import com.shinsaikou.ui.manga.TRACK_SHEET_OK_TAG
import com.shinsaikou.ui.manga.TRACK_SHEET_QUERY_TAG
import com.shinsaikou.ui.manga.TRACK_SHEET_REMOTE_BOX_TAG
import com.shinsaikou.ui.manga.TRACK_SHEET_SEARCH_TAG
import com.shinsaikou.ui.manga.TRACK_SHEET_TRACK_TAG
import com.shinsaikou.ui.manga.trackSearchResultTag
import com.shinsaikou.ui.manga.trackStatusTag
import com.shinsaikou.ui.settings.settingsSectionBodyTag
import com.shinsaikou.util.Args
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import java.net.HttpURLConnection
import java.net.URL

/**
 * Upstream's track sheet on the device (phase 3 unit T3), opened from the entry's tracking button.
 *
 * Each test seeds one favorite Local source entry with one chapter, as `TwoPaneLayoutTest` does, and
 * opens it from the library: its own screen on a phone, the library's detail pane on a tablet.
 *
 * [theTrackingButtonOpensTheTrackingSectionWhileNoTrackerIsSignedIn] runs in every suite: a test
 * base makes MyAnimeList configured and it is signed out, so the button reads "Tracking" and opens
 * the Tracking section of Settings. Nothing is contacted.
 *
 * [theSheetBindsEditsAndRemovesAgainstTheFake] needs the host's MyAnimeList fake
 * (`tools/bench/mal_fixture_server.py`) named by the `saikou.mal.base` runner argument, and logs a
 * `Skipped:` line and returns without it, as `MyAnimeListSignInDeviceTest` does. With it, MyAnimeList
 * is signed in through the fake's authorization and token endpoints directly (the browser leg is
 * that class's), and the sheet is driven: a search for the fake's entry, a bind, and on a phone the
 * status, chapter, score and start date set through their pages, the sheet closed with back and
 * opened again (which refreshes the bound row), and the entry removed with the service's box ticked. On a tablet it binds once from the library pane. Every step waits for the
 * row, read by manga and tracker, to hold what the fake answered; the host reads the fake's log for
 * the requests. Screens of each page go to [SCREENS_DIR].
 *
 * Whatever happens, MyAnimeList is signed out, the row deleted and the entry soft deleted at the
 * end, and the test base is put back.
 */
@RunWith(AndroidJUnit4::class)
class TrackSheetDeviceTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /** As `MyAnimeListSignInDeviceTest`'s: the first launch's dialogs answered and the test base set. */
    @get:Rule(order = 1)
    val testBaseSet = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                graph.basePreferences.batteryExemptionRequested.set(true)
                graph.crashLog.markExported()
                val previous = graph.myAnimeListTestBase.value
                graph.myAnimeListTestBase.value = runnerBase() ?: UNCONTACTED_BASE
                try {
                    base.evaluate()
                } finally {
                    graph.myAnimeListTestBase.value = previous
                }
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph
    private val tracker: MyAnimeList get() = graph.trackerManager.get(TrackerIds.MYANIMELIST) as MyAnimeList

    /** Carries the run's start time, as `TwoPaneLayoutTest`'s does, so no earlier run's row collides. */
    private lateinit var title: String
    private var mangaId: Long = -1L
    private lateinit var launchIntent: Intent

    @Before
    fun seedOneLibraryEntry() {
        title = "$FIXTURE_PREFIX${System.nanoTime()}"
        runBlocking(Dispatchers.IO) {
            tracker.logout()
            mangaId = graph.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = LOCAL_SOURCE_ID,
                        url = title,
                        title = title,
                        favorite = true,
                        // Initialized, so the detail screen starts no fetch from a source with no directory.
                        initialized = true,
                        dateAdded = System.currentTimeMillis(),
                    ),
                ),
            ).single().id
            graph.chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = mangaId,
                        url = "$title/1",
                        name = "Chapter 1",
                        chapterNumber = 1.0,
                        sourceOrder = 0,
                        dateFetch = System.currentTimeMillis(),
                        dateUpload = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        awaitLibrary()
        launchIntent = composeRule.activity.intent
    }

    /** See `TwoPaneLayoutTest.handTheLaunchIntentBack`. */
    @After
    fun handTheLaunchIntentBack() {
        if (::launchIntent.isInitialized) {
            composeRule.runOnUiThread { composeRule.activity.intent = launchIntent }
        }
    }

    @After
    fun removeTheFixture() {
        runBlocking(Dispatchers.IO) {
            if (mangaId > 0) {
                graph.trackRepository.getTracksByMangaId(mangaId).filter { it.trackerId == TrackerIds.MYANIMELIST }.forEach {
                    graph.trackRepository.deleteIfBoundTo(it.mangaId, it.trackerId, it.remoteId)
                }
                graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis())
            }
            tracker.logout()
        }
    }

    @Test
    fun theTrackingButtonOpensTheTrackingSectionWhileNoTrackerIsSignedIn() {
        assertTrue("a test base did not make MyAnimeList configured", tracker.isConfigured)
        assertFalse("MyAnimeList is still signed in", tracker.isLoggedIn)
        openTheEntry()

        trackingButton().assertIsDisplayed()
        composeRule.onNode(hasTestTag(MANGA_TRACKING_TAG) and hasText(string(R.string.manga_tracking))).assertIsDisplayed()
        trackingButton().performClick()

        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(settingsSectionBodyTag(SettingsSection.TRACKING)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(settingsSectionBodyTag(SettingsSection.TRACKING)).assertIsDisplayed()
        assertTrue(
            "the sheet opened with no tracker signed in",
            sheetNodes(hasTestTag(TRACK_SHEET_HOME_TAG)).isEmpty(),
        )
        Log.i(LOG_TAG, "signed out: the tracking button opened the Tracking section, tablet UI ${composesTabletUi()}")
    }

    @Test
    fun theSheetBindsEditsAndRemovesAgainstTheFake() {
        if (runnerBase() == null) {
            // Spelled out, because gate.sh's tablet step expects this line and a tools test reads it here.
            Log.i(LOG_TAG, "Skipped: theSheetBindsEditsAndRemovesAgainstTheFake: no saikou.mal.base argument")
            return
        }
        val tablet = composesTabletUi()
        if (Args.expectTablet) assertTrue("saikou.expect.tablet is true, but the device is not composing tablet UI", tablet)
        shell("rm -rf $SCREENS_DIR")
        shell("mkdir -p $SCREENS_DIR")
        try {
            driveTheSheet(tablet)
        } catch (e: Throwable) {
            // What was on screen when it failed, and every root's semantics, for the host to read.
            screen("${prefix(tablet)}failure")
            composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                composeRule.onAllNodes(isRoot())[index].printToLog(LOG_TAG)
            }
            throw e
        }
    }

    private fun driveTheSheet(tablet: Boolean) {
        signIn()

        openTheEntry()
        if (tablet) composeRule.onNodeWithTag(LIBRARY_TWO_PANE_TAG).assertIsDisplayed()
        screen("${prefix(tablet)}entry")
        trackingButton().performClick()
        awaitHome()
        sheet(inHome(hasText(string(R.string.add_tracking)))).assertIsDisplayed()
        screen("${prefix(tablet)}home-empty")

        bind(tablet)
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodes(hasTestTag(MANGA_TRACKING_TAG) and hasText(trackers(1))).fetchSemanticsNodes().isNotEmpty()
        }
        if (tablet) {
            composeRule.onNodeWithTag(LIBRARY_TWO_PANE_TAG).assertIsDisplayed()
            Log.i(LOG_TAG, "tablet: bound from the library pane")
            return
        }

        setStatus()
        setChapter()
        setScore()
        setStartDate()
        reopen()
        remove()
    }

    /**
     * MyAnimeList signed in against the fake: its authorization URL fetched without following the
     * redirect, and the code in the redirect exchanged by the tracker's own sign-in completion.
     */
    private fun signIn(): Unit = runBlocking(Dispatchers.IO) {
        val pkce = Pkce.generate()
        val url = tracker.authorizationUrl(REDIRECT_URI, "track-sheet-${System.nanoTime()}", pkce)
        val connection = URL(url.toString()).openConnection() as HttpURLConnection
        val location = try {
            connection.instanceFollowRedirects = false
            assertEquals("the fake's authorization answer", HttpURLConnection.HTTP_MOVED_TEMP, connection.responseCode)
            connection.getHeaderField("Location")
        } finally {
            connection.disconnect()
        }
        val code = Uri.parse(location).getQueryParameter("code")
        assertTrue("the fake's redirect carried no code", !code.isNullOrBlank())
        tracker.completeSignIn(code!!, REDIRECT_URI, pkce)
        assertTrue("MyAnimeList is not signed in", tracker.isLoggedIn && !tracker.isAuthExpired)
        Log.i(LOG_TAG, "sign-in: signed in as ${tracker.getDisplayUsername()}")
    }

    /** The search page: the preselected title replaced by the fake's, its entry picked and tracked. */
    private fun bind(tablet: Boolean) {
        sheet(inHome(hasText(string(R.string.add_tracking)))).performClick()
        sheet(hasTestTag(TRACK_SHEET_SEARCH_TAG)).assertIsDisplayed()
        sheet(hasTestTag(TRACK_SHEET_QUERY_TAG)).performTextReplacement(FIXTURE_QUERY)
        sheet(hasTestTag(TRACK_SHEET_QUERY_TAG)).performImeAction()
        awaitSheet(hasTestTag(trackSearchResultTag(FIXTURE_ENTRY)))
        // The fake's light novel is left out, as MyAnimeList's search is filtered.
        assertTrue("the novel was offered", sheetNodes(hasTestTag(trackSearchResultTag(FIXTURE_NOVEL))).isEmpty())
        sheet(hasTestTag(trackSearchResultTag(FIXTURE_ENTRY))).performClick()
        screen("${prefix(tablet)}search")
        sheet(hasTestTag(TRACK_SHEET_TRACK_TAG)).performClick()
        awaitHome()
        val bound = awaitRow("the bind") { it?.remoteId == FIXTURE_ENTRY }!!
        assertEquals(FIXTURE_CHAPTERS, bound.totalChapters)
        assertEquals(MyAnimeList.PLAN_TO_READ, bound.status)
        awaitSheet(inHome(hasText(FIXTURE_TITLE)))
        screen("${prefix(tablet)}home-bound")
        Log.i(LOG_TAG, "bind: row ${bound.id} names entry ${bound.remoteId}, status ${bound.status}, of ${bound.totalChapters}")
    }

    private fun setStatus() {
        sheet(inHome(hasText(statusText(MyAnimeList.PLAN_TO_READ)))).performClick()
        sheet(hasTestTag(trackStatusTag(MyAnimeList.READING))).performClick()
        screen("status")
        sheet(hasTestTag(TRACK_SHEET_OK_TAG)).performClick()
        awaitHome()
        val row = awaitRow("the status") { it?.status == MyAnimeList.READING }!!
        awaitSheet(inHome(hasText(statusText(MyAnimeList.READING))))
        Log.i(LOG_TAG, "status: row ${row.id} reads status ${row.status}")
    }

    /** The chapter wheel's manual entry, upstream's: a tap on the wheel, the number typed, Done. */
    private fun setChapter() {
        sheet(inHome(hasText("0 / $FIXTURE_CHAPTERS"))).performClick()
        // The wheel is the one list that scrolls to an index and takes a tap; the sheet's surface cannot scroll to an index.
        sheet(hasScrollToIndexAction() and hasClickAction()).performClick()
        sheet(hasSetTextAction()).performTextReplacement("$CHAPTER")
        sheet(hasSetTextAction()).performImeAction()
        screen("chapter")
        sheet(hasTestTag(TRACK_SHEET_OK_TAG)).performClick()
        awaitHome()
        val row = awaitRow("the chapter") { it?.lastChapterRead == CHAPTER.toDouble() }!!
        awaitSheet(inHome(hasText("$CHAPTER / $FIXTURE_CHAPTERS")))
        Log.i(LOG_TAG, "chapter: row ${row.id} reads chapter ${row.lastChapterRead}")
    }

    /** The score wheel opens on the middle of MyAnimeList's 0 to 10 while unscored, as upstream's does. */
    private fun setScore() {
        sheet(inHome(hasText(string(R.string.track_score)))).performClick()
        screen("score")
        sheet(hasTestTag(TRACK_SHEET_OK_TAG)).performClick()
        awaitHome()
        val row = awaitRow("the score") { it?.score == MIDDLE_SCORE }!!
        awaitSheet(inHome(hasText("${MIDDLE_SCORE.toInt()}")))
        Log.i(LOG_TAG, "score: row ${row.id} reads score ${row.score}")
    }

    /** The date picker opens on today while the row has no start date. */
    private fun setStartDate() {
        sheet(inHome(hasText(string(R.string.track_started_reading_date)))).performClick()
        screen("start-date")
        sheet(hasTestTag(TRACK_SHEET_OK_TAG)).performClick()
        awaitHome()
        val row = awaitRow("the start date") { it != null && it.startDate != 0L }!!
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            sheetNodes(inHome(hasText(string(R.string.track_started_reading_date)))).isEmpty()
        }
        screen("home-edited")
        Log.i(LOG_TAG, "start date: row ${row.id} reads start date ${row.startDate}")
    }

    /**
     * Back on the home page closes the sheet, and the button opens it again, which refreshes the bound
     * row from the service: the host reads that GET of the entry in the fake's log.
     */
    private fun reopen() {
        // The device's back key, which goes to the sheet's own window, as the owner's back does.
        shell("input keyevent KEYCODE_BACK")
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) { sheetNodes(hasTestTag(TRACK_SHEET_HOME_TAG)).isEmpty() }
        trackingButton().assertIsDisplayed()
        trackingButton().performClick()
        awaitHome()
        awaitSheet(inHome(hasText(statusText(MyAnimeList.READING))))
        Log.i(LOG_TAG, "reopen: back closed the sheet and the button opened it again")
    }

    /** The menu's Remove, the service's box unchecked until ticked, then OK. */
    private fun remove() {
        sheet(inHome(hasContentDescription(string(R.string.label_more)))).performClick()
        sheet(hasText(string(R.string.action_remove)) and hasAnyAncestor(isPopup())).performClick()
        sheet(hasTestTag(TRACK_SHEET_REMOTE_BOX_TAG)).assertIsOff()
        sheet(hasTestTag(TRACK_SHEET_REMOTE_BOX_TAG)).performClick()
        sheet(hasTestTag(TRACK_SHEET_REMOTE_BOX_TAG)).assertIsOn()
        screen("remove")
        sheet(hasTestTag(TRACK_SHEET_CONFIRM_REMOVE_TAG)).performClick()
        awaitHome()
        awaitRow("the remove") { it == null }
        awaitSheet(inHome(hasText(string(R.string.add_tracking))))
        composeRule.onNode(hasTestTag(MANGA_TRACKING_TAG) and hasText(string(R.string.manga_tracking))).assertExists()
        screen("home-removed")
        Log.i(LOG_TAG, "remove: the row is deleted and the service was asked to delete its entry")
    }

    /** Waits for the row, read by manga and tracker (a store replaces it under a new id), to satisfy [done]. */
    private fun awaitRow(what: String, done: (Track?) -> Boolean): Track? {
        val start = System.currentTimeMillis()
        while (true) {
            val row = runBlocking(Dispatchers.IO) {
                graph.trackRepository.getTracksByMangaId(mangaId).singleOrNull { it.trackerId == TrackerIds.MYANIMELIST }
            }
            if (done(row)) return row
            assertTrue(
                "$what did not reach the row within ${SHORT_WAIT_MILLIS / 1000} s (the row: $row)",
                System.currentTimeMillis() - start < SHORT_WAIT_MILLIS,
            )
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun awaitHome() = awaitSheet(hasTestTag(TRACK_SHEET_HOME_TAG))

    private fun awaitSheet(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) { sheetNodes(matcher).isNotEmpty() }
    }

    /**
     * A node of the sheet, from the unmerged tree: the sheet's surface merges its descendants (it
     * takes clicks so they do not reach the scrim, as upstream's does), which hides every tag below it
     * in the merged tree. A click goes to the node's centre, so a text's click lands on its cell.
     */
    private fun sheet(matcher: SemanticsMatcher): SemanticsNodeInteraction = composeRule.onNode(matcher, useUnmergedTree = true)

    private fun sheetNodes(matcher: SemanticsMatcher) = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun inHome(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag(TRACK_SHEET_HOME_TAG))

    private fun trackingButton(): SemanticsNodeInteraction = composeRule.onNodeWithTag(MANGA_TRACKING_TAG)

    /**
     * The entry from the library's search, as `TwoPaneLayoutTest.findFixtureCover` finds it (its
     * comment explains the matcher), then the detail screen or pane waited on.
     */
    private fun openTheEntry() {
        composeRule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(title)
        val cover = hasText(title) and hasClickAction() and hasSetTextAction().not()
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodes(cover).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(cover).performClick()
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(MANGA_TRACKING_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** As `MainActivitySmokeTest` waits: the library's title, then no spinner. */
    private fun awaitLibrary() {
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.library_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun composesTabletUi(): Boolean {
        val mode = graph.uiPreferences.tabletUiMode.get()
        val configuration = composeRule.activity.resources.configuration
        return mode.isTabletUi(configuration.smallestScreenWidthDp, configuration.orientation)
    }

    private fun prefix(tablet: Boolean) = if (tablet) "tablet-" else ""

    private fun statusText(status: Long): String = string(tracker.getStatus(status)!!)

    private fun trackers(count: Int): String = context.resources.getQuantityString(R.plurals.num_trackers, count, count)

    private fun runnerBase(): String? =
        InstrumentationRegistry.getArguments().getString(MyAnimeListTestBaseSetting.EXTRA_MAL_BASE)?.takeIf { it.isNotBlank() }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    private fun screen(name: String) {
        composeRule.waitForIdle()
        shell("screencap -p $SCREENS_DIR/$name.png")
        Log.i(LOG_TAG, "screen: $SCREENS_DIR/$name.png")
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        const val LOG_TAG = "TrackSheetDevice"

        /** As `MyAnimeListSignInDeviceTest.UNCONTACTED_BASE`: configured, never sent a request. */
        const val UNCONTACTED_BASE = "http://127.0.0.1:9"

        /** A loopback redirect the fake accepts; nothing listens on it, since the redirect is never followed. */
        val REDIRECT_URI = "http://127.0.0.1:${LoopbackListener.TRACKER_PORT}/"

        /** `mal_fixture_server.py`'s catalogue: its manga 1001 and its light novel 1002, both titled from the query. */
        const val FIXTURE_QUERY = "Fixture Title"
        const val FIXTURE_TITLE = "Fixture Title"
        const val FIXTURE_ENTRY = 1001L
        const val FIXTURE_NOVEL = 1002L
        const val FIXTURE_CHAPTERS = 12L

        const val CHAPTER = 3
        const val MIDDLE_SCORE = 5.0

        const val FIXTURE_PREFIX = "saikou-track-sheet-"
        const val LOCAL_SOURCE_ID = 0L

        const val SCREENS_DIR = "/data/local/tmp/saikou-track-sheet"
        const val SHORT_WAIT_MILLIS = 30_000L
        const val POLL_MILLIS = 250L
    }
}
