package com.shinsaikou.tracking

import android.Manifest
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.tracking.myanimelist.MyAnimeListClient
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.settings.labelRes
import com.shinsaikou.ui.settings.settingsSectionBodyTag
import com.shinsaikou.ui.settings.trackerCompareRowTag
import com.shinsaikou.ui.tracking.COMPARE_AGAIN_TAG
import com.shinsaikou.ui.tracking.COMPARE_CHOICE_OK_TAG
import com.shinsaikou.ui.tracking.COMPARE_CONFIRM_APPLY_TAG
import com.shinsaikou.ui.tracking.COMPARE_SUMMARY_TAG
import com.shinsaikou.ui.tracking.compareChoiceTag
import com.shinsaikou.ui.tracking.compareRowTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.net.URLEncoder

/**
 * "Compare with MyAnimeList" on the device (phase 4 unit TC, the design's section 13), against the
 * host's MyAnimeList fake (`tools/bench/mal_fixture_server.py`, run with `--list-page-size 1`) named by
 * the `saikou.mal.base` runner argument. Without it the test logs a `Skipped:` line and returns, as
 * `TrackSheetDeviceTest` does; with it every MyAnimeList call goes to the fake with the fake's client
 * id, so nothing reaches MyAnimeList.
 *
 * Three library entries A, B and C with chapters 1 to 12 are seeded: A with 1 to 5 read and chapter 7
 * unread at page 4, B with 1 to 8 read, C with 1 to 4 read. MyAnimeList is signed in through the
 * fake's endpoints as `TrackSheetDeviceTest` signs in, and the three are bound to the fake's 1001, 1003
 * and 1004 through [AppGraph.addTracks], which pushes their progress and opens no sheet. Then the
 * website's edits come from a second session, a token of the test's own from the fake's token
 * endpoint: A set to 10, B to 2, C deleted from the list. The app's token and preferences are not
 * touched.
 *
 * The screen is opened from Settings, Tracking, and driven as the owner would: A marked as read, B's
 * number sent, C's tracking removed, then a second comparison whose entry changes on the website while
 * the list is shown. The host reads the fake's log for the requests in order (the host script's
 * check). Screens of each step go to [SCREENS_DIR].
 *
 * The emulator has no sync backend (asserted), so the comparison starts no sync. Whatever happens,
 * the three rows are deleted, the entries soft deleted, MyAnimeList signed out and the test base put
 * back.
 */
@RunWith(AndroidJUnit4::class)
class TrackerCompareDeviceTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /** As `TrackSheetDeviceTest`'s: the first launch's dialogs answered and the test base set. */
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

    private val mangaIds = mutableListOf<Long>()
    private lateinit var entryA: Manga
    private lateinit var entryB: Manga
    private lateinit var entryC: Manga

    /** The second session's access token, the website's stand-in. */
    private lateinit var website: String

    @After
    fun removeTheFixture() {
        runBlocking(Dispatchers.IO) {
            mangaIds.forEach { mangaId ->
                graph.trackRepository.getTracksByMangaId(mangaId).filter { it.trackerId == TrackerIds.MYANIMELIST }.forEach {
                    graph.trackRepository.deleteIfBoundTo(it.mangaId, it.trackerId, it.remoteId)
                }
                graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis())
            }
            tracker.logout()
        }
    }

    @Test
    fun theComparisonShowsTheWebsitesEditsAndAppliesTheOwnersChoices() {
        if (runnerBase() == null) {
            // Spelled out, because gate.sh's tablet step expects this line and a tools test reads it here.
            Log.i(LOG_TAG, "Skipped: theComparisonShowsTheWebsitesEditsAndAppliesTheOwnersChoices: no saikou.mal.base argument")
            return
        }
        assertEquals("a sync backend is configured on this device", "", SyncPreferences(graph.preferenceStore).backendId.get())
        shell("rm -rf $SCREENS_DIR")
        shell("mkdir -p $SCREENS_DIR")
        try {
            compareAndApply()
        } catch (e: Throwable) {
            // What was on screen when it failed, and every root's semantics, for the host to read.
            screen("failure")
            composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                composeRule.onAllNodes(isRoot())[index].printToLog(LOG_TAG)
            }
            throw e
        }
    }

    private fun compareAndApply() {
        // 1. The library, the sign-in and the binds.
        runBlocking(Dispatchers.IO) {
            tracker.logout()
            removeLeftoverRows()
        }
        entryA = seed("a", read = 5, pageOfSeven = 4L)
        entryB = seed("b", read = 8)
        entryC = seed("c", read = 4)
        signIn()
        bind(entryA, ENTRY_A)
        bind(entryB, ENTRY_B)
        bind(entryC, ENTRY_C)

        // 2. The website's edits.
        website = secondSession()
        setOnWebsite(ENTRY_A, 10)
        setOnWebsite(ENTRY_B, 2)
        deleteOnWebsite(ENTRY_C)

        // 3. The comparison, every row on don't change and Apply off.
        awaitLibrary()
        openTheComparison()
        awaitRow(entryA, "In the app: chapter 5")
        row(entryA, "On MyAnimeList: chapter 10 of 12").assertExists()
        row(entryB, "In the app: chapter 8").assertExists()
        row(entryB, "On MyAnimeList: chapter 2").assertExists()
        row(entryC, "In the app: chapter 4").assertExists()
        assertTrue("C shows a list number", composeRule.onAllNodes(rowOf(entryC) and hasText("On MyAnimeList", substring = true)).fetchSemanticsNodes().isEmpty())
        row(entryA, string(R.string.compare_dont_change)).assertExists()
        row(entryB, string(R.string.compare_dont_change)).assertExists()
        row(entryC, context.getString(R.string.compare_not_on_list_dont_change_format, NAME)).assertExists()
        assertOrder(
            header(context.getString(R.string.compare_section_remote_ahead_format, NAME)),
            rowOf(entryA),
            header(string(R.string.compare_section_app_ahead)),
            rowOf(entryB),
            header(context.getString(R.string.compare_section_not_on_list_format, NAME)),
            rowOf(entryC),
        )
        applyButton().assertIsNotEnabled()
        screen("compared")
        Log.i(LOG_TAG, "compare: A ahead on MyAnimeList (5 against 10), B ahead in the app (8 against 2), C not on the list")

        // 4. The choices, applied.
        choose(entryA, CompareChoice.MARK_READ)
        choose(entryB, CompareChoice.UPDATE_REMOTE)
        choose(entryC, CompareChoice.REMOVE_TRACKING)
        applyButton().assertIsEnabled()
        screen("chosen")
        apply(context.getString(R.string.compare_applied_format, 3, 0, 0))
        val chapters = runBlocking(Dispatchers.IO) { graph.chapterRepository.getChapterByMangaId(entryA.id) }
        assertEquals(
            "A's read chapters",
            (1..10).map { it.toDouble() },
            chapters.filter { it.read }.map { it.chapterNumber }.sorted(),
        )
        assertEquals("chapter 7's page", 4L, chapters.single { it.chapterNumber == 7.0 }.lastPageRead)
        assertTrue("marking wrote history", runBlocking(Dispatchers.IO) { graph.getHistory.await(entryA.id) }.isEmpty())
        assertEquals("B's row", 8.0, trackOf(entryB)?.lastChapterRead)
        assertEquals("C's row", null, trackOf(entryC))
        screen("applied")
        Log.i(LOG_TAG, "apply: A 1 to 10 read with chapter 7 at page 4 and no history, B's row at 8, C's row gone")

        // 5. Compared again, and changed on the website while shown.
        setOnWebsite(ENTRY_B, 3)
        composeRule.onNodeWithTag(COMPARE_AGAIN_TAG).performClick()
        awaitRow(entryB, "On MyAnimeList: chapter 3")
        row(entryB, "In the app: chapter 8").assertExists()
        assertOrder(header(string(R.string.compare_section_app_ahead)), rowOf(entryB))
        assertTrue("A is still shown", composeRule.onAllNodes(rowOf(entryA)).fetchSemanticsNodes().isEmpty())
        screen("compared-again")
        setOnWebsite(ENTRY_B, 5)
        choose(entryB, CompareChoice.UPDATE_REMOTE)
        apply(context.getString(R.string.compare_applied_format, 0, 1, 0))
        row(entryB, string(R.string.compare_result_changed)).assertExists()
        screen("changed-since")
        Log.i(LOG_TAG, "compare again: B changed on the website while shown, and Apply sent nothing")
    }

    /** One favorite Local source entry with chapters 1 to 12, 1 to [read] read, as `TrackSheetDeviceTest` seeds one. */
    private fun seed(name: String, read: Int, pageOfSeven: Long = 0L): Manga = runBlocking(Dispatchers.IO) {
        val title = "$FIXTURE_PREFIX$name-${System.nanoTime()}"
        val manga = graph.mangaRepository.insertNetworkManga(
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
        ).single()
        mangaIds += manga.id
        val now = System.currentTimeMillis()
        graph.chapterRepository.addAll(
            (1..CHAPTERS).map { number ->
                Chapter.create().copy(
                    mangaId = manga.id,
                    url = "$title/$number",
                    name = "Chapter $number",
                    chapterNumber = number.toDouble(),
                    read = number <= read,
                    lastPageRead = if (number == 7) pageOfSeven else 0L,
                    sourceOrder = (CHAPTERS - number).toLong(),
                    dateFetch = now,
                    dateUpload = now,
                )
            },
        )
        manga
    }

    /**
     * Any MyAnimeList row a run that died left behind: MyAnimeList is signed out when this test starts,
     * so none belongs to the owner, and one would add a row and its requests to the comparison.
     */
    private suspend fun removeLeftoverRows() {
        graph.trackRepository.getTracksAsFlow().first().filter { it.trackerId == TrackerIds.MYANIMELIST }.forEach {
            Log.i(LOG_TAG, "setup: removed a leftover row of manga ${it.mangaId}")
            graph.trackRepository.deleteIfBoundTo(it.mangaId, it.trackerId, it.remoteId)
        }
    }

    /** As `TrackSheetDeviceTest.signIn`: the fake's redirect fetched, its code exchanged by the tracker. */
    private fun signIn(): Unit = runBlocking(Dispatchers.IO) {
        val pkce = Pkce.generate()
        val code = authorizationCode(pkce)
        tracker.completeSignIn(code, REDIRECT_URI, pkce)
        assertTrue("MyAnimeList is not signed in", tracker.isLoggedIn && !tracker.isAuthExpired)
        Log.i(LOG_TAG, "sign-in: signed in as ${tracker.getDisplayUsername()}")
    }

    /** [manga] bound to [remoteId] from the fake's search results, as the sheet's Track does. */
    private fun bind(manga: Manga, remoteId: Long): Unit = runBlocking(Dispatchers.IO) {
        val result = tracker.search(FIXTURE_QUERY).single { it.remoteId == remoteId }
        val bound = graph.addTracks.bind(tracker, result, manga.id)
        Log.i(LOG_TAG, "bind: manga ${manga.id} to entry ${bound.remoteId} at chapter ${bound.lastChapterRead}")
    }

    /**
     * A token of the test's own, as another app of the fake's client would get one: the authorization
     * redirect, then its own POST to the token endpoint with the fake's public client id.
     */
    private fun secondSession(): String = runBlocking(Dispatchers.IO) {
        val pkce = Pkce.generate()
        val code = authorizationCode(pkce)
        val form = listOf(
            "client_id" to MyAnimeListClient.TEST_BASE_CLIENT_ID,
            "code" to code,
            "code_verifier" to pkce.verifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to REDIRECT_URI,
        )
        val (status, body) = call("POST", "/v1/oauth2/token", form, bearer = null)
        assertEquals("the fake's token answer", HttpURLConnection.HTTP_OK, status)
        Log.i(LOG_TAG, "website: a second session has its own token")
        JSONObject(body).getString("access_token")
    }

    private fun setOnWebsite(entry: Long, chapter: Int): Unit = runBlocking(Dispatchers.IO) {
        val (status, _) = call("PUT", "/v2/manga/$entry/my_list_status", listOf("num_chapters_read" to "$chapter"), website)
        assertEquals("the website's edit of $entry", HttpURLConnection.HTTP_OK, status)
        Log.i(LOG_TAG, "website: entry $entry set to chapter $chapter")
    }

    private fun deleteOnWebsite(entry: Long): Unit = runBlocking(Dispatchers.IO) {
        val (status, _) = call("DELETE", "/v2/manga/$entry/my_list_status", null, website)
        assertEquals("the website's delete of $entry", HttpURLConnection.HTTP_OK, status)
        Log.i(LOG_TAG, "website: entry $entry deleted from the list")
    }

    /** The code in the fake's redirect for [pkce], its authorization URL fetched without following it. */
    private fun authorizationCode(pkce: Pkce): String {
        val url = tracker.authorizationUrl(REDIRECT_URI, "tracker-compare-${System.nanoTime()}", pkce)
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
        return code!!
    }

    /** One request to the fake at [path], with [form] encoded as its body; its status and body. */
    private fun call(method: String, path: String, form: List<Pair<String, String>>?, bearer: String?): Pair<Int, String> {
        val connection = URL(URL(runnerBase()!!), path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (form != null) {
                val body = form.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                }.toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    /** As `MyAnimeListSignInDeviceTest.openTrackingSection`, then the section's compare row. */
    private fun openTheComparison() {
        composeRule.onNode(hasText(string(R.string.tab_more)) and isSelectable()).performClick()
        val settings = hasText(string(R.string.label_settings)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(settings)
        composeRule.onNode(settings).performClick()
        composeRule.onNode(
            hasText(string(SettingsSection.TRACKING.labelRes)) and hasClickAction() and isSelectable().not(),
        ).performClick()
        composeRule.onNodeWithTag(settingsSectionBodyTag(SettingsSection.TRACKING)).assertExists()
        val compareRow = hasTestTag(trackerCompareRowTag(TrackerIds.MYANIMELIST))
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) { composeRule.onAllNodes(compareRow).fetchSemanticsNodes().isNotEmpty() }
        screen("settings")
        composeRule.onNode(compareRow).performClick()
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(COMPARE_SUMMARY_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The row's dialog, [choice] picked, OK. */
    private fun choose(manga: Manga, choice: CompareChoice) {
        composeRule.onNode(rowOf(manga)).performClick()
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(compareChoiceTag(choice)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(compareChoiceTag(choice)).performClick()
        screen("choice-${manga.id}-$choice")
        composeRule.onNodeWithTag(COMPARE_CHOICE_OK_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) { composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** Apply, its confirmation, then the snackbar that reads [result]. */
    private fun apply(result: String) {
        applyButton().performClick()
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(COMPARE_CONFIRM_APPLY_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        screen("confirm-${System.nanoTime()}")
        composeRule.onNodeWithTag(COMPARE_CONFIRM_APPLY_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) { composeRule.onAllNodesWithText(result).fetchSemanticsNodes().isNotEmpty() }
        Log.i(LOG_TAG, "apply: the snackbar reads \"$result\"")
        // The snackbar lies over the Apply button until it times out, and would take the next tap.
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) { composeRule.onAllNodesWithText(result).fetchSemanticsNodes().isEmpty() }
    }

    private fun applyButton(): SemanticsNodeInteraction =
        composeRule.onNode(hasText(string(R.string.action_apply)) and hasClickAction() and hasTestTag(COMPARE_CONFIRM_APPLY_TAG).not())

    private fun rowOf(manga: Manga): SemanticsMatcher = hasTestTag(compareRowTag(manga.id))

    private fun row(manga: Manga, text: String): SemanticsNodeInteraction = composeRule.onNode(rowOf(manga) and hasText(text))

    private fun awaitRow(manga: Manga, text: String) {
        composeRule.waitUntil(timeoutMillis = SHORT_WAIT_MILLIS) {
            composeRule.onAllNodes(rowOf(manga) and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun header(text: String): SemanticsMatcher = hasText(text) and hasClickAction().not()

    /** Each of [matchers] shown below the one before it: a section's header, then its rows. */
    private fun assertOrder(vararg matchers: SemanticsMatcher) {
        val tops = matchers.map { composeRule.onNode(it).fetchSemanticsNode().boundsInRoot.top }
        tops.zipWithNext().forEachIndexed { index, (above, below) ->
            assertTrue("item ${index + 1} of the order is not above item ${index + 2}: $tops", above < below)
        }
    }

    private fun trackOf(manga: Manga): Track? = runBlocking(Dispatchers.IO) {
        graph.trackRepository.getTracksByMangaId(manga.id).singleOrNull { it.trackerId == TrackerIds.MYANIMELIST }
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

        const val LOG_TAG = "TrackerCompareDevice"
        const val NAME = "MyAnimeList"

        /** As `TrackSheetDeviceTest.UNCONTACTED_BASE`: configured, never sent a request. */
        const val UNCONTACTED_BASE = "http://127.0.0.1:9"

        /** A loopback redirect the fake accepts; nothing listens on it, since the redirect is never followed. */
        val REDIRECT_URI = "http://127.0.0.1:${LoopbackListener.TRACKER_PORT}/"

        /** `mal_fixture_server.py`'s catalogue: its manga 1001, 1003 and 1004, all found by this query. */
        const val FIXTURE_QUERY = "Fixture"
        const val ENTRY_A = 1001L
        const val ENTRY_B = 1003L
        const val ENTRY_C = 1004L

        const val CHAPTERS = 12
        const val FIXTURE_PREFIX = "saikou-tracker-compare-"
        const val LOCAL_SOURCE_ID = 0L

        const val SCREENS_DIR = "/data/local/tmp/saikou-tracker-compare"
        const val SHORT_WAIT_MILLIS = 30_000L
    }
}
