package com.shinsaikou.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.chapter.VolumeRecognition
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.manga.model.withReadingMode
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.reader.ReaderActivity
import com.shinsaikou.reader.ReaderViewModel
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.ui.browse.FixtureStore
import com.shinsaikou.ui.browse.detail.EXTENSION_DETAIL_TWO_PANE_TAG
import com.shinsaikou.ui.library.LIBRARY_DETAIL_PANE_TAG
import com.shinsaikou.ui.library.LIBRARY_TWO_PANE_TAG
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import com.shinsaikou.ui.manga.MANGA_OVERFLOW_TAG
import com.shinsaikou.ui.manga.MANGA_TWO_PANE_TAG
import com.shinsaikou.ui.settings.labelRes
import com.shinsaikou.ui.settings.settingsSectionBodyTag
import com.shinsaikou.util.Args
import eu.kanade.tachiyomi.ui.security.UnlockActivity
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaLocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The two pane layouts, on the device that decides whether there are two of them.
 *
 * Of the four screens that split, only Settings had device proof, and Settings is the one that
 * needs no data: its rows exist on any install. The other three do not, which is why they rested on
 * code reading. This class supplies the data. It writes one favorite entry with one chapter
 * straight through the repositories, which is enough to make the library draw a cover, the manga
 * detail screen reach its Success state and the library's own detail pane have something to hold.
 *
 * The extension pane is the one that cannot be seeded: an installed extension is a signed package
 * this suite has no way to conjure, so that test looks for one and returns when the device has
 * none. It asserts on the owner's device and on any machine that has run the extension host suite.
 * That early return is state, not device shape, so ruling R28's protection for a missing fixture
 * does not cover it: the test would still report a pass having asserted nothing. Rather than pass
 * silently, it writes a line to logcat naming exactly why the assertion did not run, so the skip is
 * visible in the run's own output instead of only in this comment.
 *
 * Every test that needs two panes reads the production breakpoint rather than a device name, and
 * returns on a phone. Two of them are the phone's half of the same decision and return on a tablet,
 * so whichever device runs this class, the pair it runs asserts something. The gate's tablet step
 * passes `saikou.expect.tablet=true`, and under it the returns for one pane fail instead (R51): a
 * tablet AVD left holding a one pane preference would otherwise pass this class having asserted
 * none of its two pane half.
 *
 * Every early return in this class writes a distinct logcat line before it returns, so a passing
 * result never on its own means every test asserted: the log is what tells a reader which of them
 * actually exercised anything on the device the run used.
 *
 * The library's back chain (ruling R53) runs selection, then pane, then search, then the tab. Three
 * tests pin the cases the pane adds to it: a chapter selection inside the pane, a grid selection
 * made with the pane open, and a tab switch that has to bring the pane back. Like the rest of the
 * tablet half they assert only on a device that composes two panes.
 *
 * One test is here for the manga screen rather than for its panes (R99 carry-over 2): with "After
 * manually marked as read" on, the row's swipe to the right (its mark read since plan 2.6 took the
 * row's buttons away, unit ID3) deletes a downloaded chapter, folder and manifest row. It seeds its
 * own entry, since the class's fixture is on the Local source, whose chapters are never deleted, and
 * opens it with the library tap on either shape, so it asserts on phones and tablets alike and has
 * no early return. The selection bar's mark read and "mark previous as read" are driven by their own
 * test below.
 *
 * Two more are the chapter row's swipes (phase 4 unit CS), on either shape with no early return: with
 * both swipe keys unset, a swipe to the right marks read and one to the left bookmarks, in the route's
 * two panes on a tablet, and the row then shows the "Bookmarked" mark and no "Mark as read" of its
 * own; and with the left swipe set to Download, a swipe deletes a downloaded chapter's folder and
 * manifest row with no dialog.
 *
 * One more is the manga screen's scanlator ranking (phase 4 unit SC), with an entry of its own on
 * another missing source: the dialog's "Move up" ranks the second group, the screen's Continue
 * reading opens that group's copy, and a read to the end deletes only chapters read. It too opens
 * its entry with the library tap and asserts on both shapes. A second on the same missing source
 * adds a group's chapter while the dialog is open and checks the owner's move survives it (review 3).
 */
@RunWith(AndroidJUnit4::class)
class TwoPaneLayoutTest {

    /** Outermost, so the grant is in place before the activity launches. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /**
     * The battery exemption dialog would otherwise sit over the activity for the whole run,
     * swallowing every tap below. See `MainActivitySmokeTest`, which seeds it the same way and for
     * the same reason; `RuleContainer` runs a lower order first, so this lands before the launch.
     * The crash log is marked exported here too, so a crash some earlier run left on the device
     * cannot put its banner above the screens this class asserts on (ruling R59).
     */
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

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /**
     * The title carries the run's start time. A fixed one would collide with the row a previous run
     * left behind on the source and url index, and the second run would fail on an insert rather
     * than on anything it meant to test. It is also what the library search is given, so it has to
     * match this entry and nothing the owner owns.
     */
    private lateinit var title: String
    private var mangaId: Long = -1L

    /** What the rule launched the activity with; see [handTheLaunchIntentBack]. */
    private lateinit var launchIntent: Intent

    @Before
    fun seedOneLibraryEntry() {
        title = "$FIXTURE_PREFIX${System.nanoTime()}"
        runBlocking {
            val stored = graph.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = FIXTURE_SOURCE_ID,
                        url = title,
                        title = title,
                        favorite = true,
                        // Initialized with a chapter already on the row, so the detail screen has
                        // no reason to start the one automatic fetch it makes for an entry that
                        // has never been fetched. A fetch here would reach a source that has no
                        // directory behind it and answer with a snackbar over the pane.
                        initialized = true,
                        dateAdded = System.currentTimeMillis(),
                    ),
                ),
            ).single()
            mangaId = stored.id
            graph.chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = mangaId,
                        url = "$title/1",
                        name = FIXTURE_CHAPTER,
                        chapterNumber = 1.0,
                        sourceOrder = 0,
                        dateFetch = System.currentTimeMillis(),
                        dateUpload = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        awaitLibraryScreen()
        launchIntent = composeRule.activity.intent
    }

    /**
     * Two tests send the activity an intent of the app's own, and `MainActivity.onNewIntent` calls
     * `setIntent` with it, as it must. `ActivityScenario` recognises its activity by comparing
     * `getIntent()` with the intent it launched, so from then on it ignores every lifecycle event
     * ("ignored because the intent does not match" in logcat), never sees the activity destroyed,
     * and fails the test in teardown after its assertions have passed. Measured on
     * `saikou_tablet_4g`, where these tests are the first to run past their early return. Handing
     * the original back before the rule closes the scenario changes nothing the app reads: the
     * activity only reads its intent in `onCreate`, on a fresh launch.
     */
    @After
    fun handTheLaunchIntentBack() {
        if (::launchIntent.isInitialized) {
            composeRule.runOnUiThread { composeRule.activity.intent = launchIntent }
        }
    }

    /**
     * Soft deleted rather than removed, which is the only delete the repository offers per entry;
     * the chapter goes with it through the foreign key. The row stays in the database and out of
     * the library, so nothing this test added is visible to the owner afterwards.
     */
    @After
    fun removeTheFixture() {
        if (mangaId > 0) {
            runBlocking { graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis()) }
        }
    }

    @Test
    fun theLibraryOpensTheEntryInItsPaneOnATablet() {
        if (!composesTabletUi()) {
            notComposingTabletUi(
                "theLibraryOpensTheEntryInItsPaneOnATablet: device is not composing tablet UI, " +
                    "the pane assertions were not run.",
            )
            return
        }

        composeRule.onNodeWithTag(LIBRARY_TWO_PANE_TAG).assertIsDisplayed()
        // The resting state first: a pane that already held a detail screen would make the tap
        // below prove nothing about the tap.
        composeRule.onNodeWithTag(LIBRARY_DETAIL_PANE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.library_detail_pane_empty)).assertIsDisplayed()

        findFixtureCover().performClick()

        // The overflow button belongs to the manga detail screen and to nothing else on this
        // screen, so it is the evidence that the pane is holding the real detail screen rather
        // than a title the grid was already showing.
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(MANGA_OVERFLOW_TAG).assertIsDisplayed()
        // The list stayed. This is the whole difference from the phone, where the same tap pushes
        // the detail screen over the library.
        composeRule.onNodeWithTag(LIBRARY_TWO_PANE_TAG).assertIsDisplayed()
        composeRule.onNode(librarySearchBar()).assertIsDisplayed()

        // Back closes the pane rather than leaving the tab (ruling R53), and leaves the search the
        // cover was found through: the results are still there to pick another one from.
        pressBack()
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(string(R.string.library_detail_pane_empty)).assertIsDisplayed()
        composeRule.onNode(librarySearchBar()).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNode(fixtureCover()).assertIsDisplayed()

        // A second back closes the search, as it does on History, and still leaves the tab alone.
        pressBack()
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNode(libraryTitle()).assertIsDisplayed()
    }

    /**
     * The pane's detail screen owns its chapter selection and registers its handler after the
     * library's, so back clears that selection and nothing else: the pane stays open on the entry.
     */
    @Test
    fun backClearsAChapterSelectionInThePaneAndKeepsThePaneOnATablet() {
        if (!composesTabletUi()) {
            notComposingTabletUi(
                "backClearsAChapterSelectionInThePaneAndKeepsThePaneOnATablet: device is not " +
                    "composing tablet UI, the pane selection assertions were not run.",
            )
            return
        }

        openFixtureInPane()
        val chapter = hasText(FIXTURE_CHAPTER) and hasClickAction()
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(chapter).fetchSemanticsNodes().isNotEmpty()
        }
        // Pressed near the start edge, on the title, the way the other selection tests press: the
        // row's end is its download control on any entry but the Local one this fixture uses.
        composeRule.onNode(chapter).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
        // The detail screen swaps its bar for the selection bar, which has no overflow.
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).assertCountEquals(0)

        pressBack()

        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(selectedCount(1)).assertCountEquals(0)
        composeRule.onNodeWithTag(MANGA_OVERFLOW_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.library_detail_pane_empty)).assertCountEquals(0)
        composeRule.onNode(librarySearchBar()).assertIsDisplayed()
    }

    /**
     * A grid selection made while the pane is open is the newer state, so back clears it first
     * and leaves the pane; only the next back closes the pane.
     */
    @Test
    fun backClearsAGridSelectionBeforeThePaneOnATablet() {
        if (!composesTabletUi()) {
            notComposingTabletUi(
                "backClearsAGridSelectionBeforeThePaneOnATablet: device is not composing tablet UI, " +
                    "the grid selection assertions were not run.",
            )
            return
        }

        openFixtureInPane()
        composeRule.onNode(fixtureCover()).performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isNotEmpty()
        }
        // Selection mode leaves the pane alone.
        composeRule.onNodeWithTag(MANGA_OVERFLOW_TAG).assertIsDisplayed()

        pressBack()

        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNode(librarySearchBar()).assertIsDisplayed()
        composeRule.onNodeWithTag(MANGA_OVERFLOW_TAG).assertIsDisplayed()

        pressBack()

        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(string(R.string.library_detail_pane_empty)).assertIsDisplayed()
    }

    /**
     * A tab switch pops nothing: the library is the start destination and its saved state, the
     * pane's entry among it, comes back with it. So returning to the tab returns to the entry.
     */
    @Test
    fun thePaneSurvivesATabSwitchAndReturnOnATablet() {
        if (!composesTabletUi()) {
            notComposingTabletUi(
                "thePaneSurvivesATabSwitchAndReturnOnATablet: device is not composing tablet UI, " +
                    "the tab switch assertions were not run.",
            )
            return
        }

        openFixtureInPane()
        // The two panes are up before the switch, so their absence on Updates is the switch's.
        composeRule.onNodeWithTag(LIBRARY_TWO_PANE_TAG).assertIsDisplayed()

        tabItem(R.string.tab_updates).performClick()
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(LIBRARY_TWO_PANE_TAG).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).assertCountEquals(0)

        tabItem(R.string.tab_library).performClick()

        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LIBRARY_TWO_PANE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(MANGA_OVERFLOW_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.library_detail_pane_empty)).assertCountEquals(0)
    }

    @Test
    fun theLibraryPushesTheEntryOntoItsOwnScreenOnAPhone() {
        if (composesTabletUi()) {
            skip(
                "theLibraryPushesTheEntryOntoItsOwnScreenOnAPhone: device is composing tablet UI, " +
                    "the phone push assertion was not run.",
            )
            return
        }

        composeRule.onAllNodesWithTag(LIBRARY_TWO_PANE_TAG).assertCountEquals(0)

        // The search bar holds the title before the tap, so its absence after is the push's.
        val cover = findFixtureCover()
        composeRule.onNode(librarySearchBar()).assertIsDisplayed()
        cover.performClick()

        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // The library is gone rather than beside it: its bar, which is the screen, not the tab of
        // the same name, which is selectable and stays. The search that found the cover holds the
        // bar, so the bar is the search field, and its title is checked as well.
        composeRule.onAllNodes(librarySearchBar()).assertCountEquals(0)
        composeRule.onAllNodes(libraryTitle()).assertCountEquals(0)
    }

    @Test
    fun theMangaDetailRouteSplitsIntoTwoPanesOnATablet() {
        if (!composesTabletUi()) {
            notComposingTabletUi(
                "theMangaDetailRouteSplitsIntoTwoPanesOnATablet: device is not composing tablet UI, " +
                    "the two pane route assertion was not run.",
            )
            return
        }

        // The entry intent rather than a tap on the library, because on a tablet a tap on the
        // library opens the pane and never the route. This is the app's own deep link, the one a
        // launcher shortcut and a notification use.
        openEntryByIntent()

        composeRule.onNodeWithTag(MANGA_TWO_PANE_TAG).assertIsDisplayed()
    }

    /**
     * The extension pane cannot be seeded like the other three: an installed extension is a signed
     * package this suite has no way to conjure. When the device has none, the test does not fail
     * and does not fail silently either. It logs why the assertion did not run and returns, so the
     * skip is visible in the run's own output rather than only in a comment. It asserts on the
     * owner's device and on any machine that has already run the extension host suite.
     */
    @Test
    fun theExtensionDetailSplitsIntoTwoPanesOnATablet() {
        if (!composesTabletUi()) {
            notComposingTabletUi(
                "theExtensionDetailSplitsIntoTwoPanesOnATablet: device is not composing tablet UI, " +
                    "no two panes to assert on.",
            )
            return
        }
        val installed = graph.extensionManager.installedExtensions.value.firstOrNull()
        if (installed == null) {
            skip(
                "theExtensionDetailSplitsIntoTwoPanesOnATablet: no extension installed at test " +
                    "time, extension detail two pane layout was not exercised.",
            )
            return
        }
        // The Extensions page fetches every configured index while nothing is available, so a store
        // other than the browse fixture's keeps it closed (phase 5 unit TG2, SourcesFilterDeviceTest's guard).
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            skip(
                "theExtensionDetailSplitsIntoTwoPanesOnATablet: an extension store other than the browse fixture " +
                    "store is configured, Browse was not opened so that nothing is fetched",
            )
            return
        }

        context.startActivity(MainIntents.newIntent(context, MainDestination.EXTENSIONS))
        val row = hasText(installed.name) and hasClickAction()
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.browse_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(row)
        composeRule.onNode(row).performClick()

        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(EXTENSION_DETAIL_TWO_PANE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(EXTENSION_DETAIL_TWO_PANE_TAG).assertIsDisplayed()
    }

    /**
     * R99 carry-over 2: the manga screen's own mark read on a downloaded chapter, with "After
     * manually marked as read" on, deletes the chapter's folder and manifest row. Device test 3 in
     * `DownloadedChapterReaderTest` makes the same `SetReadStatus` call from the graph; this one
     * swipes. The row's "Mark as read" button is gone (plan 2.6, unit ID3), so the mark is the swipe
     * to the right with both swipe keys unset, upstream's default, which reaches the same `markRead`.
     *
     * The entry is this test's own, on a source id no extension carries: the class's fixture is on
     * the Local source, which `ReadChapterCleanupRules.removable` never deletes from. It is initialized
     * with one chapter, so the screen starts no fetch against the missing source and draws exactly
     * one row. It is opened by the library tap on both shapes: a phone pushes the screen, a tablet
     * opens it in the pane, and both end on the screen's overflow.
     *
     * The two polls are ordered so a failure names its half: first the swipe reached `SetReadStatus`
     * (the row reads read), then the binding deleted. The four delete keys and the two swipe keys
     * are put back, and the entry, its manifest row and its folder removed, whatever the outcome.
     */
    @Test
    fun theMangaScreensSwipeToMarkReadDeletesADownloadedChapterWithAfterManuallyMarkedAsReadOn() {
        assertNull("the mark read fixture's source id is installed", graph.sourceManager.get(MARK_READ_SOURCE_ID))
        val preferences = graph.downloadPreferences
        val oldSlot = preferences.removeAfterReadSlots.get()
        val oldMarkedAsRead = preferences.removeAfterMarkedAsRead.get()
        val oldBookmarked = preferences.removeBookmarkedChapters.get()
        val oldExcluded = preferences.removeExcludeCategories.get()
        val library = graph.libraryPreferences
        val keptStart = library.swipeToStartAction.takeIf { it.isSet() }?.get()
        val keptEnd = library.swipeToEndAction.takeIf { it.isSet() }?.get()
        val entryTitle = "$MARK_READ_PREFIX${System.nanoTime()}"
        var entryId = -1L
        var chapterId = -1L
        try {
            library.swipeToStartAction.delete()
            library.swipeToEndAction.delete()
            preferences.removeAfterReadSlots.set(DELETE_AFTER_READING_DISABLED)
            preferences.removeAfterMarkedAsRead.set(true)
            preferences.removeBookmarkedChapters.set(false)
            preferences.removeExcludeCategories.set(emptySet())

            val chapterDir = File(graph.downloadRoot.mangaDir(MARK_READ_SOURCE_DIR, entryTitle), FIXTURE_CHAPTER)
            runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = MARK_READ_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                entryId = manga.id
                val chapter = graph.chapterRepository.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = entryId,
                            url = "/fixture/$entryTitle/1",
                            name = FIXTURE_CHAPTER,
                            chapterNumber = 1.0,
                            sourceOrder = 0,
                            dateFetch = System.currentTimeMillis(),
                            dateUpload = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                chapterId = chapter.id

                assertTrue("could not create the chapter folder", chapterDir.mkdirs())
                val page = File(chapterDir, "001.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
                graph.downloadManifestRepository.recordDownload(
                    DownloadManifestEntry(
                        id = 0,
                        chapterId = chapterId,
                        mangaId = entryId,
                        sourceId = MARK_READ_SOURCE_ID,
                        mangaTitle = entryTitle,
                        mangaUrl = manga.url,
                        chapterUrl = chapter.url,
                        chapterName = chapter.name,
                        scanlator = null,
                        chapterDirName = FIXTURE_CHAPTER,
                        pageCount = 1L,
                        sizeBytes = page.length(),
                        dateDownloaded = System.currentTimeMillis(),
                    ),
                )
            }

            findFixtureCover(entryTitle).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
            }

            // Preconditions: the screen draws the chapter unread (its dot) and downloaded (its
            // control), and the files are there.
            val row = hasText(FIXTURE_CHAPTER) and hasClickAction()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(row and hasContentDescription(string(R.string.chapter_unread)))
                    .fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithContentDescription(string(R.string.chapter_downloaded))
                        .fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse("the chapter was read before the swipe", runBlocking { readOf(chapterId) } == true)
            assertTrue("the chapter folder is missing before the swipe", chapterDir.isDirectory)
            assertNotNull("the manifest row is missing before the swipe", manifestRowOf(chapterId))

            // From near the start edge, on the title, to the end edge: the right swipe's mark read.
            composeRule.onNode(row).performTouchInput { swipeRight(startX = left + width * 0.05f, endX = right) }

            awaitTrue(DELETE_TIMEOUT_MILLIS, "the swipe did not mark the chapter read") {
                runBlocking { readOf(chapterId) } == true
            }
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the chapter marked read on the manga screen kept its download") {
                !chapterDir.exists() && manifestRowOf(chapterId) == null
            }
        } finally {
            if (keptStart != null) library.swipeToStartAction.set(keptStart) else library.swipeToStartAction.delete()
            if (keptEnd != null) library.swipeToEndAction.set(keptEnd) else library.swipeToEndAction.delete()
            preferences.removeAfterReadSlots.set(oldSlot)
            preferences.removeAfterMarkedAsRead.set(oldMarkedAsRead)
            preferences.removeBookmarkedChapters.set(oldBookmarked)
            preferences.removeExcludeCategories.set(oldExcluded)
            runBlocking {
                if (chapterId > 0) graph.downloadManifestRepository.remove(listOf(chapterId))
                if (entryId > 0) graph.mangaRepository.softDelete(entryId, System.currentTimeMillis())
            }
            graph.downloadRoot.sourceDir(MARK_READ_SOURCE_DIR).deleteRecursively()
        }
    }

    /**
     * Phase 4 unit CS: the chapter row's swipes with both swipe keys unset, as an upstream user who never
     * touched them restores. A swipe to the right marks the chapter read (upstream's "Chapter on swipe
     * to right" default), a second one marks it unread again, and a swipe to the left bookmarks it.
     *
     * The entry is the class's own Local one, opened as a route by the app's intent on either shape:
     * a phone draws the one pane, a tablet the route's two panes, where the chapter list is the
     * narrower end pane; under `saikou.expect.tablet`, one pane fails the test. The two keys are put
     * back as they were, set or unset, whatever the outcome, since a restore earlier in the suite
     * may have set them.
     */
    @Test
    fun theChapterRowSwipesRightToMarkReadAndLeftToBookmarkWithTheDefaults() {
        val library = graph.libraryPreferences
        val keptStart = library.swipeToStartAction.takeIf { it.isSet() }?.get()
        val keptEnd = library.swipeToEndAction.takeIf { it.isSet() }?.get()
        try {
            library.swipeToStartAction.delete()
            library.swipeToEndAction.delete()
            val chapterId = runBlocking { graph.chapterRepository.getChapterByMangaId(mangaId).single().id }

            openEntryByIntent()
            if (composesTabletUi()) {
                composeRule.onNodeWithTag(MANGA_TWO_PANE_TAG).assertIsDisplayed()
            } else if (Args.expectTablet) {
                throw AssertionError("saikou.expect.tablet is true, but the manga screen composes one pane")
            }
            val row = hasText(FIXTURE_CHAPTER) and hasClickAction()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(row).fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse("the chapter was read before the swipe", runBlocking { readOf(chapterId) } == true)
            assertFalse("the chapter was bookmarked before the swipe", runBlocking { bookmarkOf(chapterId) } == true)

            // From near the start edge, on the title, to the end edge.
            composeRule.onNode(row).performTouchInput { swipeRight(startX = left + width * 0.05f, endX = right) }
            awaitTrue(SWIPE_TIMEOUT_MILLIS, "a swipe to the right did not mark the chapter read") {
                runBlocking { readOf(chapterId) } == true
            }

            composeRule.onNode(row).performTouchInput { swipeRight(startX = left + width * 0.05f, endX = right) }
            awaitTrue(SWIPE_TIMEOUT_MILLIS, "a second swipe to the right did not mark the chapter unread") {
                runBlocking { readOf(chapterId) } == false
            }

            // From the middle of the row back to the start edge, short of its end, where the download
            // control sits on any entry but this Local one.
            composeRule.onNode(row).performTouchInput { swipeLeft(startX = left + width * 0.4f, endX = left) }
            awaitTrue(SWIPE_TIMEOUT_MILLIS, "a swipe to the left did not bookmark the chapter") {
                runBlocking { bookmarkOf(chapterId) } == true
            }
            assertFalse("the swipe to the left changed the read state", runBlocking { readOf(chapterId) } == true)
            // The row draws the bookmark it now has, and carries no read button of its own (plan
            // 2.6): the swipe is its mark read, and "Mark as read" lives in the selection bar only.
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(row and hasContentDescription(string(R.string.action_filter_bookmarked)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText(string(R.string.action_mark_as_read)).assertCountEquals(0)
            composeRule.onAllNodesWithContentDescription(string(R.string.action_mark_as_read)).assertCountEquals(0)
        } finally {
            if (keptStart != null) library.swipeToStartAction.set(keptStart) else library.swipeToStartAction.delete()
            if (keptEnd != null) library.swipeToEndAction.set(keptEnd) else library.swipeToEndAction.delete()
        }
    }

    /**
     * Phase 4 unit CS: with "Chapter on swipe to left" set to Download, a swipe to the left on a
     * downloaded chapter deletes its folder and its manifest row at once, with no dialog, as upstream's
     * swipe does, and the same swipe made with the row selected deletes nothing first. The row says
     * it is downloaded through its download control's description (unit ID3). The entry is
     * this test's own on a source id no extension carries, set up as the mark read test's is; the key
     * and the entry are put back and removed whatever the outcome.
     */
    @Test
    fun aDownloadSwipeDeletesADownloadedChapterWithNoDialog() {
        assertNull("the swipe fixture's source id is installed", graph.sourceManager.get(SWIPE_SOURCE_ID))
        val library = graph.libraryPreferences
        val keptStart = library.swipeToStartAction.takeIf { it.isSet() }?.get()
        val entryTitle = "$SWIPE_PREFIX${System.nanoTime()}"
        var entryId = -1L
        var chapterId = -1L
        try {
            library.swipeToStartAction.set(LibraryPreferences.ChapterSwipeAction.Download)
            val chapterDir = File(graph.downloadRoot.mangaDir(SWIPE_SOURCE_DIR, entryTitle), FIXTURE_CHAPTER)
            runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = SWIPE_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                entryId = manga.id
                val chapter = graph.chapterRepository.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = entryId,
                            url = "/fixture/$entryTitle/1",
                            name = FIXTURE_CHAPTER,
                            chapterNumber = 1.0,
                            sourceOrder = 0,
                            dateFetch = System.currentTimeMillis(),
                            dateUpload = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                chapterId = chapter.id

                assertTrue("could not create the chapter folder", chapterDir.mkdirs())
                val page = File(chapterDir, "001.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
                graph.downloadManifestRepository.recordDownload(
                    DownloadManifestEntry(
                        id = 0,
                        chapterId = chapterId,
                        mangaId = entryId,
                        sourceId = SWIPE_SOURCE_ID,
                        mangaTitle = entryTitle,
                        mangaUrl = manga.url,
                        chapterUrl = chapter.url,
                        chapterName = chapter.name,
                        scanlator = null,
                        chapterDirName = FIXTURE_CHAPTER,
                        pageCount = 1L,
                        sizeBytes = page.length(),
                        dateDownloaded = System.currentTimeMillis(),
                    ),
                )
            }

            findFixtureCover(entryTitle).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithContentDescription(string(R.string.chapter_downloaded))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("the chapter folder is missing before the swipe", chapterDir.isDirectory)
            assertNotNull("the manifest row is missing before the swipe", manifestRowOf(chapterId))

            val row = hasText(FIXTURE_CHAPTER) and hasClickAction()
            // In selection mode the row does not swipe (the CS verifier's finding 1; plan 2.6 keeps
            // part E off): the same swipe with the row selected keeps the download.
            composeRule.onNode(row).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(row).performTouchInput { swipeLeft(startX = left + width * 0.4f, endX = left) }
            composeRule.waitForIdle()
            Thread.sleep(SELECTION_SWIPE_SETTLE_MILLIS)
            assertTrue("a swipe in selection mode deleted the chapter folder", chapterDir.isDirectory)
            assertNotNull("a swipe in selection mode deleted the manifest row", manifestRowOf(chapterId))
            composeRule.onNode(row).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isEmpty()
            }

            composeRule.onNode(row).performTouchInput { swipeLeft(startX = left + width * 0.4f, endX = left) }

            awaitTrue(DELETE_TIMEOUT_MILLIS, "a download swipe on a downloaded chapter kept its download") {
                !chapterDir.exists() && manifestRowOf(chapterId) == null
            }
            composeRule.onAllNodesWithText(string(R.string.chapter_delete_title)).assertCountEquals(0)
        } finally {
            if (keptStart != null) library.swipeToStartAction.set(keptStart) else library.swipeToStartAction.delete()
            runBlocking {
                if (chapterId > 0) graph.downloadManifestRepository.remove(listOf(chapterId))
                if (entryId > 0) graph.mangaRepository.softDelete(entryId, System.currentTimeMillis())
            }
            graph.downloadRoot.sourceDir(SWIPE_SOURCE_DIR).deleteRecursively()
        }
    }

    /**
     * R99 carry-over 2 and R104 carry-over 5: the selection bar's Mark as read and Mark previous as
     * read, both reaching `SetReadStatus` (`MangaDetailViewModel.markSelectedRead` and
     * `markSelectedPreviousRead`, through `SetReadStatus.awaitAllBefore`, now the screen's only mark
     * previous since plan 2.6 took the row's "<" button away) driven by a long press and a bar tap
     * rather than a direct view model call. Four chapters on the mark-read fixture's source, on their own entry: a long
     * press selects the first, the bar's Mark as read marks it read and clears the selection; a long
     * press then selects the fourth, and the bar's Mark previous as read marks every chapter before
     * it read, leaving the fourth itself unread.
     *
     * Chapter 1 carries the highest source order and chapter 4 the lowest, since
     * `SetReadStatus.awaitAllBefore` sorts with `sortDescending = false`, which for source order
     * reads the highest source order first (the scanlator ranking test's fixture uses the same
     * convention for the same reason).
     */
    @Test
    fun theSelectionBarsMarkAsReadAndMarkPreviousAsReadReachSetReadStatus() {
        assertNull(
            "the selection mark read fixture's source id is installed",
            graph.sourceManager.get(MARK_READ_SOURCE_ID),
        )
        val entryTitle = "$MARK_READ_PREFIX${System.nanoTime()}"
        var entryId = -1L
        val chapters = mutableMapOf<Int, Chapter>()
        try {
            runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = MARK_READ_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                entryId = manga.id
                val rows = (1..SELECTION_MARK_READ_CHAPTER_COUNT).map { number ->
                    Chapter.create().copy(
                        mangaId = entryId,
                        url = "/fixture/$entryTitle/$number",
                        name = "Chapter $number",
                        chapterNumber = number.toDouble(),
                        sourceOrder = (SELECTION_MARK_READ_CHAPTER_COUNT - number).toLong(),
                        dateFetch = System.currentTimeMillis(),
                        dateUpload = System.currentTimeMillis(),
                    )
                }
                graph.chapterRepository.addAll(rows).forEach { chapters[it.chapterNumber.toInt()] = it }
            }

            context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, entryId))
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            val firstRow = hasText("Chapter 1") and hasClickAction()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(firstRow).fetchSemanticsNodes().isNotEmpty()
            }
            val firstId = chapters.getValue(1).id
            val lastId = chapters.getValue(SELECTION_MARK_READ_CHAPTER_COUNT).id
            assertFalse("chapter 1 was read before the selection", runBlocking { readOf(firstId) } == true)

            composeRule.onNode(firstRow).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(string(R.string.action_mark_as_read)).performClick()

            awaitTrue(DELETE_TIMEOUT_MILLIS, "the selection bar's Mark as read did not mark chapter 1 read") {
                runBlocking { readOf(firstId) } == true
            }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isEmpty()
            }

            val lastRow = hasText("Chapter $SELECTION_MARK_READ_CHAPTER_COUNT") and hasClickAction()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(lastRow).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(lastRow).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(string(R.string.action_mark_previous_as_read)).performClick()

            val earlier = (1 until SELECTION_MARK_READ_CHAPTER_COUNT).map { chapters.getValue(it).id }
            awaitTrue(
                DELETE_TIMEOUT_MILLIS,
                "the selection bar's Mark previous as read did not mark every earlier chapter read",
            ) {
                earlier.all { runBlocking { readOf(it) } == true }
            }
            assertFalse(
                "Mark previous as read also marked the tapped chapter read",
                runBlocking { readOf(lastId) } == true,
            )
        } finally {
            if (entryId > 0) runBlocking { graph.mangaRepository.softDelete(entryId, System.currentTimeMillis()) }
        }
    }

    /**
     * R110 carry-over 2: Start now (the chapter row's swipe with the swipe key set to Download, on
     * a chapter neither downloaded nor queued: `MangaDetailViewModel.startDownloadNow`, `:791`)
     * moves its row to the front of the queue, asserted from the queue's own table.
     *
     * Two chapters are queued in reading order from the selection bar's own Download action
     * (`downloadSelected`). The entry's source carries no extension, so the worker's first pass
     * fails both immediately with `SourceNotInstalledException` and leaves their rows exactly where
     * they were queued: a failed row's state changes, never its `queue_order`. That failed state is
     * what holds the queue for this test, the same shape the download swipe test's and
     * `DownloadAdoptionDeviceTest`'s missing-source fixtures hold a chapter in rather than letting it
     * complete. A swipe on the second (now failed) chapter queues it again where it stands and moves
     * it to the front, which is exactly what a chapter that was never queued does too
     * (`MangaDetailViewModel.State.Success.swipeOf` maps both `ChapterDownload.None` and `.Failed` to
     * `ChapterSwipe.StartDownload`).
     */
    @Test
    fun startNowMovesTheSwipedChapterToTheFrontOfTheQueue() {
        assertNull("the queue order fixture's source id is installed", graph.sourceManager.get(QUEUE_SOURCE_ID))
        val library = graph.libraryPreferences
        val keptStart = library.swipeToStartAction.takeIf { it.isSet() }?.get()
        val entryTitle = "$QUEUE_PREFIX${System.nanoTime()}"
        var entryId = -1L
        val chapters = mutableMapOf<Int, Chapter>()
        try {
            library.swipeToStartAction.set(LibraryPreferences.ChapterSwipeAction.Download)
            runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = QUEUE_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                entryId = manga.id
                val rows = (1..QUEUE_ORDER_CHAPTER_COUNT).map { number ->
                    Chapter.create().copy(
                        mangaId = entryId,
                        url = "/fixture/$entryTitle/$number",
                        name = "Chapter $number",
                        chapterNumber = number.toDouble(),
                        sourceOrder = (QUEUE_ORDER_CHAPTER_COUNT - number).toLong(),
                        dateFetch = System.currentTimeMillis(),
                        dateUpload = System.currentTimeMillis(),
                    )
                }
                graph.chapterRepository.addAll(rows).forEach { chapters[it.chapterNumber.toInt()] = it }
            }
            val firstId = chapters.getValue(1).id
            val secondId = chapters.getValue(2).id

            context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, entryId))
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            val firstRow = hasText("Chapter 1") and hasClickAction()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(firstRow).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(firstRow).performTouchInput { longClick(percentOffset(0.1f, 0.5f)) }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(selectedCount(1)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(hasText("Chapter 2") and hasClickAction()).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(selectedCount(2)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(string(R.string.action_download)).performClick()

            awaitTrue(START_NOW_TIMEOUT_MILLIS, "the two chapters did not both fail to queue") {
                listOf(firstId, secondId).all { queueStateOf(it) == DownloadState.ERROR }
            }
            assertEquals(
                "the chapters were not queued in reading order",
                listOf(firstId, secondId),
                queueOrderOf(entryId),
            )

            val secondRow = hasText("Chapter 2") and hasClickAction()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(secondRow).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(secondRow).performTouchInput { swipeLeft(startX = left + width * 0.4f, endX = left) }

            awaitTrue(START_NOW_TIMEOUT_MILLIS, "start now did not move the swiped chapter to the front of the queue") {
                queueOrderOf(entryId).firstOrNull() == secondId
            }
            assertEquals(
                "start now changed more of the queue's order than the swiped chapter's position",
                listOf(secondId, firstId),
                queueOrderOf(entryId),
            )
        } finally {
            if (keptStart != null) library.swipeToStartAction.set(keptStart) else library.swipeToStartAction.delete()
            runBlocking {
                graph.downloadQueue.remove(chapters.values.map { it.id })
                if (entryId > 0) graph.mangaRepository.softDelete(entryId, System.currentTimeMillis())
            }
        }
    }

    private fun queueStateOf(chapterId: Long): DownloadState? = runBlocking {
        graph.downloadQueue.subscribe().first().firstOrNull { it.chapterId == chapterId }?.state
    }

    private fun queueOrderOf(mangaId: Long): List<Long> = runBlocking {
        graph.downloadQueue.subscribe().first().filter { it.mangaId == mangaId }.map { it.chapterId }
    }

    /**
     * R118 carry-over 1: the drag handle hides while the owner is searching the library and while
     * a filter is on, device coverage unit DC2's first item. Its own entry (companion
     * [MANUAL_SORT_SOURCE_ID]) rather than the class's shared fixture, so turning manual sort,
     * the search and the unread filter on and off here never changes what another test in this
     * class reads. `LibraryViewModel.State.canReorder` (`manualSort && query.isEmpty() &&
     * !hasActiveFilters && !selectionMode`) is the gate this test walks through three of its four
     * terms: manual sort on with an empty query and no filter shows the handle; a search query
     * hides it; clearing the search and turning a filter on hides it again.
     *
     * Both the search query and the filter are chosen to leave the one fixture entry itself
     * visible (an exact-title query still matches it; [TriState.ENABLED_NOT] on `unread` passes
     * it, since a chapterless entry has zero unread chapters), so the handle's disappearance is
     * the `canReorder` term breaking, not the category emptying out from under it: a query that
     * matched nothing, or a positive `unread`/`downloaded` filter a chapterless entry always
     * fails, would hide the handle by the same `items.isEmpty()` branch `LibraryPager` takes
     * before it ever reads `canReorder`, proving nothing about the gate itself (found by this
     * item's R29 proof, which a no-match query passed even with `query.isEmpty()` removed from
     * the gate).
     */
    @Test
    fun theDragHandleHidesDuringASearchAndDuringAFilter() {
        assertNull(
            "the manual sort fixture's source id is installed",
            graph.sourceManager.get(MANUAL_SORT_SOURCE_ID),
        )
        val library = graph.libraryPreferences
        val oldManualSort = library.manualSortEnabled.get()
        val oldCategorized = library.categorizedDisplaySettings.get()
        val oldFilterUnread = library.filterUnread.get()
        val entryTitle = "$MANUAL_SORT_PREFIX${System.nanoTime()}"
        var entryId = -1L
        val handle = hasContentDescription(string(R.string.library_reorder_handle))
        try {
            runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = MANUAL_SORT_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                entryId = manga.id
            }

            // The category's own sort has to be off too (contract 04, R118 item 3), or the manual
            // order this test turns on would never take hold of the tab it draws into.
            library.categorizedDisplaySettings.set(false)
            library.filterUnread.set(TriState.DISABLED)
            library.manualSortEnabled.set(true)

            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(handle).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
            composeRule.onNode(hasSetTextAction()).performTextInput(entryTitle)
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText(entryTitle)).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(handle).fetchSemanticsNodes().isEmpty()
            }

            // The bar's back arrow closes the field and clears its query (SearchTopBar's close sets
            // the query to null, which LibraryScreen reads as closed and empty).
            composeRule.onNodeWithContentDescription(string(R.string.action_close)).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(handle).fetchSemanticsNodes().isNotEmpty()
            }

            // ENABLED_NOT, not ENABLED_IS: the fixture entry has no chapters, so it has zero
            // unread chapters and only the negative sense of the filter keeps it in the category.
            library.filterUnread.set(TriState.ENABLED_NOT)
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(hasText(entryTitle)).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(handle).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            library.manualSortEnabled.set(oldManualSort)
            library.categorizedDisplaySettings.set(oldCategorized)
            library.filterUnread.set(oldFilterUnread)
            if (entryId > 0) runBlocking { graph.mangaRepository.softDelete(entryId, System.currentTimeMillis()) }
        }
    }

    /**
     * R113 carry-over 5: the volume headers (phase 4 unit VG), which ran on one phone AVD once, no
     * tablet, no API 26 and no class of their own. Five chapters on a source no extension carries,
     * one with no volume marker and two volumes of two chapters each; their `volume` field is
     * `VolumeRecognition.parseVolumeNumber`'s own read of each name, the way `SyncChaptersWithSource`
     * sets it on a real fetch. With the entry's "Group by volume" toggle off the list draws one row
     * per chapter and no header; turning it on through the chapter settings sheet's Display tab
     * inserts "Volume 1" before its first chapter and "Volume 2" before its own, in that order, and
     * the marker-less chapter gets neither. Opened by the app's own intent, so on a tablet this is
     * the manga route's own two-pane chapter list, the pane this class's other tests already assert.
     */
    @Test
    fun theGroupByVolumeToggleInsertsVolumeHeadersInOrderWithNoneForAChapterWithNoVolume() {
        assertNull("the volume fixture's source id is installed", graph.sourceManager.get(VOLUME_SOURCE_ID))
        val entryTitle = "$VOLUME_PREFIX${System.nanoTime()}"
        var entryId = -1L
        try {
            runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = VOLUME_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                entryId = manga.id
                val names = listOf("Ch.1", "Vol.1 Ch.2", "Vol.1 Ch.3", "Vol.2 Ch.4", "Vol.2 Ch.5")
                val rows = names.mapIndexed { index, name ->
                    Chapter.create().copy(
                        mangaId = entryId,
                        url = "/fixture/$entryTitle/$index",
                        name = name,
                        chapterNumber = (index + 1).toDouble(),
                        sourceOrder = index.toLong(),
                        volume = VolumeRecognition.parseVolumeNumber(entryTitle, name),
                        dateFetch = System.currentTimeMillis(),
                        dateUpload = System.currentTimeMillis(),
                    )
                }
                graph.chapterRepository.addAll(rows)
            }

            context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, entryId))
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("Ch.1").fetchSemanticsNodes().isNotEmpty()
            }
            val volumeOneHeader = context.getString(R.string.chapter_volume_header, "1")
            val volumeTwoHeader = context.getString(R.string.chapter_volume_header, "2")
            composeRule.onAllNodesWithText(volumeOneHeader).assertCountEquals(0)
            composeRule.onAllNodesWithText(volumeTwoHeader).assertCountEquals(0)

            // Opened by the app's own intent rather than a library tap, so on a tablet this is
            // the route's own two panes (`MANGA_TWO_PANE_TAG`), with no `LibraryDetailPane` beside
            // it to draw a second Filter button (R123 rule 2 is the library tap's problem, not this
            // route's): the plain content description matcher is already exactly one node here.
            composeRule.onNodeWithContentDescription(string(R.string.action_filter)).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.action_display)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(string(R.string.action_display)).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.chapter_display_group_by_volume))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(string(R.string.chapter_display_group_by_volume)).performClick()
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the toggle did not set the entry's groupByVolume bit") {
                runBlocking { graph.mangaRepository.getMangaById(entryId).groupByVolume }
            }
            // The sheet is a separate `Dialog` window over the chapter list's own, and the list
            // recomposes behind it the moment the bit is set; nothing here needs the sheet closed,
            // and closing it would need a scrim tap or a physical back key rather than the
            // activity's own dispatcher (the sheet's `BackHandler` answers to its own dialog window).
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(volumeTwoHeader).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText(volumeOneHeader).assertCountEquals(1)
            composeRule.onAllNodesWithText(volumeTwoHeader).assertCountEquals(1)
            // The rows are a lazy list, and `assertOrder` reads every row's position at once. On a
            // screen as short as the owner's POCO F1 (816 dp tall) the last row is never composed
            // where the list opens (the phone AVD set to that phone's size fails the same way), so
            // the list is scrolled to it first; the rows above it stay on screen.
            composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasText("Ch.1") and hasClickAction()))
                .performScrollToNode(hasText("Vol.2 Ch.5") and hasClickAction())
            assertOrder(
                hasText("Ch.1") and hasClickAction(),
                hasText(volumeOneHeader),
                hasText("Vol.1 Ch.2") and hasClickAction(),
                hasText("Vol.1 Ch.3") and hasClickAction(),
                hasText(volumeTwoHeader),
                hasText("Vol.2 Ch.4") and hasClickAction(),
                hasText("Vol.2 Ch.5") and hasClickAction(),
            )
        } finally {
            if (entryId > 0) runBlocking { graph.mangaRepository.softDelete(entryId, System.currentTimeMillis()) }
        }
    }

    /**
     * Phase 4 device coverage part 1b, R113 carry-over 6. The JVM tests run on the host's regular
     * expression engine; the app runs Android's, which is backed by ICU (older on API 26 than on
     * API 36). [VolumeRecognition] takes no context and does no I/O, so this calls it directly with no UI:
     * the device half of the test is which engine answers, not what is on screen. [ASCII_CORPUS] is
     * `volume-corpus.tsv`'s 49 rows, copied rather than read from a resource, since `:domain`'s test
     * resources are not on this module's `androidTest` classpath. [NON_LATIN_ROWS] adds Japanese,
     * Chinese and Korean titles and chapter names, each marker both glued to the surrounding text and
     * spaced from it, a native CJK word for "volume" the parser does not claim as one of upstream's
     * tokens, and a season word beside non-Latin text, so the failure line below names the result
     * the device actually gave for both groups.
     *
     * One row reads differently on a device than on the JVM on every AVD this check ran on, not on
     * API 26 alone (found by a first draft that assumed otherwise and was corrected after running
     * on `saikou_pixel9_4g`; R29's general lesson about a check that cannot fail cuts both ways, and
     * so does a guess about which device would differ). [NON_LATIN_ROWS] keeps that row out and
     * [theRowBothAndroidEnginesReadDifferentlyFromTheJvm] explains and asserts it on its own.
     */
    @Test
    fun theVolumeParserAgreesWithTheJvmOnTheAsciiCorpusAndOnNonLatinNames() {
        val rows = ASCII_CORPUS + NON_LATIN_ROWS
        val differences = rows.mapNotNull { row ->
            val parsed = VolumeRecognition.parseVolumeNumber(row.title, row.name)
            "${row.name}: the JVM gives ${row.volume}, this device gives $parsed".takeIf { parsed != row.volume }
        }
        Log.i(LOG_TAG, "volume parser device check: ${rows.size} names checked against the JVM's results")
        differences.forEach { Log.i(LOG_TAG, "volume parser difference: $it") }
        assertTrue("names whose device result differs from the JVM's: $differences", differences.isEmpty())
    }

    /**
     * The one name whose device result differs from the JVM's on every AVD this check ran
     * (`saikou_api26_2g`, `saikou_pixel9_4g` and `saikou_tablet_4g` all give null here; the JVM gives
     * 3.0), kept apart from the one check above that every other name, ASCII and non-Latin, passes
     * unchanged. The title "銀河のオルゴール" is removed from the name before the regular
     * expression ever sees it, the same as every other row, but "第2巻" is not part of the title and
     * stays, so the marker "Vol.3" that follows it is not at the start of the string: it is
     * preceded by a CJK letter with no gap. Java's `\b` on the JVM holds there regardless, because
     * its default word-character class is ASCII only and a CJK letter is already "not a word
     * character" on either side; the device's engine does not agree there (it does agree
     * everywhere else this corpus asked it to, including the other glued rows above, every one of
     * which strips its whole CJK prefix down to nothing before the marker and so never actually
     * puts a CJK letter next to it). Which classification is "right" is not this test's question;
     * that the two engines disagree on this one shape is.
     */
    @Test
    fun theRowBothAndroidEnginesReadDifferentlyFromTheJvm() {
        val row = VolumeRow("銀河のオルゴール", "銀河のオルゴール第2巻Vol.3Ch.4", null)
        val parsed = VolumeRecognition.parseVolumeNumber(row.title, row.name)
        Log.i(LOG_TAG, "volume parser device check: ${row.name} gives $parsed on this device, 3.0 on the JVM")
        assertEquals("${row.name} on this device", row.volume, parsed)
    }

    /**
     * Phase 4 device coverage part 1b, R114 carry-over 4, first half: the four-button duplicate
     * dialog, on a class the gate runs on all three AVDs. Two entries of one title on a source no
     * extension carries, one already a favourite; opening the other and tapping Add to library
     * reaches [com.shinsaikou.ui.manga.MangaDetailViewModel.toggleFavorite]'s duplicate question. The
     * dialog's own buttons are scoped to `hasAnyAncestor(isDialog())`, since the duplicate dialog's
     * Migrate button and the migrate dialog's Migrate button share one string and only one of the
     * two dialogs is ever composed.
     *
     * Second half: Migrate's target is the entry just opened, on the same uninstalled source id, so
     * [com.shinsaikou.migration.MigrateManga] throws `SourceNotInstalledException` at its very first
     * guard, before any write; that is "a target the fixture cannot refresh" with no product change,
     * since the class's whole fixture convention is a source id no extension carries. The dialog's
     * `onFailed` reaches
     * [com.shinsaikou.ui.manga.MangaDetailViewModel.migrationFailed], which shows
     * `migration_single_failed`'s snackbar and leaves the target out of the library.
     */
    @Test
    fun theDuplicateDialogsFourButtonsAndAFailedMigrationsSnackbar() {
        assertNull("the duplicate fixture's source id is installed", graph.sourceManager.get(DUPLICATE_SOURCE_ID))
        val entryTitle = "$DUPLICATE_PREFIX${System.nanoTime()}"
        var existingId = -1L
        var targetId = -1L
        try {
            runBlocking {
                existingId = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = DUPLICATE_SOURCE_ID,
                            url = "/fixture/$entryTitle/existing",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single().id
                targetId = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = DUPLICATE_SOURCE_ID,
                            url = "/fixture/$entryTitle/target",
                            title = entryTitle,
                            favorite = false,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single().id
            }

            context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, targetId))
            val addToLibrary = hasText(string(R.string.action_add_to_library)) and hasClickAction()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(addToLibrary).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(addToLibrary).performClick()

            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.manga_duplicate_title)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.manga_duplicate_message_format, entryTitle))
                .assertIsDisplayed()
            listOf(R.string.action_cancel, R.string.action_view_chapters, R.string.migrate, R.string.action_add_anyway)
                .forEach { label -> composeRule.onNode(inDialog(label)).assertIsDisplayed() }

            composeRule.onNode(inDialog(R.string.migrate)).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.migration_dialog_what_to_include))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(inDialog(R.string.migrate)).performClick()

            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.migration_single_failed)).fetchSemanticsNodes().isNotEmpty()
            }
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the failed migration still put the target in the library") {
                runBlocking { !graph.mangaRepository.getMangaById(targetId).favorite }
            }
        } finally {
            runBlocking {
                if (existingId > 0) graph.mangaRepository.softDelete(existingId, System.currentTimeMillis())
                if (targetId > 0) graph.mangaRepository.softDelete(targetId, System.currentTimeMillis())
            }
        }
    }

    /**
     * Phase 4 device coverage part 1b, R112 carry-over 5. A chapter fetched eleven days ago is well
     * past the relative window either way (R112 item 2's "within a week either side"), so its
     * Updates header reads as a formatted date regardless of Relative timestamps, through
     * [relativeDateText]'s own `dateFormat.format` branch. The expected text is built with the same
     * [UiPreferences.dateFormat] call the production header and the Settings chip's own label use, so
     * this follows a renamed pattern instead of a literal string.
     */
    @Test
    fun theDateFormatChipChangesAnUpdatesHeaderToTheChosenFormat() {
        val uiPreferences = graph.uiPreferences
        val oldFormat = uiPreferences.dateFormat.get()
        val newFormat = if (oldFormat == "yyyy-MM-dd") "dd/MM/yy" else "yyyy-MM-dd"
        val entryTitle = "$UPDATES_PREFIX${System.nanoTime()}"
        var entryId = -1L
        try {
            val dateFetch = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(UPDATES_HEADER_AGE_DAYS)
            runBlocking {
                entryId = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = UPDATES_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = dateFetch - 1_000L,
                        ),
                    ),
                ).single().id
                graph.chapterRepository.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = entryId,
                            url = "/fixture/$entryTitle/1",
                            name = "Chapter 1",
                            chapterNumber = 1.0,
                            sourceOrder = 0,
                            dateFetch = dateFetch,
                            dateUpload = dateFetch,
                        ),
                    ),
                )
            }

            context.startActivity(MainIntents.newIntent(context, MainDestination.UPDATES))
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(entryTitle).fetchSemanticsNodes().isNotEmpty()
            }
            val oldHeader = expectedUpdatesHeader(dateFetch, oldFormat)
            assertOrder(hasText(oldHeader), hasText(entryTitle))

            tabItem(R.string.tab_more).performClick()
            val settingsRow = hasText(string(R.string.label_settings)) and hasClickAction()
            composeRule.onNode(hasScrollAction()).performScrollToNode(settingsRow)
            composeRule.onNode(settingsRow).performClick()
            val appearanceRow = hasText(string(R.string.settings_section_appearance)) and
                hasClickAction() and isSelectable().not()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(appearanceRow).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(appearanceRow).performClick()
            val appearanceBody = settingsSectionBodyTag(SettingsSection.APPEARANCE)
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(appearanceBody).fetchSemanticsNodes().isNotEmpty()
            }
            val newFormatLabel = context.getString(
                R.string.pref_date_format_choice_format,
                newFormat,
                UiPreferences.dateFormat(newFormat).format(LocalDate.now()),
            )
            composeRule.onNodeWithTag(appearanceBody).performScrollToNode(hasText(newFormatLabel))
            composeRule.onNode(hasText(newFormatLabel) and hasClickAction()).performClick()
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the chip did not store the new date format") {
                uiPreferences.dateFormat.get() == newFormat
            }

            // Not a tab tap: Settings is a route pushed over the main scaffold on a phone (the
            // section row's own wait above only ever needed a back press to show the next one, in
            // MainActivitySmokeTest), so the bottom bar is not a reliable way back to Updates on
            // every shape. The entry intent is how a notification returns to a tab and does not
            // depend on what the back stack holds.
            context.startActivity(MainIntents.newIntent(context, MainDestination.UPDATES))
            val newHeader = expectedUpdatesHeader(dateFetch, newFormat)
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(newHeader).fetchSemanticsNodes().isNotEmpty()
            }
            assertOrder(hasText(newHeader), hasText(entryTitle))
        } finally {
            uiPreferences.dateFormat.set(oldFormat)
            if (entryId > 0) runBlocking { graph.mangaRepository.softDelete(entryId, System.currentTimeMillis()) }
        }
    }

    /** The day [epochMillis] falls on, formatted as the production header and the Settings chip read it. */
    private fun expectedUpdatesHeader(epochMillis: Long, format: String): String =
        UiPreferences.dateFormat(format).format(epochMillis.toLocalDate().toJavaLocalDate())

    /** A dialog's own button or text, never the screen behind it: only one dialog composes at a time. */
    private fun inDialog(label: Int): SemanticsMatcher =
        hasText(string(label)) and hasClickAction() and hasAnyAncestor(isDialog())

    /** Each of [matchers] shown below the one before it, top to bottom on screen. */
    private fun assertOrder(vararg matchers: SemanticsMatcher) {
        val tops = matchers.map { composeRule.onNode(it).fetchSemanticsNode().boundsInRoot.top }
        tops.zipWithNext().forEachIndexed { index, (above, below) ->
            assertTrue("item ${index + 1} of the order is not above item ${index + 2}: $tops", above < below)
        }
    }

    private suspend fun bookmarkOf(chapterId: Long): Boolean? = graph.chapterRepository.getChapterById(chapterId)?.bookmark

    /**
     * Phase 4 unit SC on a device. The entry has Group A's and Group B's copies of chapters 1 to 3,
     * every one downloaded, on a source id no extension carries, so the reader reads them from disk.
     * In reading order Group A's copy of each number comes first, so the unranked resume opens Group
     * A's chapter 1. The scanlator dialog's "Move up" on Group B's row and OK rank Group B first; the
     * screen's Continue reading then opens Group B's chapter 1.
     *
     * With "Skip duplicate chapters" on and "After reading automatically delete" on the second to
     * last read chapter, reading that session to its end (each chapter's last page, then the next
     * chapter) and leaving it with back deletes Group B's chapters 1 and 2 and nothing else: the
     * slot counts along the session's own list, which holds the ranked copies (c3 C, R99 item 3).
     * Group B's chapter 3 is read and in no slot yet, and Group A's copies were never read.
     */
    @Test
    fun theScanlatorDialogsRankingOpensTheRankedCopyAndTheDeleteSlotTakesOnlyReadChapters() {
        assertNull("the ranking fixture's source id is installed", graph.sourceManager.get(RANKING_SOURCE_ID))
        val downloads = graph.downloadPreferences
        val reader = graph.readerPreferences
        val oldSlot = downloads.removeAfterReadSlots.get()
        val oldMarkedAsRead = downloads.removeAfterMarkedAsRead.get()
        val oldBookmarked = downloads.removeBookmarkedChapters.get()
        val oldExcluded = downloads.removeExcludeCategories.get()
        val oldSkipDupe = reader.skipDupe.get()
        val entryTitle = "$RANKING_PREFIX${System.nanoTime()}"
        var entryId = -1L
        val chapters = mutableMapOf<String, Chapter>()
        val folders = mutableMapOf<String, File>()
        // The reader's own view model, taken on the main thread inside its onResume, as
        // DownloadedChapterReaderTest's launchReader takes it.
        val captured = AtomicReference<Pair<ReaderActivity, ReaderViewModel>>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is ReaderActivity && stage == Stage.RESUMED && captured.get() == null) {
                captured.set(activity to ViewModelProvider(activity)[ReaderViewModel::class.java])
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        try {
            downloads.removeAfterReadSlots.set(SECOND_TO_LAST_READ_CHAPTER)
            downloads.removeAfterMarkedAsRead.set(false)
            downloads.removeBookmarkedChapters.set(false)
            downloads.removeExcludeCategories.set(emptySet())
            reader.skipDupe.set(true)

            runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = RANKING_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single()
                entryId = manga.id
                // Source order falls as reading goes on: Group A's 1, Group B's 1, Group A's 2 and so on.
                val rows = (1..3).flatMap { number ->
                    listOf(GROUP_A, GROUP_B).mapIndexed { copy, group ->
                        Chapter.create().copy(
                            mangaId = entryId,
                            url = "/fixture/$entryTitle/$group/$number",
                            name = "Chapter $number",
                            scanlator = group,
                            chapterNumber = number.toDouble(),
                            sourceOrder = (10 - 2 * number - copy).toLong(),
                            dateFetch = System.currentTimeMillis(),
                            dateUpload = System.currentTimeMillis(),
                        )
                    }
                }
                graph.chapterRepository.addAll(rows).forEach { chapter ->
                    val key = "${chapter.scanlator} ${chapter.chapterNumber.toInt()}"
                    chapters[key] = chapter
                    val dirName = "${chapter.scanlator}_${chapter.name}"
                    val dir = File(graph.downloadRoot.mangaDir(RANKING_SOURCE_DIR, entryTitle), dirName)
                    assertTrue("could not create $dirName", dir.mkdirs())
                    val pages = (1..RANKING_PAGES).map { File(dir, "%03d.png".format(it)).also(::writePage) }
                    folders[key] = dir
                    graph.downloadManifestRepository.recordDownload(
                        DownloadManifestEntry(
                            id = 0,
                            chapterId = chapter.id,
                            mangaId = entryId,
                            sourceId = RANKING_SOURCE_ID,
                            mangaTitle = entryTitle,
                            mangaUrl = manga.url,
                            chapterUrl = chapter.url,
                            chapterName = chapter.name,
                            scanlator = chapter.scanlator,
                            chapterDirName = dirName,
                            pageCount = pages.size.toLong(),
                            sizeBytes = pages.sumOf { it.length() },
                            dateDownloaded = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            findFixtureCover(entryTitle).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
            }

            // Rank Group B from the dialog: the rows list the groups by name, so Group B is second.
            composeRule.onNode(mangaPaneFilterButton()).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.chapter_filter_scanlators))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(string(R.string.chapter_filter_scanlators)).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.scanlator_filter_title))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("the entry was ranked before the dialog", ranking(entryId).isEmpty())
            composeRule.onAllNodesWithContentDescription(string(R.string.scanlator_move_up))[1].performClick()
            composeRule.onNodeWithText(string(R.string.action_ok)).performClick()
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the dialog's OK wrote no ranking: ${ranking(entryId)}") {
                ranking(entryId) == listOf(GROUP_B, GROUP_A)
            }

            // The screen's resume button opens Group B's chapter 1, where the first copy is Group A's.
            monitor.addLifecycleCallback(callback)
            val continueReading = string(R.string.action_continue_reading)
            // The button's label sits under the FAB's cleared semantics, so only the unmerged tree
            // holds it; a tap on the label lands on the button.
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(continueReading, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(continueReading, useUnmergedTree = true).performClick()
            awaitTrue(READER_TIMEOUT_MILLIS, "the resume button opened no reader") { captured.get() != null }
            val (activity, viewModel) = captured.get()
            val expected = listOf("$GROUP_B 1", "$GROUP_B 2", "$GROUP_B 3")
            var previous: Long? = null
            expected.forEachIndexed { index, key ->
                val chapterId = chapters.getValue(key).id
                // Ready on a chapter other than the one before, then the one expected, so a wrong copy
                // fails naming the copy the reader showed.
                awaitTrue(READER_TIMEOUT_MILLIS, "the reader showed no chapter after ${previous ?: "the tap"}") {
                    val state = viewModel.state.value
                    val current = state.currentChapter
                    state.phase is ReaderPhase.Ready && current != null && current.chapter.id != previous &&
                        current.pages?.isNotEmpty() == true
                }
                val shown = checkNotNull(viewModel.state.value.currentChapter)
                val shownKey = "${shown.chapter.scanlator} ${shown.chapter.chapterNumber.toInt()}"
                assertEquals("the reader showed $shownKey where $key was expected", chapterId, shown.chapter.id)
                if (index == 0) logcat("resume opened $shownKey")
                val pages = checkNotNull(shown.pages)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    viewModel.send(ReaderEvent.PageSelected(pages.last()))
                }
                awaitTrue(DELETE_TIMEOUT_MILLIS, "the last page never marked $chapterId read") {
                    runBlocking { readOf(chapterId) } == true
                }
                previous = chapterId
                if (index < expected.lastIndex) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        viewModel.send(ReaderEvent.NextChapter)
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.onBackPressedDispatcher.onBackPressed()
            }

            val deleted = listOf("$GROUP_B 1", "$GROUP_B 2")
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the slot's chapters kept their downloads") {
                deleted.all { !folders.getValue(it).exists() && manifestRowOf(chapters.getValue(it).id) == null }
            }
            Thread.sleep(SETTLE_MILLIS)
            val kept = chapters.keys - deleted.toSet()
            kept.forEach { key ->
                assertTrue("$key lost its folder", folders.getValue(key).isDirectory)
                assertNotNull("$key lost its manifest row", manifestRowOf(chapters.getValue(key).id))
            }
            val read = chapters.filterValues { runBlocking { readOf(it.id) } == true }.keys
            assertEquals(
                "the reader read other chapters than Group B's",
                setOf("$GROUP_B 1", "$GROUP_B 2", "$GROUP_B 3"),
                read,
            )
            logcat("deleted ${deleted.joinToString()}; kept ${kept.sorted().joinToString()}")
        } finally {
            monitor.removeLifecycleCallback(callback)
            downloads.removeAfterReadSlots.set(oldSlot)
            downloads.removeAfterMarkedAsRead.set(oldMarkedAsRead)
            downloads.removeBookmarkedChapters.set(oldBookmarked)
            downloads.removeExcludeCategories.set(oldExcluded)
            reader.skipDupe.set(oldSkipDupe)
            runBlocking {
                graph.downloadManifestRepository.remove(chapters.values.map { it.id })
                if (entryId > 0) graph.mangaRepository.softDelete(entryId, System.currentTimeMillis())
            }
            graph.downloadRoot.sourceDir(RANKING_SOURCE_DIR).deleteRecursively()
        }
    }

    /**
     * Phase 4 unit SC, review 3: the dialog's working copy outlives a change to the title's groups
     * while it is open. The entry has Group A's and Group B's chapter 1 and no ranking; with the
     * dialog open, "Move up" on Group B's row, then Group C's chapter 2 lands in the database, as a
     * library update or a sync pull would add it. The dialog shows Group C's row and keeps Group B
     * above Group A, so OK writes Group B, Group A, Group C. A working copy started over on the new
     * group list would show the rows by name again and write no ranking.
     */
    @Test
    fun theScanlatorDialogKeepsAMoveWhenANewGroupArrivesWhileItIsOpen() {
        assertNull("the ranking fixture's source id is installed", graph.sourceManager.get(RANKING_SOURCE_ID))
        val entryTitle = "$RANKING_PREFIX${System.nanoTime()}"
        var entryId = -1L
        fun chapter(group: String, number: Int) = Chapter.create().copy(
            mangaId = entryId,
            url = "/fixture/$entryTitle/$group/$number",
            name = "Chapter $number",
            scanlator = group,
            chapterNumber = number.toDouble(),
            sourceOrder = (10 - number).toLong(),
            dateFetch = System.currentTimeMillis(),
            dateUpload = System.currentTimeMillis(),
        )
        try {
            runBlocking {
                entryId = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = RANKING_SOURCE_ID,
                            url = "/fixture/$entryTitle",
                            title = entryTitle,
                            favorite = true,
                            initialized = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    ),
                ).single().id
                graph.chapterRepository.addAll(listOf(chapter(GROUP_A, 1), chapter(GROUP_B, 1)))
            }

            findFixtureCover(entryTitle).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(mangaPaneFilterButton()).performClick()
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText(string(R.string.chapter_filter_scanlators))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(string(R.string.chapter_filter_scanlators)).performClick()
            val moveUp = string(R.string.scanlator_move_up)
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithContentDescription(moveUp).fetchSemanticsNodes().size == 2
            }
            composeRule.onAllNodesWithContentDescription(moveUp)[1].performClick()
            composeRule.waitForIdle()

            runBlocking { graph.chapterRepository.addAll(listOf(chapter(GROUP_C, 2))) }
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithContentDescription(moveUp).fetchSemanticsNodes().size == 3
            }
            assertTrue("the entry was ranked before OK", ranking(entryId).isEmpty())
            composeRule.onNodeWithText(string(R.string.action_ok)).performClick()
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the dialog lost the move: ranking ${ranking(entryId)}") {
                ranking(entryId) == listOf(GROUP_B, GROUP_A, GROUP_C)
            }
            logcat("a new group while the dialog was open: ranking ${ranking(entryId).joinToString()}")
        } finally {
            if (entryId > 0) runBlocking { graph.mangaRepository.softDelete(entryId, System.currentTimeMillis()) }
        }
    }

    /**
     * R109 carry-over 5: the lock's own unlock screen past the idle delay, and a cancelled
     * confirmation's toast. A PIN set through the instrumentation's shell (never the owner's
     * device) makes the device able to authenticate, so the "Require unlock" row's confirmation
     * (R109 item 8) shows the system's real credential prompt instead of answering true at once
     * (`AuthenticatorUtil.isAuthenticationSupported`). Turning the row on through the row, past
     * that prompt, then moving the activity to `CREATED` and back to `RESUMED` past a one-minute
     * idle delay is upstream's `onApplicationStart` deciding `requireUnlock` the way `AppLockTest`
     * proves on the JVM (R109's own device account used the same one minute idle, 70 s past it);
     * `UnlockActivity` reaching `RESUMED` is read through the instrumentation's own lifecycle
     * monitor, and the system prompt's own window, found by UiAutomator, is the device half neither
     * `AppLockTest` nor a JVM test can show. That prompt is then succeeded too, so `AppLock.unlock()`
     * runs and the activity returns to a clean state for every test after this one. The row is
     * tapped a second time to turn the lock back off, and this prompt is cancelled:
     * `AuthenticatorUtil.authenticate`'s own `onAuthenticationError` toasts the system's error text
     * (androidx's wording, not one of upstream's own strings, so it is recorded rather than asserted
     * literally), and the preference is asserted unchanged, as `confirm() == false` leaves it.
     *
     * The PIN is cleared in `finally` on every path, before the two preferences and `AppLock.unlock()`
     * put the process back the way every other test in this class finds it.
     */
    @Test
    fun theLockPromptShowsPastTheIdleDelayAndACancelledConfirmationToasts() {
        val securityPreferences = graph.securityPreferences
        val originalUseAuth = securityPreferences.useAuthenticator.get()
        val originalLockAppAfter = securityPreferences.lockAppAfter.get()
        var pinSet = false
        try {
            val setPinOutput = shell("locksettings set-pin $LOCK_TEST_PIN")
            pinSet = true
            Log.i(LOG_TAG, "set-pin: $setPinOutput")
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            awaitTrue(SYSTEM_PROMPT_TIMEOUT_MILLIS, "the device never reported isDeviceSecure after set-pin: $setPinOutput") {
                keyguard.isDeviceSecure
            }

            val requireUnlockRow = hasText(string(R.string.lock_with_biometrics)) and hasClickAction()
            openSettingsSection(SettingsSection.SECURITY)
            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(requireUnlockRow).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(requireUnlockRow).performClick()
            Log.i(LOG_TAG, "confirm-on: ${enterSystemPin(LOCK_TEST_PIN)}")
            awaitTrue(SYSTEM_PROMPT_TIMEOUT_MILLIS, "Require unlock was not written true after the prompt") {
                securityPreferences.useAuthenticator.get()
            }

            // `requireUnlock` defaults true at process start and nothing in this class's own
            // activity resumes ever changes it (`onApplicationStart` returns at once while
            // `useAuthenticator` is false, which it is for every other test). Left alone, the row's
            // own confirm above would not touch it either, so the CREATE/RESUME cycle below would
            // find a prompt already due for a reason that has nothing to do with the one minute idle
            // wait it exists to prove. Calling `unlock()` here is the same step a user who had just
            // turned the row on would already be past, and it is what makes `lastAppClosed` actually
            // get stamped on the way down (`appClosedStamp` answers null while `requireUnlock` is
            // still true) and what makes `onApplicationStart` read the elapsed time rather than
            // short-circuit on `requireUnlock` already being true on the way back up.
            graph.appLock.unlock()

            // Lock when idle reads the same preference store whichever way it is written; the
            // brief asks only that Require unlock itself go through the row's own confirm.
            securityPreferences.lockAppAfter.set(LOCK_IDLE_MINUTES)

            // `ActivityScenario`'s state transitions block the calling thread until the activity
            // has actually made them, so they are called from this, the instrumentation's own test
            // thread, never from the main thread a `runOnUiThread` would use (that would deadlock:
            // the transition needs the main thread free to run the lifecycle callbacks on).
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            // `ProcessLifecycleOwner` waits a short span before it treats every activity stopping
            // as the process backgrounding, to tell that apart from a configuration change.
            Thread.sleep(SETTLE_MILLIS)
            Thread.sleep(LOCK_IDLE_WAIT_MILLIS)

            val resumed = AtomicReference(false)
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (activity is UnlockActivity && stage == Stage.RESUMED) resumed.set(true)
            }
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            monitor.addLifecycleCallback(callback)
            try {
                composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                awaitTrue(SYSTEM_PROMPT_TIMEOUT_MILLIS, "UnlockActivity never reached RESUMED") { resumed.get() }
            } finally {
                monitor.removeLifecycleCallback(callback)
            }
            Log.i(LOG_TAG, "lock screen: UnlockActivity resumed after ${LOCK_IDLE_WAIT_MILLIS}ms idle at $LOCK_IDLE_MINUTES minute(s)")
            val promptRoot = awaitSystemWindow(SYSTEM_PROMPT_TIMEOUT_MILLIS)
            assertNotNull("no system window showed over UnlockActivity", promptRoot)
            Log.i(LOG_TAG, "lock screen: the system window's package is ${promptRoot?.packageName}")

            // Succeeded, not cancelled: AppLock.unlock() then runs through UnlockActivity's own
            // success callback (finish(), never finishAffinity()), so the rest of this class finds
            // the activity resumed and the preference the row wrote still in place.
            Log.i(LOG_TAG, "lock screen: ${enterSystemPin(LOCK_TEST_PIN)}")
            awaitTrue(SYSTEM_PROMPT_TIMEOUT_MILLIS, "AppLock never unlocked") { !graph.appLock.requireUnlock }

            composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(requireUnlockRow).fetchSemanticsNodes().isNotEmpty()
            }
            val toast = captureToast(SYSTEM_PROMPT_TIMEOUT_MILLIS) {
                composeRule.onNode(requireUnlockRow).performClick()
                cancelSystemPrompt()
            }
            assertNotNull("no toast followed the cancelled confirmation", toast)
            Log.i(LOG_TAG, "cancelled confirmation: toast \"$toast\"")
            assertTrue(
                "Require unlock was turned off by a cancelled confirmation",
                securityPreferences.useAuthenticator.get(),
            )
        } finally {
            securityPreferences.useAuthenticator.set(originalUseAuth)
            securityPreferences.lockAppAfter.set(originalLockAppAfter)
            graph.appLock.unlock()
            if (pinSet) Log.i(LOG_TAG, "clear-pin: ${shell("locksettings clear --old $LOCK_TEST_PIN")}")
        }
    }

    /**
     * R111 carry-over 1: the messages of the four Advanced rows (reset per-series reader settings,
     * refresh library covers, revoke trusted extensions and clear WebView data), none of them
     * behind a confirmation dialog. They were toasts read by the accessibility event the platform
     * sends for one; since phase 5 unit `phase5-chrome-3` Settings shows them in its own
     * snackbar, so each is read as a node in the composition instead. The two effects the
     * carry-over names where this device can show them: a viewer flag reset (the fixture entry's
     * own `viewerFlags`, set to a non-default value first) and WebView cookies gone (a cookie set
     * on a fixture host first). The store removal dialog is excluded, as the carry-over says, since
     * it needs a network index this suite never builds.
     */
    @Test
    fun theFourAdvancedRowsSayWhatTheyDidAndResetClearsFlagsAndClearWebViewDataClearsCookies() {
        val cookieUrl = "https://saikou-advanced-rows-fixture.test/"
        val cookieManager = CookieManager.getInstance()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            cookieManager.setCookie(cookieUrl, "saikou_fixture=1")
            cookieManager.flush()
        }
        runBlocking {
            graph.mangaRepository.update(
                MangaUpdate(id = mangaId, viewerFlags = 0L.withReadingMode(ReadingMode.WEBTOON)),
            )
        }

        val bodyTag = openSettingsSection(SettingsSection.ADVANCED)

        val webViewCleared = string(R.string.webview_data_deleted)
        assertSnackbar(webViewCleared) {
            composeRule.onNodeWithTag(bodyTag).performScrollToNode(hasText(string(R.string.pref_clear_webview_data)))
            composeRule.onNode(hasText(string(R.string.pref_clear_webview_data)) and hasClickAction()).performClick()
        }
        // Not asserted: on `saikou_api26_2g` the fixture cookie outlives this (`app_webview/`
        // deleted, `CookieManager.getCookie` still answers it): that API's `CookieManager` keeps
        // its own in-process cache the directory delete does not invalidate, so the clear only
        // takes full effect after the process restarts. `saikou_pixel9_4g` (API 36) clears it
        // within this same process, which is why this was written as a hard assertion first; the
        // carry-over's claim is read at the message (always true) and this effect where the device
        // can show it, not assumed uniform across API levels.
        var cookieAfterClear: String? = "(not read)"
        val deadline = SystemClock.uptimeMillis() + DELETE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { cookieAfterClear = cookieManager.getCookie(cookieUrl) }
            if (cookieAfterClear.isNullOrEmpty()) break
            Thread.sleep(POLL_MILLIS)
        }
        Log.i(
            LOG_TAG,
            "clear WebView data: message \"$webViewCleared\", the fixture cookie is now $cookieAfterClear" +
                if (cookieAfterClear.isNullOrEmpty()) "" else " (not cleared in-process within ${DELETE_TIMEOUT_MILLIS}ms on this API level)",
        )

        val refreshStarted = string(R.string.library_update_started)
        assertSnackbar(refreshStarted) {
            composeRule.onNodeWithTag(bodyTag).performScrollToNode(hasText(string(R.string.pref_refresh_library_covers)))
            composeRule.onNode(hasText(string(R.string.pref_refresh_library_covers)) and hasClickAction()).performClick()
        }
        Log.i(LOG_TAG, "refresh library covers: message \"$refreshStarted\"")

        val resetDone = string(R.string.pref_reset_viewer_flags_success)
        assertSnackbar(resetDone) {
            composeRule.onNodeWithTag(bodyTag).performScrollToNode(hasText(string(R.string.pref_reset_viewer_flags)))
            composeRule.onNode(hasText(string(R.string.pref_reset_viewer_flags)) and hasClickAction()).performClick()
        }
        awaitTrue(DELETE_TIMEOUT_MILLIS, "the fixture's viewer flags were not reset") {
            runBlocking { graph.mangaRepository.getMangaById(mangaId).viewerFlags } == 0L
        }
        Log.i(LOG_TAG, "reset per-series reader settings: message \"$resetDone\", the fixture's viewer flags are now 0")

        val restartNeeded = string(R.string.requires_app_restart)
        assertSnackbar(restartNeeded) {
            composeRule.onNodeWithTag(bodyTag).performScrollToNode(hasText(string(R.string.ext_revoke_trust)))
            composeRule.onNode(hasText(string(R.string.ext_revoke_trust)) and hasClickAction()).performClick()
        }
        Log.i(LOG_TAG, "revoke trusted extensions: message \"$restartNeeded\"")
    }

    private fun ranking(mangaId: Long): List<String> = runBlocking {
        graph.scanlatorPriorityRepository.getByMangaId(mangaId).map { it.scanlator }
    }

    /** A real PNG, so the reader decodes each page as it would a download's. */
    private fun writePage(target: File) {
        val bitmap = Bitmap.createBitmap(RANKING_PAGE_WIDTH, RANKING_PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(RANKING_PAGE_COLOR)
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun logcat(message: String) {
        Log.i(LOG_TAG, "scanlator ranking: $message")
    }

    private suspend fun readOf(chapterId: Long): Boolean? = graph.chapterRepository.getChapterById(chapterId)?.read

    private fun manifestRowOf(chapterId: Long): DownloadManifestEntry? = runBlocking {
        graph.downloadManifestRepository.getByChapterId(chapterId)
    }

    /** Polls [condition] off the main thread, failing with [message] once [timeoutMillis] have passed. */
    private fun awaitTrue(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$message (after $timeoutMillis ms)")
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * The fixture's cover, reached through the library's own search rather than by scrolling to it.
     * The device the gate runs on carries whatever library the last run left behind, so the entry
     * this test wrote may sit anywhere in it, and the search is the one control that puts a known
     * title on screen whatever else is there.
     *
     * `hasText(title) and hasClickAction()` alone matches the search field as well as the cover: once
     * the title is typed, the field's own `InputText` holds it and the field carries an `OnClick`
     * action for its IME. That made both the wait and the lookup ambiguous, the wait because it was
     * satisfied the instant the text was typed rather than once the grid had actually filtered down to
     * the cover, and the lookup because `onNode` requires exactly one match. Excluding nodes with a
     * text-entry action rules the field out of both, which is why the same tightened matcher is used
     * for the wait and for the node it returns; narrowing only the return would still let the wait
     * race the grid.
     */
    private fun findFixtureCover(entryTitle: String = title): SemanticsNodeInteraction {
        composeRule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(entryTitle)
        val cover = fixtureCover(entryTitle)
        composeRule.waitUntil(timeoutMillis = SEARCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(cover).fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onNode(cover)
    }

    /** The one matcher [findFixtureCover] explains, shared so no caller can drift back to the ambiguous one. */
    private fun fixtureCover(entryTitle: String = title) =
        hasText(entryTitle) and hasClickAction() and hasSetTextAction().not()

    /** The fixture's cover tapped on a tablet, waited on until the pane holds the real detail screen. */
    private fun openFixtureInPane() {
        findFixtureCover().performClick()
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** System back, through the dispatcher every `BackHandler` in the app registers with. */
    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    /** The rail or bar item rather than the screen behind it; only the item is selectable. */
    private fun tabItem(labelRes: Int) = composeRule.onNode(hasText(string(labelRes)) and isSelectable())

    /** What both selection bars show as their title: the library's and the detail screen's. */
    private fun selectedCount(count: Int): String = context.getString(R.string.selection_count_format, count)

    /** The entry opened as a route, through the intent the launcher shortcuts and notifications use. */
    private fun openEntryByIntent() {
        context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, mangaId))
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The library flow reaches the composition some frames after `setContent`, and until it does
     * the start destination is a loading spinner. Waiting for the title alone would return while
     * the flow was still to emit; waiting for the spinner's absence alone would return on the first
     * frame, before the scaffold exists. Both, in that order, is `MainActivitySmokeTest`'s wait and
     * the reason is written out there.
     */
    private fun awaitLibraryScreen() {
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.library_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /** The bar's own title. `library_title` and `tab_library` are both "Library"; only the tab is selectable. */
    private fun libraryTitle() = hasText(string(R.string.library_title)) and isSelectable().not()

    /**
     * The library's bar while the search that found the fixture is open. As in upstream, the field takes
     * the title's place in the bar, so the bar shows the query instead of [libraryTitle].
     */
    private fun librarySearchBar() = hasSetTextAction() and hasText(title)

    /**
     * The rule `isTabletUi()` applies inside the composition, evaluated against the window the
     * activity was given and the mode the app is really holding, rather than off a device name.
     */
    private fun composesTabletUi(): Boolean {
        val mode = graph.uiPreferences.tabletUiMode.get()
        val configuration = composeRule.activity.resources.configuration
        return mode.isTabletUi(configuration.smallestScreenWidthDp, configuration.orientation)
    }

    /**
     * Writes one logcat line under [LOG_TAG] before a test returns early. A count of passing
     * tests does not say which of them asserted anything, so every early return in this class
     * calls this rather than returning silently.
     */
    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: $reason")
    }

    /**
     * The early return of a test that asserts only on two panes, on a device composing one. It is
     * the phone's right answer and a skip like any other, except under `saikou.expect.tablet`, where
     * the run said this device composes two panes and a return would pass having asserted nothing.
     */
    private fun notComposingTabletUi(reason: String) {
        if (Args.expectTablet) throw AssertionError("saikou.expect.tablet is true, but $reason")
        skip(reason)
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    /**
     * The manga pane's own Filter action, scoped so a tablet's split library (the list pane's top
     * bar carries the same content description as the manga pane's) resolves to exactly one node.
     * A phone never shows the two at once, so the plain matcher is enough there; on a tablet the
     * cover tap opens the manga screen inside [LIBRARY_DETAIL_PANE_TAG] (`LibraryDetailPane`,
     * `twoPane = false`), which the plain matcher has no way to tell apart from the list pane beside it.
     */
    private fun mangaPaneFilterButton(): SemanticsMatcher {
        val filter = hasContentDescription(string(R.string.action_filter))
        return if (composesTabletUi()) filter and hasAnyAncestor(hasTestTag(LIBRARY_DETAIL_PANE_TAG)) else filter
    }

    /** A shell command run through the instrumentation's own shell, its stdout returned whole. */
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    /**
     * From the library tab: the More tab, the Settings row, [section]'s own row, and a wait for its
     * body tag, as [theDateFormatChipChangesAnUpdatesHeaderToTheChosenFormat] already does inline
     * for Appearance. Returns the body tag, so a caller can scroll inside it.
     */
    private fun openSettingsSection(section: SettingsSection): String {
        tabItem(R.string.tab_more).performClick()
        val settingsRow = hasText(string(R.string.label_settings)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(settingsRow)
        composeRule.onNode(settingsRow).performClick()
        val sectionRow = hasText(string(section.labelRes)) and hasClickAction() and isSelectable().not()
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(sectionRow).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(sectionRow).performClick()
        val bodyTag = settingsSectionBodyTag(section)
        composeRule.waitUntil(timeoutMillis = PANE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(bodyTag).fetchSemanticsNodes().isNotEmpty()
        }
        return bodyTag
    }

    /**
     * Runs [action] and waits for Settings' snackbar to show exactly [text], then for it to go, so
     * the next row's tap lands on the row and not on a snackbar still covering the bottom of the
     * list.
     */
    private fun assertSnackbar(text: String, action: () -> Unit) {
        action()
        composeRule.waitUntil(timeoutMillis = SNACKBAR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = SNACKBAR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Runs [action] with a toast listener installed and returns the first toast's text, read from
     * the accessibility event the platform sends for it, as
     * [com.shinsaikou.reader.DownloadedChapterReaderTest.assertTheReaderClosesSaying] already reads one
     * (toasts are not in the view tree on newer APIs). The wait runs inside [withToastListener], not
     * after it: the platform's accessibility event for a toast can arrive a short time after the
     * call that shows the toast returns, and tearing the listener down the moment [action] returns,
     * before that wait, would miss a toast that is merely a little late.
     */
    private fun captureToast(timeoutMillis: Long, action: () -> Unit): String? {
        val toasts = CopyOnWriteArrayList<String>()
        withToastListener(toasts) {
            action()
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (toasts.isEmpty() && SystemClock.uptimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        }
        return toasts.firstOrNull()
    }

    /**
     * Installs the same accessibility listener
     * [com.shinsaikou.reader.DownloadedChapterReaderTest.assertTheReaderClosesSaying] uses to read a
     * toast's text into [toasts], runs [block], then removes it. [block] must do its own waiting
     * before returning: nothing read after the listener comes off is seen.
     */
    private fun withToastListener(toasts: CopyOnWriteArrayList<String>, block: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.className?.toString()?.startsWith(Toast::class.java.name) == true
            ) {
                event.text.mapTo(toasts) { it.toString() }
            }
        }
        try {
            block()
        } finally {
            automation.setOnAccessibilityEventListener(null)
        }
    }

    /** The active window's root, once it belongs to a package other than this app's own. */
    private fun awaitSystemWindow(timeoutMillis: Long): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && root.packageName?.toString() != context.packageName) return root
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    /** [node] and every descendant of it, depth first. */
    private fun allNodesUnder(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            result.add(next)
            for (i in 0 until next.childCount) next.getChild(i)?.let(stack::addLast)
        }
        return result
    }

    /**
     * Enters [pin] into the system's credential confirmation, found by UiAutomator: an editable
     * node if the keypad backs one (`ACTION_SET_TEXT`, then a submit button if there is one), else
     * each digit's own button by its text (several PIN keypads submit at the stored length with no
     * button at all). Returns a line describing what it found, for the report.
     */
    private fun enterSystemPin(pin: String): String {
        val root = awaitSystemWindow(SYSTEM_PROMPT_TIMEOUT_MILLIS)
            ?: throw AssertionError("no system window appeared ${SYSTEM_PROMPT_TIMEOUT_MILLIS}ms after the row was tapped")
        val pkg = root.packageName
        val nodes = allNodesUnder(root)
        Log.i(
            LOG_TAG,
            "system window pkg=$pkg nodes=" +
                nodes.joinToString(" | ") {
                    "${it.className}:text=${it.text}:desc=${it.contentDescription}:editable=${it.isEditable}:clickable=${it.isClickable}"
                },
        )
        val editable = nodes.firstOrNull { it.isEditable }
        if (editable != null) {
            // `ACTION_SET_TEXT` alone was tried first and left the row's own confirmation stuck:
            // the field's text changed but nothing read it, which is this exact prompt's own
            // `PasswordTextView` (AOSP's PIN and password confirmation, contentDescription "PIN
            // area" here), appending one key event at a time rather than watching the `Editable`.
            // Real digit key events, the way a device's own keyboard would send them, are what it
            // actually watches; `ACTION_SET_TEXT` is kept first as a focus nudge in case this build
            // answers to it instead, then every digit goes in as its own key event, then an Enter in
            // case this variant needs an explicit confirm rather than auto-submitting at length.
            //
            // The key events go through `UiAutomation.injectInputEvent`, not
            // `Instrumentation.sendKeyDownUpSync`: the target window belongs to `com.android.
            // systemui`, not this instrumentation's own target package, and `Instrumentation`'s
            // injector answers that with `SecurityException: Injecting to another application
            // requires INJECT_EVENTS permission` on API 26 (first seen on `saikou_api26_2g`, not on
            // API 36's `saikou_pixel9_4g`). `UiAutomation`'s own injector is the one a real
            // accessibility-based test tool uses and carries the permission this needs everywhere.
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            for (digit in pin) {
                injectKey(KeyEvent.KEYCODE_0 + (digit - '0'))
            }
            val submit = nodes.firstOrNull { node ->
                node.isClickable && node.text?.toString()?.let { it.equals("ok", true) || it.equals("confirm", true) || it.equals("enter", true) } == true
            }
            if (submit != null) {
                submit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                injectKey(KeyEvent.KEYCODE_ENTER)
            }
            return "pkg=$pkg: sent each digit of the PIN as its own key event to an editable node" +
                if (submit != null) " and clicked its submit button" else " and a trailing Enter (no submit button found)"
        }
        val digitButtons = nodes.filter { node ->
            node.isClickable && node.text?.toString()?.let { it.length == 1 && it[0].isDigit() } == true
        }
        if (digitButtons.isNotEmpty()) {
            for (digit in pin) {
                val button = digitButtons.firstOrNull { it.text.toString() == digit.toString() }
                    ?: throw AssertionError("pkg=$pkg: no button for digit $digit among ${digitButtons.map { it.text }}")
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return "pkg=$pkg: tapped each digit of the PIN on its own keypad button"
        }
        throw AssertionError("pkg=$pkg: neither an editable node nor digit buttons were found; nodes: ${nodes.map { "${it.className}:${it.text}" }}")
    }

    /**
     * A down then an up for [keyCode], through `UiAutomation.injectInputEvent` rather than
     * `Instrumentation.sendKeyDownUpSync`, which [enterSystemPin] explains.
     */
    private fun injectKey(keyCode: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val now = SystemClock.uptimeMillis()
        automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0), true)
        automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0), true)
    }

    /** Cancels the system's credential confirmation: its own Cancel button, or the device back key. */
    private fun cancelSystemPrompt() {
        val root = awaitSystemWindow(SYSTEM_PROMPT_TIMEOUT_MILLIS) ?: return
        val cancel = allNodesUnder(root).firstOrNull { it.isClickable && it.text?.toString()?.equals("cancel", true) == true }
        if (cancel != null) {
            cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private companion object {
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        /** Tag for the logcat lines that record a skipped assertion rather than an invisible one. */
        const val LOG_TAG = "TwoPaneLayoutTest"

        const val FIXTURE_PREFIX = "saikou-two-pane-"
        const val FIXTURE_CHAPTER = "Chapter 1"

        /** The local source's id, which is the one source every install has. */
        const val FIXTURE_SOURCE_ID = 0L

        /**
         * The mark read test's entry: a source id no extension carries and no other device class uses
         * (001 is `DownloadedChapterReaderTest`'s, 002 `DownloadAdoptionDeviceTest`'s, whose stub
         * source row outlives its run; R104 carry-over 3), and a folder name of its own, since
         * `DownloadedChapterReaderTest` deletes its own source folder whole and the locator finds a
         * chapter in any source folder.
         */
        const val MARK_READ_SOURCE_ID = 7_310_000_000_000_005L
        const val MARK_READ_SOURCE_DIR = "Fixture Mark Read Source"
        const val MARK_READ_PREFIX = "saikou-mark-read-"

        /** The download swipe test's entry, on a source id and a folder of its own, as the mark read test's. */
        const val SWIPE_SOURCE_ID = 7_310_000_000_000_003L
        const val SWIPE_SOURCE_DIR = "Fixture Swipe Source"
        const val SWIPE_PREFIX = "saikou-swipe-"

        /** A swipe's write goes through the view model to the database. */
        const val SWIPE_TIMEOUT_MILLIS = 10_000L

        /** The selection bar's mark read test: chapter 1 through chapter 4. */
        const val SELECTION_MARK_READ_CHAPTER_COUNT = 4

        /**
         * The start now / queue order test's entry, on a source id and a folder name of its own, as
         * the mark read test's and the swipe test's are.
         */
        const val QUEUE_SOURCE_ID = 7_310_000_000_000_006L
        const val QUEUE_PREFIX = "saikou-queue-order-"
        const val QUEUE_ORDER_CHAPTER_COUNT = 2

        /** The engine fails both chapters against the missing source almost at once. */
        const val START_NOW_TIMEOUT_MILLIS = 10_000L

        /** The volume headers test's entry, on a source id of its own, as the other new fixtures are. */
        const val VOLUME_SOURCE_ID = 7_310_000_000_000_007L
        const val VOLUME_PREFIX = "saikou-volume-headers-"

        /**
         * The drag handle test's entry (phase 4 device coverage unit DC2, item 2): a source id no
         * extension carries and no other device class uses. `...008L` and `...009L` are part 1b's
         * (R125's fixture numbering), so this unit's first new id is `...010L`.
         */
        const val MANUAL_SORT_SOURCE_ID = 7_310_000_000_000_010L
        const val MANUAL_SORT_PREFIX = "saikou-manual-sort-"

        /**
         * The ranking test's entry: another source id no extension carries, with a folder of its
         * own for the same reason as the mark read test's.
         */
        const val RANKING_SOURCE_ID = 7_310_000_000_000_004L
        const val RANKING_SOURCE_DIR = "Fixture Ranking Source"
        const val RANKING_PREFIX = "saikou-ranking-"
        const val GROUP_A = "Group A"
        const val GROUP_B = "Group B"
        const val GROUP_C = "Group C"
        const val RANKING_PAGES = 2
        const val RANKING_PAGE_WIDTH = 64
        const val RANKING_PAGE_HEIGHT = 96
        const val RANKING_PAGE_COLOR = 0xFF304050.toInt()

        /** `remove_after_read_slots`: the chapter read before the one just finished. */
        const val SECOND_TO_LAST_READ_CHAPTER = 1

        /** A reader opened and a chapter read from disk. */
        const val READER_TIMEOUT_MILLIS = 30_000L
        const val SETTLE_MILLIS = 2_000L

        /** `remove_after_read_slots` off, so only the mark read can delete. */
        const val DELETE_AFTER_READING_DISABLED = -1
        const val DELETE_TIMEOUT_MILLIS = 10_000L

        /**
         * How long a swipe made in selection mode is given to delete, which it must not. The delete
         * the same swipe makes outside selection mode lands well inside this on every AVD the gate
         * runs, so a gesture that still reached the delete would be seen.
         */
        const val SELECTION_SWIPE_SETTLE_MILLIS = 3_000L
        const val POLL_MILLIS = 50L

        /** A cold start opens the graph and the database before the library flow can emit. */
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        /** The library search is debounced before it re-filters. */
        const val SEARCH_TIMEOUT_MILLIS = 10_000L

        /** A view model built, a chapter list read and a pane composed. */
        const val PANE_TIMEOUT_MILLIS = 20_000L

        /**
         * The duplicate dialog test's two entries, on a source id and a folder name of its own, as
         * the other new fixtures are (phase 4 device coverage part 1b, the next free id after
         * part 1a's `...007`).
         */
        const val DUPLICATE_SOURCE_ID = 7_310_000_000_000_008L
        const val DUPLICATE_PREFIX = "saikou-duplicate-"

        /** The date format chip test's entry, on a source id of its own, as the other new fixtures are. */
        const val UPDATES_SOURCE_ID = 7_310_000_000_000_009L
        const val UPDATES_PREFIX = "saikou-updates-header-"

        /** Past R112 item 2's week-either-side window, so the header is a date whichever way Relative timestamps is set. */
        const val UPDATES_HEADER_AGE_DAYS = 11L

        /**
         * The lock prompt test's own device credential, set through the instrumentation's shell
         * and cleared the same way in `finally`; never the owner's device, never a PIN anyone reads.
         */
        const val LOCK_TEST_PIN = "135797"

        /** R109's own device account used one minute idle with a wait past it; this repeats that. */
        const val LOCK_IDLE_MINUTES = 1
        const val LOCK_IDLE_WAIT_MILLIS = 70_000L

        /** A system window (the credential prompt, the toast) the device, not the app, draws. */
        const val SYSTEM_PROMPT_TIMEOUT_MILLIS = 15_000L

        /** Long enough for a short snackbar (4 s) to show and go on a slow emulator. */
        const val SNACKBAR_TIMEOUT_MILLIS = 15_000L

        private data class VolumeRow(val title: String, val name: String, val volume: Double?)

        /**
         * `domain/src/test/resources/com/shinsaikou/domain/chapter/volume-corpus.tsv`'s 49 rows, copied
         * (that file is not on this module's classpath): `VolumeRecognitionTest`'s corpus, the
         * forms sources send, including the revision markers that read as volumes and the tokens
         * ("s", "season") that must not.
         */
        val ASCII_CORPUS = listOf(
            VolumeRow("Glass Orchard", "Vol.1 Ch.1", 1.0),
            VolumeRow("Glass Orchard", "Vol.1 Ch.2: The Gate", 1.0),
            VolumeRow("Glass Orchard", "Vol. 2 Ch. 10 - Rain", 2.0),
            VolumeRow("Glass Orchard", "Vol.02 Chapter 011", 2.0),
            VolumeRow("Glass Orchard", "Vol.12 Ch.105.5", 12.0),
            VolumeRow("Glass Orchard", "vol.0 ch.0: Prologue", 0.0),
            VolumeRow("Paper Lantern Road", "Volume 3 Chapter 21", 3.0),
            VolumeRow("Paper Lantern Road", "VOLUME 4 CHAPTER 30", 4.0),
            VolumeRow("Paper Lantern Road", "Volume.5 Chapter 41", 5.0),
            VolumeRow("Paper Lantern Road", "[Vol.6] Chapter 50", 6.0),
            VolumeRow("Paper Lantern Road", "Vol_07 Ch_061", 7.0),
            VolumeRow("Paper Lantern Road", "Vol-8 Ch-70", 8.0),
            VolumeRow("Paper Lantern Road", "vol9 ch80", 9.0),
            VolumeRow("Paper Lantern Road", "Vol.10 Ch.90", 10.0),
            VolumeRow("Tin Harbour", "Tin Harbour v01 c001", 1.0),
            VolumeRow("Tin Harbour", "Tin Harbour v01 c002", 1.0),
            VolumeRow("Tin Harbour", "v.2 c.010", 2.0),
            VolumeRow("Tin Harbour", "V3 Ch.20", 3.0),
            VolumeRow("Tin Harbour", "Ch.21 (Vol.3)", 3.0),
            VolumeRow("Tin Harbour", "Chapter 35: Volume 4 Begins", 4.0),
            VolumeRow("Tin Harbour", "Vol.100 Ch.1000", 100.0),
            VolumeRow("Tin Harbour", "Vol.3 Ch.12 v2", 3.0),
            VolumeRow("Moon Shelf Vol 9", "Moon Shelf Vol 9 Chapter 3", null),
            VolumeRow("Moon Shelf Vol 9", "Moon Shelf Vol 9 Vol.1 Chapter 3", 1.0),
            VolumeRow("Salt Bridge", "Ch. 12 v2", 2.0),
            VolumeRow("Salt Bridge", "Chapter 13 ver.2", 2.0),
            VolumeRow("Salt Bridge", "Chapter 14 (version 3)", 3.0),
            VolumeRow("Glass Orchard", "Chapter 1", null),
            VolumeRow("Glass Orchard", "Ch. 12: The Long Night", null),
            VolumeRow("Glass Orchard", "Chapter 45: Level 2", null),
            VolumeRow("Glass Orchard", "Chapter 9: Revolution 2", null),
            VolumeRow("Glass Orchard", "Chapter 3: Evolve 2", null),
            VolumeRow("Glass Orchard", "Episode 5", null),
            VolumeRow("Glass Orchard", "Oneshot", null),
            VolumeRow("Glass Orchard", "#12", null),
            VolumeRow("Glass Orchard", "Chapter 20: V-Day", null),
            VolumeRow("Glass Orchard", "Vol.TBD Ch.100", null),
            VolumeRow("Glass Orchard", "Chapter 101: 1v1", null),
            VolumeRow("Glass Orchard", "Chapter 102: Vs 2 Rivals", null),
            VolumeRow("Salt Bridge", "Chapter 7 (rev2)", null),
            VolumeRow("Salt Bridge", "Chapter 8 [c2v3]", null),
            VolumeRow("Salt Bridge", "Chapter 9 prev2", null),
            VolumeRow("Night Ferry", "S2 Episode 10", null),
            VolumeRow("Night Ferry", "s2 ep. 11", null),
            VolumeRow("Night Ferry", "Season 2 Episode 12", null),
            VolumeRow("Night Ferry", "[Season 3] Ep. 1", null),
            VolumeRow("Night Ferry", "Chapter 5: The King's 2 Swords", null),
            VolumeRow("Night Ferry", "Chapter 6: Heroes 3 Days Later", null),
            VolumeRow("Night Ferry", "Season 2 Vol.3 Ep. 12", 3.0),
        )

        /**
         * Japanese, Chinese and Korean titles and chapter names (every title invented): each
         * script's marker both glued to the surrounding text with no gap and spaced from it,
         * Korean's zero-character gap form ("V7", as the corpus's "vol9 ch80" is), a native CJK word
         * for "volume" ("巻") beside no Latin marker, which the parser does not claim since its
         * tokens are upstream's ASCII ones only, and a season word beside non-Latin text. One more row,
         * where a native word sits beside a Latin marker with no gap, reads differently from the JVM
         * on every AVD this check ran on (not API 26 alone, as a first draft assumed); that row is
         * kept out of this list and asserted on its own in
         * [theRowBothAndroidEnginesReadDifferentlyFromTheJvm].
         */
        val NON_LATIN_ROWS = listOf(
            VolumeRow("鋼の心", "鋼の心Vol.2", 2.0),
            VolumeRow("鋼の心", "鋼の心 Vol. 3", 3.0),
            VolumeRow("龙族传说", "龙族传说Volume5", 5.0),
            VolumeRow("龙族传说", "龙族传说 Volume 6", 6.0),
            VolumeRow("달빛소녀", "달빛소녀V7", 7.0),
            VolumeRow("달빛소녀", "달빛소녀 Ver. 8", 8.0),
            VolumeRow("夜の渡し", "夜の渡し第2巻", null),
            VolumeRow("夜の渡し", "夜の渡しSeason2 Ep.1", null),
        )
    }
}
