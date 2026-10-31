package com.shinsaikou.tracking

import android.Manifest
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.settings.SETTINGS_TWO_PANE_TAG
import com.shinsaikou.ui.settings.labelRes
import com.shinsaikou.ui.settings.settingsSectionBodyTag
import com.shinsaikou.util.Args
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/**
 * MyAnimeList on the device (phase 3 unit T2), from the Tracking section of Settings.
 *
 * [theTrackingSectionOffersMyAnimeListOnATestBase] runs in every suite. A test base makes
 * MyAnimeList configured in this debug build with the build's client id blank, so the section shows
 * and holds its row; the base is set before the activity launches and nothing is clicked, so nothing
 * is contacted. On a device composing tablet UI the section opens in the end pane beside the list.
 *
 * [theBrowserSignInFromTheTrackingRowThenAPushReachTheFake] needs the host's MyAnimeList fake
 * (`tools/bench/mal_fixture_server.py`) named by the `saikou.mal.base` runner argument, and logs a
 * `Skipped:` line and returns without it, reading the argument straight from the runner as
 * `GoogleRoundTripDeviceTest` does, since a strict gate run has no fake and must not fail for its
 * absence. With it: the row's Sign in opens the device's browser on the fake's authorization page,
 * the fake redirects the browser to the app's loopback on `LoopbackListener.TRACKER_PORT`, the app
 * exchanges the code and fetches the account; the shell brings the app back and the row shows the
 * account. Then a finished chapter goes through the one progress hook ([AppGraph.trackProgress]) and
 * the retry worker to the fake's list status endpoint, and the row stores what the fake answered.
 * Then the "replaced while waiting" case of phase 3 unit TQ on the real database: with the device's
 * network off, so the worker's network constraint holds the run, the next chapter is recorded, the
 * row is stored again by a plain insert (as a sync pull or the tracking sheet stores it), which
 * gives it a new id, and with the network back the worker sends that chapter to the row read by
 * manga and tracker. Then TQ's verifier finding 1, offline again: the chapter after it is recorded
 * against the fake's entry 1001, the pair is bound to entry [OTHER_ENTRY] by a plain insert (as a
 * bind to another search result or a sync pull stores it), and with the network back the worker
 * drops the push, so nothing is sent for either entry and the new row stays at chapter 0. The
 * host reads the fake's log for the PUTs and for any request naming [OTHER_ENTRY]. Screens are
 * captured to [SCREENS_DIR].
 *
 * The browser's first-run screens are the host's to clear before the run. Whatever happens, the test
 * turns the network back on, signs MyAnimeList out and deletes its entry and row at the end, and the
 * test base is put back.
 */
@RunWith(AndroidJUnit4::class)
class MyAnimeListSignInDeviceTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /**
     * Before the activity launches: the first launch's dialogs answered, as `MainActivitySmokeTest`
     * answers them, and the test base set, so the settings list and the Tracking section's view model
     * find MyAnimeList configured when they are built. Put back afterwards.
     */
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

    @Test
    fun theTrackingSectionOffersMyAnimeListOnATestBase() {
        assertTrue("a test base did not make MyAnimeList configured", tracker.isConfigured)
        val tablet = composesTabletUi()
        if (Args.expectTablet) assertTrue("saikou.expect.tablet is true, but the device is not composing tablet UI", tablet)

        openTrackingSection()

        composeRule.onNodeWithText(tracker.name).assertIsDisplayed()
        if (!tracker.isLoggedIn) composeRule.onNodeWithText(string(R.string.tracking_sign_in)).assertIsDisplayed().assertIsEnabled()
        if (tablet) {
            composeRule.onNodeWithTag(SETTINGS_TWO_PANE_TAG).assertIsDisplayed()
            // The list stays beside the pane, so its Tracking row is still there.
            sectionRow().assertIsDisplayed()
        }
        Log.i(LOG_TAG, "section: MyAnimeList row shown, tablet UI $tablet, signed in ${tracker.isLoggedIn}")
    }

    @Test
    fun theBrowserSignInFromTheTrackingRowThenAPushReachTheFake() {
        if (runnerBase() == null) {
            // Spelled out, because gate.sh's tablet step expects this line and a tools test reads it here.
            Log.i(LOG_TAG, "Skipped: theBrowserSignInFromTheTrackingRowThenAPushReachTheFake: no saikou.mal.base argument")
            return
        }
        shell("mkdir -p $SCREENS_DIR")
        runBlocking(Dispatchers.IO) {
            tracker.logout()
            removeEntry()
        }
        try {
            signInFromTheRow()
            val manga = pushAFinishedChapter()
            pushWhileTheRowIsReplacedOffline(manga)
            pushRecordedBeforeARebindOffline(manga)
        } finally {
            network(on = true)
            runBlocking(Dispatchers.IO) {
                removeEntry()
                tracker.logout()
            }
        }
    }

    private fun signInFromTheRow() {
        assertFalse("MyAnimeList is still signed in", tracker.isLoggedIn)
        openTrackingSection()
        composeRule.onNodeWithText(tracker.name).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.tracking_sign_in)).performClick()
        Log.i(LOG_TAG, "sign-in: Sign in tapped")

        // The browser is in front now, in its own task; the activity is stopped, so this polls the
        // tracker rather than the composition.
        val start = System.currentTimeMillis()
        var captured = false
        while (!(tracker.isLoggedIn && !tracker.isAuthExpired)) {
            val waited = System.currentTimeMillis() - start
            if (!captured && waited > BROWSER_SCREEN_AFTER_MILLIS) {
                screen("browser")
                captured = true
            }
            assertTrue("no sign-in ${SIGN_IN_WAIT_MILLIS / 1000} s after the browser opened", waited < SIGN_IN_WAIT_MILLIS)
            Thread.sleep(POLL_MILLIS)
        }
        Log.i(LOG_TAG, "sign-in: signed in as ${tracker.getDisplayUsername()} after ${System.currentTimeMillis() - start} ms")
        // The browser's page after the loopback answered, before the app comes back.
        Thread.sleep(POLL_MILLIS)
        screen("browser-after-callback")

        // The shell brings the app back with the launcher's intent, as the owner's tap on its icon
        // would: the task comes to the front with the activity the rule launched, and no new intent
        // reaches it (one would replace its intent, and the rule would lose track of it).
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${context.packageName}/${MainActivity::class.java.name}")
        val signedIn = context.getString(R.string.tracking_signed_in_as_format, FIXTURE_USER_NAME)
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodesWithText(signedIn).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(signedIn).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.tracking_sign_out)).assertIsDisplayed()
        assertTrue(
            "the row still waits for the browser",
            composeRule.onAllNodesWithText(string(R.string.tracking_waiting_for_browser)).fetchSemanticsNodes().isEmpty(),
        )
        screen("row-signed-in")
        Log.i(LOG_TAG, "sign-in: the row shows \"$signedIn\"")
    }

    /** The ordinary push: [FIRST_CHAPTER] through the hook and the worker. Returns the entry. */
    private fun pushAFinishedChapter(): Manga = runBlocking(Dispatchers.IO) {
        val manga = graph.mangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = SOURCE_ID, url = URL, title = TITLE, initialized = true)),
        ).single()
        graph.trackRepository.insert(track(manga.id))
        val track = row(manga.id)

        assertTrue("the hook would not record a push for a signed-in tracker", graph.trackProgress.wouldRecord(manga.id, FIRST_CHAPTER))
        assertEquals("tracks recorded", 1, graph.trackProgress.record(manga.id, FIRST_CHAPTER))
        Log.i(LOG_TAG, "push: entry ${manga.id} (row ${track.id}, MyAnimeList entry ${track.remoteId}) recorded at $FIRST_CHAPTER")

        val stored = awaitSent(manga.id, FIRST_CHAPTER)
        Log.i(
            LOG_TAG,
            "push: sent; the row (now id ${stored.id}) reads chapter ${stored.lastChapterRead} of ${stored.totalChapters}, " +
                "status ${stored.status}",
        )
        manga
    }

    /**
     * Phase 3 unit TQ's "replaced while waiting": [CHAPTER] recorded while the worker cannot send,
     * the row stored again by a plain insert, then the worker sends [CHAPTER] once it can.
     */
    private fun pushWhileTheRowIsReplacedOffline(manga: Manga): Unit = runBlocking(Dispatchers.IO) {
        network(on = false)
        val recordedFor = row(manga.id)
        assertEquals("tracks recorded", 1, graph.trackProgress.record(manga.id, CHAPTER))
        // Long enough for a run the constraint did not hold to have sent it.
        Thread.sleep(HOLD_MILLIS)
        val runs = graph.workManager.getWorkInfosForUniqueWork(WorkManagerDelayedTrackingScheduler.WORK_NAME).get()
        Log.i(LOG_TAG, "offline: recorded $CHAPTER for row ${recordedFor.id}; runs ${runs.map { it.state }}")
        assertTrue("the push was not held offline", queued(CHAPTER))
        assertEquals("the row moved while offline", FIRST_CHAPTER, row(manga.id).lastChapterRead, 0.0)

        // A plain insert of the bound pair, as a sync pull or the tracking sheet stores the row.
        graph.trackRepository.insert(recordedFor)
        val replaced = row(manga.id)
        assertNotEquals("storing the row again kept its id", recordedFor.id, replaced.id)
        Log.i(LOG_TAG, "offline: the row was stored again, id ${recordedFor.id} became ${replaced.id}")

        network(on = true)
        val stored = awaitSent(manga.id, CHAPTER)
        Log.i(
            LOG_TAG,
            "replaced: sent after the row was replaced; the row (now id ${stored.id}) reads chapter " +
                "${stored.lastChapterRead} of ${stored.totalChapters}, status ${stored.status}",
        )
    }

    /**
     * Phase 3 unit TQ's verifier finding 1: [REBIND_CHAPTER] recorded against [FIXTURE_ENTRY] while
     * the worker cannot send, the pair then bound to [OTHER_ENTRY] by a plain insert, and the worker,
     * once it runs, drops the push instead of sending a chapter of one entry to the other.
     */
    private fun pushRecordedBeforeARebindOffline(manga: Manga): Unit = runBlocking(Dispatchers.IO) {
        network(on = false)
        val recordedFor = row(manga.id)
        assertEquals("tracks recorded", 1, graph.trackProgress.record(manga.id, REBIND_CHAPTER))
        Thread.sleep(HOLD_MILLIS)
        assertTrue("the push was not held offline", queued(REBIND_CHAPTER))

        // A plain insert of the bound pair naming another entry, as a bind to another search result
        // or a sync pull carrying one from another device stores it.
        graph.trackRepository.insert(
            recordedFor.copy(
                remoteId = OTHER_ENTRY,
                title = OTHER_TITLE,
                lastChapterRead = 0.0,
                totalChapters = 0L,
                status = MyAnimeList.PLAN_TO_READ,
                remoteUrl = "https://myanimelist.net/manga/$OTHER_ENTRY",
            ),
        )
        val rebound = row(manga.id)
        assertEquals("the pair was not bound to the other entry", OTHER_ENTRY, rebound.remoteId)
        Log.i(
            LOG_TAG,
            "offline: recorded $REBIND_CHAPTER for entry ${recordedFor.remoteId} (row ${recordedFor.id}); " +
                "the pair is bound to entry ${rebound.remoteId} now (row ${rebound.id})",
        )

        network(on = true)
        val start = System.currentTimeMillis()
        while (true) {
            val runs = graph.workManager.getWorkInfosForUniqueWork(WorkManagerDelayedTrackingScheduler.WORK_NAME).get()
            val queued = queued(REBIND_CHAPTER)
            if (!queued && runs.isNotEmpty() && runs.all { it.state == WorkInfo.State.SUCCEEDED }) break
            assertTrue(
                "the push recorded before the rebind was not dropped within ${PUSH_WAIT_MILLIS / 1000} s " +
                    "(queued $queued, runs ${runs.map { "${it.state} attempts=${it.runAttemptCount}" }})",
                System.currentTimeMillis() - start < PUSH_WAIT_MILLIS,
            )
            Thread.sleep(POLL_MILLIS)
        }
        val after = row(manga.id)
        assertEquals("the rebound row changed entry", OTHER_ENTRY, after.remoteId)
        assertEquals("a chapter of the old entry reached the new one", 0.0, after.lastChapterRead, 0.0)
        Log.i(
            LOG_TAG,
            "rebound: the push was dropped after ${System.currentTimeMillis() - start} ms; the row (id ${after.id}) " +
                "names entry ${after.remoteId} at chapter ${after.lastChapterRead}",
        )
    }

    /** Waits for the queue to empty and the row, read by manga and tracker, to read [chapter]. */
    private suspend fun awaitSent(mangaId: Long, chapter: Double): Track {
        val start = System.currentTimeMillis()
        while (true) {
            val queued = queued(chapter)
            // By manga and tracker: storing the service's answer replaces the row under a new id
            // (`manga_sync`'s UNIQUE (manga_id, sync_id) ON CONFLICT REPLACE, as in upstream).
            val stored = graph.trackRepository.getTracksByMangaId(mangaId).singleOrNull { it.trackerId == TrackerIds.MYANIMELIST }
            if (!queued && stored != null && stored.lastChapterRead == chapter) {
                // What the fake answered: its chapter count from the entry, reading from the push.
                assertEquals(FIXTURE_CHAPTERS, stored.totalChapters)
                assertEquals(MyAnimeList.READING, stored.status)
                Log.i(LOG_TAG, "push: chapter $chapter sent after ${System.currentTimeMillis() - start} ms")
                return stored
            }
            assertTrue(
                "the push of chapter $chapter on entry $mangaId was not sent within ${PUSH_WAIT_MILLIS / 1000} s " +
                    "(queued $queued, row at ${stored?.lastChapterRead})",
                System.currentTimeMillis() - start < PUSH_WAIT_MILLIS,
            )
            Thread.sleep(POLL_MILLIS)
        }
    }

    private suspend fun row(mangaId: Long): Track =
        graph.trackRepository.getTracksByMangaId(mangaId).single { it.trackerId == TrackerIds.MYANIMELIST }

    /**
     * Whether a MyAnimeList push of [chapter] is queued. Read without the key's first half, so the
     * check means the same with the queue keyed by the row id, which the R29 proof builds.
     */
    private fun queued(chapter: Double): Boolean =
        graph.delayedTrackingStore.getItems().any { it.trackerId == TrackerIds.MYANIMELIST && it.chapterNumber == chapter }

    /**
     * Turns Wi-Fi and mobile data on or off through the shell, as `DelayedTrackingColdStartTest`'s
     * host procedure does, and waits until the device has, or has no, active network.
     */
    private fun network(on: Boolean) {
        val state = if (on) "enable" else "disable"
        shell("svc wifi $state")
        shell("svc data $state")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val start = System.currentTimeMillis()
        while ((connectivity.activeNetwork != null) != on) {
            assertTrue("the network did not turn ${if (on) "on" else "off"}", System.currentTimeMillis() - start < SHORT_WAIT_MILLIS)
            Thread.sleep(POLL_MILLIS)
        }
        Log.i(LOG_TAG, "network: ${if (on) "on" else "off"}")
    }

    private suspend fun removeEntry() {
        graph.mangaRepository.getMangaByUrlAndSourceId(URL, SOURCE_ID)?.let { manga ->
            graph.delayedTrackingStore.getItems().filter { it.mangaId == manga.id }.forEach {
                graph.delayedTrackingStore.remove(it.mangaId, it.trackerId, it.remoteId, it.chapterNumber)
            }
            graph.trackRepository.getTracksByMangaId(manga.id).filter { it.trackerId == TrackerIds.MYANIMELIST }.forEach {
                graph.trackRepository.deleteIfBoundTo(it.mangaId, it.trackerId, it.remoteId)
            }
        }
        graph.mangaRepository.deleteNonLibraryManga(listOf(SOURCE_ID), keepReadManga = false)
    }

    private fun openTrackingSection() {
        awaitLibrary()
        composeRule.onNode(hasText(string(R.string.tab_more)) and isSelectable()).performClick()
        val settings = hasText(string(R.string.label_settings)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(settings)
        composeRule.onNode(settings).performClick()
        sectionRow().performClick()
        composeRule.onNodeWithTag(settingsSectionBodyTag(SettingsSection.TRACKING)).assertIsDisplayed()
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

    private fun sectionRow() = composeRule.onNode(
        hasText(string(SettingsSection.TRACKING.labelRes)) and hasClickAction() and isSelectable().not(),
    )

    private fun composesTabletUi(): Boolean {
        val mode = graph.uiPreferences.tabletUiMode.get()
        val configuration = composeRule.activity.resources.configuration
        return mode.isTabletUi(configuration.smallestScreenWidthDp, configuration.orientation)
    }

    private fun track(mangaId: Long) = Track(
        id = 0L,
        mangaId = mangaId,
        trackerId = TrackerIds.MYANIMELIST,
        remoteId = FIXTURE_ENTRY,
        libraryId = null,
        title = TITLE,
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = MyAnimeList.PLAN_TO_READ,
        score = 0.0,
        remoteUrl = "https://myanimelist.net/manga/$FIXTURE_ENTRY",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun runnerBase(): String? =
        InstrumentationRegistry.getArguments().getString(MyAnimeListTestBaseSetting.EXTRA_MAL_BASE)?.takeIf { it.isNotBlank() }

    /** Runs [command] as the shell and waits for it to end; returns what it printed. */
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    private fun screen(name: String) {
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

        const val LOG_TAG = "MyAnimeListSignInDevice"

        /**
         * The base the section test sets when the runner names none: a discard port on the device's
         * own loopback. It makes MyAnimeList configured; nothing in that test sends it a request.
         */
        const val UNCONTACTED_BASE = "http://127.0.0.1:9"

        /** `mal_fixture_server.py`'s account and its entry 1001. */
        const val FIXTURE_USER_NAME = "fixture-reader"
        const val FIXTURE_ENTRY = 1001L
        const val FIXTURE_CHAPTERS = 12L

        /** An entry the fake does not have, so a request naming it would fail and show. */
        const val OTHER_ENTRY = 2002L
        const val OTHER_TITLE = "Another entry"

        /** A source id no extension has, one of the device suite's own (see `DelayedTrackingColdStartTest`'s). */
        const val SOURCE_ID = 9_000_000_000_000_000_008L
        const val URL = "/saikou-mal-sign-in/entry"
        const val TITLE = "MyAnimeList device check"
        const val FIRST_CHAPTER = 2.0
        const val CHAPTER = 3.0
        const val REBIND_CHAPTER = 4.0

        const val SCREENS_DIR = "/data/local/tmp/saikou-mal"
        const val SHORT_WAIT_MILLIS = 30_000L
        const val SIGN_IN_WAIT_MILLIS = 120_000L
        const val BROWSER_SCREEN_AFTER_MILLIS = 3_000L
        const val PUSH_WAIT_MILLIS = 90_000L
        const val HOLD_MILLIS = 5_000L
        const val POLL_MILLIS = 1_000L
    }
}
