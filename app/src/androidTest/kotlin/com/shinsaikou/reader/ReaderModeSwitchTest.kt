package com.shinsaikou.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ModeResolver
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.reader.ui.stringRes
import com.shinsaikou.util.GateFragments
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * A reading mode switched through the reader's own settings sheet rebuilds the viewer exactly once
 * and keeps the reader on the page it was on. Contract 07 part 2 section 9.2, built under
 * `design/plans/phase2-start.md` Q9 (a).
 *
 * The JVM side already pins the reducer's half: `ReaderStateMachineTest` asserts that an effective
 * mode change bumps `viewerGeneration` once and that nothing else does. What it cannot see is the
 * rest of the chain, and that is where the one reader defect found since the last device run
 * lived (`f01b21d`, a viewer built before the real mode arrived). So everything here goes through
 * the screen: a tap in the middle of the page opens the menu, the menu's slider moves the reader
 * to [TARGET_PAGE], the bottom bar's settings button opens the sheet, and the sheet's chip for the
 * other mode is tapped. That chip writes the mode onto the entry, the entry's flow brings it back,
 * the reducer bumps the generation and `ReaderViewerContainer` keys a new viewer on it. No event is
 * sent from the test.
 *
 * ### What is asserted, and how each part could fail
 *
 * - **Rebuilt once.** `viewerGeneration` moves by exactly one, and the process logged exactly one
 *   new `reader.viewer` marker, for the new mode at the new generation. The marker is written when
 *   `ReaderViewerContainer` has constructed a viewer and sent `ViewerBuilt`, so the second half
 *   counts viewers actually built rather than generations requested: a key that churned, or an
 *   effect performed on top of the key (decision D16), would build two and log two.
 * - **The page held.** The session reports [TARGET_PAGE] after the rebuild, and a screenshot shows
 *   it: every page of the fixture is a different flat colour ([ReaderFixture.pageColor]), and the
 *   pixel in the middle of the screen has to be the target page's. The session alone could not
 *   show this. A viewer that came up on the first page without reporting a page selection would
 *   leave the state on the target page while the screen showed page 1.
 * - **The orientation request followed.** The activity's requested orientation is still the one
 *   the session's effective orientation maps to, so the rebuild did not drop it.
 * - **The slider's page was recorded.** Before the switch, the chapter's row holds the page the
 *   slider was let go on. Only the viewer's page report commits progress, and the webtoon viewer
 *   reports a slider jump only from the layout pass that follows it (RecyclerView's
 *   `onScrolled(0, 0)` after a layout that changed the visible range), so a viewer that moved
 *   without reporting would leave the row on page 1.
 *
 * Both directions run, because the two viewer families position themselves differently: the pager
 * selects an adapter item and the webtoon viewer scrolls a list to an offset.
 *
 * There is no early return anywhere in this class. The fixture is written by the test process
 * ([ReaderFixture]), so it asserts on every device the suite runs on.
 *
 * ### A page note, through the same screen (phase 4 unit BN)
 *
 * [aNoteWrittenFromThePageSheetShowsTheMarkerAndBlankTextRemovesIt] shares the fixture and the
 * owner's gestures: a long press on the page opens the page sheet, its Note button opens the note
 * dialog, typed text is saved, the bottom bar's marker appears with the menu, the marker opens the
 * dialog on the stored note, and the text saved empty removes the note and the marker. Each step
 * is read back twice: the reader's state, and the note table through the application's graph.
 */
@RunWith(AndroidJUnit4::class)
class ReaderModeSwitchTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = instrumentation.targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    private lateinit var fixture: ReaderFixture

    /**
     * Whether `verticalNavigator` was set before this test pinned it, and to what. `BackupImportTest`
     * restores the owner's real backup earlier in the suite, and that backup can set
     * `pref_vertical_navigator` to a set that gives WEBTOON a vertical chapter navigator
     * (`ReaderChrome.kt`). This test's slider drag is horizontal, so the preference is pinned to
     * empty for the run and put back exactly as found afterward.
     */
    private var verticalNavigatorWasSet = false
    private lateinit var verticalNavigatorBefore: Set<String>

    @Before
    fun buildTheFixtureAndSeedTheRows() {
        val verticalNavigator = graph.readerPreferences.verticalNavigator
        verticalNavigatorWasSet = verticalNavigator.isSet()
        verticalNavigatorBefore = verticalNavigator.get()
        verticalNavigator.set(emptySet())
        fixture = ReaderFixture.create(context, graph, FIXTURE_PREFIX, listOf(PAGE_COUNT))
    }

    @After
    fun removeTheFixture() {
        if (::fixture.isInitialized) fixture.remove()
        val verticalNavigator = graph.readerPreferences.verticalNavigator
        if (verticalNavigatorWasSet) verticalNavigator.set(verticalNavigatorBefore) else verticalNavigator.delete()
    }

    @Test
    fun thePagerSwitchedToLongStripIsRebuiltOnceOnTheSamePage() {
        switchTheMode(from = ReadingMode.LEFT_TO_RIGHT, to = ReadingMode.WEBTOON)
    }

    @Test
    fun theLongStripSwitchedToThePagerIsRebuiltOnceOnTheSamePage() {
        switchTheMode(from = ReadingMode.WEBTOON, to = ReadingMode.LEFT_TO_RIGHT)
    }

    @Test
    fun aNoteWrittenFromThePageSheetShowsTheMarkerAndBlankTextRemovesIt() {
        // The sheet opens only on a long tap the preference allows, and an owner's restored backup
        // can turn it off, so it is pinned for this method and put back as found.
        val longTap = graph.readerPreferences.readWithLongTap
        val longTapWasSet = longTap.isSet()
        val longTapBefore = longTap.get()
        longTap.set(true)
        fixture.writeReadingMode(ReadingMode.LEFT_TO_RIGHT)
        val chapterId = fixture.chapters.single().id
        val scenario = ActivityScenario.launch<ReaderActivity>(
            ReaderIntents.newIntent(context, fixture.mangaId, chapterId, page = 0),
        )
        try {
            val viewModel = viewModelOf(scenario)
            val ready = awaitState(viewModel, "the session never became ready") {
                it.phase is ReaderPhase.Ready && it.totalPages > 0
            }
            val first = checkNotNull(ready.currentChapter?.pages) { "the chapter published no page list" }.first()
            awaitTrue("the viewer never loaded page 1") { first.route != null || first.status is PageState.Error }
            assertTrue("page 1 did not decode: ${first.status}", first.status is PageState.Ready)

            longPressTheMiddle()
            awaitState(viewModel, "a long press on the page did not open the page sheet") {
                it.dialog is ReaderDialog.PageActions
            }
            compose.onNodeWithText(string(R.string.action_page_note)).performClick()
            awaitState(viewModel, "the sheet's Note button did not open an empty note dialog for page 1") {
                it.dialog == ReaderDialog.PageNote(chapterId, 0, "")
            }
            compose.onNode(hasSetTextAction()).performTextInput(NOTE)
            compose.onNodeWithText(string(R.string.action_save)).performClick()
            awaitTrue("Save did not store the note on page 1") { storedNotes(chapterId) == listOf(0L to NOTE) }
            awaitState(viewModel, "the reader never saw the note it stored") { it.dialog == null && it.currentNote == NOTE }
            awaitNoDialogWindow()

            openTheMenu(viewModel)
            val marker = compose.onNodeWithContentDescription(string(R.string.page_note_marker))
            marker.assertIsDisplayed()
            marker.performClick()
            awaitState(viewModel, "the marker did not open the note dialog on the stored note") {
                it.dialog == ReaderDialog.PageNote(chapterId, 0, NOTE)
            }
            compose.onNodeWithText(NOTE).assertIsDisplayed()
            saveEvidenceScreenshot("note-dialog-and-marker")

            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNodeWithText(string(R.string.action_save)).performClick()
            awaitTrue("saving the note empty did not remove it") { storedNotes(chapterId).isEmpty() }
            awaitState(viewModel, "the reader still shows a note after its removal") {
                it.dialog == null && it.currentNote == null
            }
            // The bar is still up, so a missing marker is the note's removal, not a hidden menu.
            compose.waitForIdle()
            assertTrue("the menu closed with the note dialog", viewModel.state.value.menuVisible)
            compose.onNodeWithContentDescription(string(R.string.action_settings)).assertIsDisplayed()
            compose.onAllNodesWithContentDescription(string(R.string.page_note_marker)).assertCountEquals(0)
            logcat(LOG_TAG, LogPriority.INFO) { "page note written, shown by the marker, and removed on page 1" }
        } finally {
            scenario.close()
            if (longTapWasSet) longTap.set(longTapBefore) else longTap.delete()
        }
    }

    private fun switchTheMode(from: ReadingMode, to: ReadingMode) {
        fixture.writeReadingMode(from)
        // Page 0 by name: with no page the reader resumes where the chapter was left.
        val scenario = ActivityScenario.launch<ReaderActivity>(
            ReaderIntents.newIntent(context, fixture.mangaId, fixture.chapters.single().id, page = 0),
        )
        try {
            val viewModel = viewModelOf(scenario)
            val ready = awaitState(viewModel, "the session never became ready") {
                it.phase is ReaderPhase.Ready && it.totalPages > 0
            }
            assertEquals("the entry did not carry the reading mode this test wrote", from, ready.readingMode)
            assertEquals("the page list is not the fixture's", PAGE_COUNT, ready.totalPages)
            val first = checkNotNull(ready.currentChapter?.pages) { "the chapter published no page list" }.first()
            awaitTrue("the $from viewer never loaded page 1") {
                first.route != null || first.status is PageState.Error
            }
            assertTrue("page 1 did not decode: ${first.status}", first.status is PageState.Ready)

            openTheMenu(viewModel)
            moveWithTheSlider(viewModel)
            awaitTheScreenShows(TARGET_PAGE, "the $from viewer before the switch")

            val before = viewModel.state.value
            val markersBefore = viewerMarkers()

            chooseTheModeInTheSettingsSheet(viewModel, to)
            val switched = awaitState(viewModel, "the reader never switched to $to") {
                it.readingMode == to && it.viewerGeneration > before.viewerGeneration
            }
            closeTheSettingsSheet(viewModel)
            awaitTheScreenShows(TARGET_PAGE, "the $to viewer after the switch")

            // Whatever the new viewer reported while it positioned itself has been reduced by now:
            // the screen already shows the page, and a second build would follow the first within
            // a frame or two. The pause and the idle wait after it let a late one arrive, and be
            // composed, before anything is counted.
            Thread.sleep(SETTLE_MILLIS)
            compose.waitForIdle()
            val after = viewModel.state.value
            assertRebuiltOnce(to, before, switched, after, markersBefore)
            assertThePageHeld(before, after)
            assertTheOrientationFollowed(scenario, after)
            logcat(LOG_TAG, LogPriority.INFO) {
                "$from to $to: generation ${before.viewerGeneration} to ${after.viewerGeneration}, " +
                    "page ${after.currentPage + 1} of ${after.totalPages} before and after"
            }
        } finally {
            scenario.close()
        }
    }

    /**
     * A tap in the middle of the screen. Every tap-zone layout either viewer offers gives the
     * middle to the menu, so this is the owner's gesture, delivered to the window the way a finger's
     * is rather than as a reader event.
     *
     * One retry, only for a tap the system delivered late. `Pager` opens the menu from
     * `GestureDetector.onSingleTapConfirmed`, and the detector schedules its long press at the
     * event's own down time plus the long press timeout, so a down that reaches the window after
     * that moment is taken for a long press and the tap is dropped (shown on the API 36 phone AVD
     * with a down time 600 ms old). On the owner's POCO F1 the first tap after the note dialog
     * closes reaches the window about 420 ms after its down time, against that phone's 300 ms
     * timeout, and opened no menu in 3 runs of 3; a tap a moment later arrives in under 20 ms and
     * opens it. A tap delivered in time that opens no menu still fails here as before.
     */
    private fun openTheMenu(viewModel: ReaderViewModel) {
        val delivered = tapTheMiddle()
        val late = delivered != null && delivered > ViewConfiguration.getLongPressTimeout()
        if (late && !menuOpensWithin(viewModel, LATE_TAP_WAIT_MILLIS)) {
            logcat(LOG_TAG, LogPriority.WARN) {
                "the tap reached the window $delivered ms after its down time, past the " +
                    "${ViewConfiguration.getLongPressTimeout()} ms long press timeout; tapping once more"
            }
            tapTheMiddle()
        }
        awaitState(viewModel, "a tap in the middle of the page did not open the menu (delivered $delivered ms after its down time)") {
            it.menuVisible
        }
    }

    /**
     * Sends [openTheMenu]'s tap and returns how long after its down time the reader's window
     * received the down, read by wrapping the resumed activity's window callback for the tap alone;
     * null if the window saw no down.
     */
    private fun tapTheMiddle(): Long? {
        var window: Window? = null
        var original: Window.Callback? = null
        val delays = CopyOnWriteArrayList<Long>()
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val callback = activity.window.callback
            window = activity.window
            original = callback
            activity.window.callback = object : Window.Callback by callback {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) delays += SystemClock.uptimeMillis() - event.downTime
                    return callback.dispatchTouchEvent(event)
                }
            }
        }
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels / 2f
        val down = SystemClock.uptimeMillis()
        val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        val release = MotionEvent.obtain(down, down + TAP_MILLIS, MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.sendPointerSync(press)
            instrumentation.sendPointerSync(release)
        } finally {
            press.recycle()
            release.recycle()
            instrumentation.runOnMainSync { window?.callback = original }
        }
        return delays.firstOrNull()
    }

    private fun menuOpensWithin(viewModel: ReaderViewModel, millis: Long): Boolean = try {
        compose.waitUntil(millis) { viewModel.state.value.menuVisible }
        true
    } catch (e: ComposeTimeoutException) {
        false
    }

    /**
     * A press held in the middle of the screen for twice the platform's long press timeout, the
     * owner's gesture for the page sheet, delivered to the window as [openTheMenu]'s tap is.
     */
    private fun longPressTheMiddle() {
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels / 2f
        val held = ViewConfiguration.getLongPressTimeout() * 2L
        val down = SystemClock.uptimeMillis()
        val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        try {
            instrumentation.sendPointerSync(press)
        } finally {
            press.recycle()
        }
        SystemClock.sleep(held)
        val release = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.sendPointerSync(release)
        } finally {
            release.recycle()
        }
    }

    /**
     * Waits until no dialog window is left. A tap sent while the closed note dialog's window is still
     * there goes to that window rather than to the page, and the menu does not open: 4 of 14 runs on
     * the API 26 AVD failed that way before this wait.
     */
    private fun awaitNoDialogWindow() {
        awaitTrue("a dialog window stayed open") { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        instrumentation.waitForIdleSync()
    }

    /** The fixture chapter's notes as the table holds them, page index and text. */
    private fun storedNotes(chapterId: Long): List<Pair<Long, String>> =
        runBlocking { graph.bookmarkNoteRepository.getByChapterId(chapterId) }.map { it.pageIndex to it.note }

    /**
     * Unit BN's evidence: the screen written to the app's external files under `bn-evidence/`, only
     * when the run passes `saikou.evidence.screenshots=true`, for the host to pull. A suite run
     * writes nothing. Read off the bundle rather than through `Args.argument`, which strict mode
     * turns into a failure when the argument is absent.
     */
    private fun saveEvidenceScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("saikou.evidence.screenshots") != "true") return
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation took no screenshot" }
        val readable = if (screenshot.config == Bitmap.Config.HARDWARE) {
            screenshot.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            screenshot
        }
        val directory = File(checkNotNull(context.getExternalFilesDir(null)) { "no external files directory" }, "bn-evidence")
        directory.mkdirs()
        try {
            File(directory, "$name.png").outputStream().use { readable.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            if (readable !== screenshot) readable.recycle()
            screenshot.recycle()
        }
    }

    /**
     * The menu's page slider, dragged from near its start to its middle and let go there. The slider
     * runs from page 1 to [PAGE_COUNT], so its middle is [TARGET_PAGE] in either direction. Each
     * move sends `SliderMoved`, and the release sends `SliderReleased`, which is what moves the
     * viewer rather than only the number.
     *
     * A drag rather than a tap, and the release is a separate gesture after the state has caught up,
     * because the release reports the slider's value and that value is the page the state last
     * published, written back on recomposition. A tap on the track presses and releases inside one
     * event, before any recomposition, and so releases on the page from before the press: the
     * reader goes back to where it was (reported in unit K's report as a defect of the chrome). A
     * finger's drag spans many frames, so the release reads the page the drag reached; the wait
     * between the halves stands in for those frames.
     */
    private fun moveWithTheSlider(viewModel: ReaderViewModel) {
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        slider.performTouchInput {
            down(percentOffset(SLIDER_GRAB_FRACTION, 0.5f))
            moveTo(center)
        }
        awaitState(viewModel, "dragging the slider to its middle did not scrub to page ${TARGET_PAGE + 1}") {
            it.currentPage == TARGET_PAGE && it.scrubbing
        }
        slider.performTouchInput { up() }
        awaitState(viewModel, "letting go of the slider did not settle the reader on page ${TARGET_PAGE + 1}") {
            it.currentPage == TARGET_PAGE && !it.scrubbing
        }
        val chapterId = fixture.chapters.single().id
        awaitTrue("letting go of the slider did not write page ${TARGET_PAGE + 1} to the chapter's row") {
            runBlocking { graph.chapterRepository.getChapterById(chapterId) }?.lastPageRead == TARGET_PAGE.toLong()
        }
    }

    /**
     * The bottom bar's settings button, then the mode's chip on the sheet's first tab.
     *
     * The pager hides the menu when it lands on a page it did not scroll to itself, which is what a
     * slider release is, so the menu is opened again first if it went. The sheet names each mode
     * twice: in the entry's reading mode row on the first tab, and in the general tab's row of modes
     * that get a vertical slider, which the sheet's pager composes beside it. Every row of a tab
     * flattens into that tab's one scrolling column, so a chip's siblings are the whole tab, and
     * only the first tab carries the "For this series" heading. The heading is plain text, which
     * the merged tree folds into the sheet's surface, so the chip is found in the unmerged tree:
     * the clickable node holding the mode's label beside that heading. It is scrolled into view
     * before the tap.
     */
    private fun chooseTheModeInTheSettingsSheet(viewModel: ReaderViewModel, mode: ReadingMode) {
        compose.waitForIdle()
        if (!viewModel.state.value.menuVisible) openTheMenu(viewModel)
        compose.onNodeWithContentDescription(string(R.string.action_settings)).performClick()
        awaitState(viewModel, "the settings button did not open the settings sheet") {
            it.dialog == ReaderDialog.Settings
        }
        compose.onNode(
            hasClickAction() and
                hasAnyChild(hasText(string(mode.stringRes))) and
                hasAnySibling(hasText(string(R.string.pref_category_for_this_series))),
            useUnmergedTree = true,
        )
            .performScrollTo()
            .performClick()
    }

    /** Back, sent to the sheet's window; the sheet's own dismiss is what clears the dialog. */
    private fun closeTheSettingsSheet(viewModel: ReaderViewModel) {
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitState(viewModel, "back did not close the settings sheet") { it.dialog == null }
    }

    private fun assertRebuiltOnce(
        mode: ReadingMode,
        before: ReaderState,
        switched: ReaderState,
        after: ReaderState,
        markersBefore: List<String>,
    ) {
        val expected = before.viewerGeneration + 1
        assertEquals("the switch moved the viewer generation by more than one", expected, switched.viewerGeneration)
        assertEquals("the viewer generation moved again after the switch", expected, after.viewerGeneration)

        val added = viewerMarkers().drop(markersBefore.size)
        assertEquals(
            "the switch built ${added.size} viewers where one was expected: $added",
            1,
            added.size,
        )
        assertTrue(
            "the viewer built was not the $mode viewer at generation $expected: ${added.single()}",
            added.single().contains("reader.viewer mode=${mode.name} gen=$expected"),
        )
    }

    private fun assertThePageHeld(before: ReaderState, after: ReaderState) {
        assertTrue("the session left its ready phase", after.phase is ReaderPhase.Ready)
        assertEquals(
            "the switch opened another chapter",
            before.currentChapter?.chapter?.id,
            after.currentChapter?.chapter?.id,
        )
        assertEquals("the switch changed the page count", before.totalPages, after.totalPages)
        assertEquals("the switch moved the reader to another page", TARGET_PAGE, after.currentPage)
    }

    private fun assertTheOrientationFollowed(scenario: ActivityScenario<ReaderActivity>, after: ReaderState) {
        val wanted = with(ModeResolver) { after.orientation.toActivityOrientation() }
        var requested = Int.MIN_VALUE
        scenario.onActivity { requested = it.requestedOrientation }
        assertEquals("the rebuild dropped the orientation the session asked for", wanted, requested)
    }

    /**
     * Waits until the middle of the screen is [page]'s colour. The screenshot is the display's, so
     * this is what an owner would see, and a timeout names the colour that was there and the page
     * it belongs to, if any.
     */
    private fun awaitTheScreenShows(page: Int, what: String) {
        val wanted = ReaderFixture.pageColor(page)
        var seen = 0
        try {
            compose.waitUntil(SCREEN_TIMEOUT_MILLIS) {
                seen = middlePixel()
                sameColor(seen, wanted)
            }
        } catch (e: ComposeTimeoutException) {
            val shown = (0 until PAGE_COUNT).firstOrNull { sameColor(seen, ReaderFixture.pageColor(it)) }
            throw AssertionError(
                "$what showed %08X in the middle of the screen, %s, where page ${page + 1} is %08X"
                    .format(seen, shown?.let { "page ${it + 1}'s colour" } ?: "no page's colour", wanted),
                e,
            )
        }
    }

    /** Newer platforms hand the screenshot back as a hardware bitmap, which has no pixel access. */
    private fun middlePixel(): Int {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation took no screenshot" }
        val readable = if (screenshot.config == Bitmap.Config.HARDWARE) {
            screenshot.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            screenshot
        }
        try {
            return readable.getPixel(readable.width / 2, readable.height / 2)
        } finally {
            if (readable !== screenshot) readable.recycle()
            screenshot.recycle()
        }
    }

    private fun sameColor(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
            abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
            abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE

    /**
     * Every `reader.viewer` marker this process has logged, oldest first. Read from the process's
     * own log through the shell, because the marker is written by `ReaderBenchHooks`, which is the
     * only record of a viewer actually constructed.
     */
    private fun viewerMarkers(): List<String> =
        GateFragments.shell("logcat -d --pid=${Process.myPid()} -s ${VIEWER_MARKER_TAG}:I")
            .lineSequence()
            .filter { VIEWER_MARKER in it }
            .toList()

    @Suppress("DEPRECATION")
    private fun viewModelOf(scenario: ActivityScenario<ReaderActivity>): ReaderViewModel {
        lateinit var viewModel: ReaderViewModel
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java]
        }
        return viewModel
    }

    private fun string(id: Int): String = context.getString(id)

    /**
     * Waits through the compose rule rather than on the flow. The rule drives every composition in
     * the process from its own clock, which only moves while the test is synchronising with it, so
     * a wait that blocked on the view model's flow alone would hold back the very recomposition
     * that builds the viewer. Returns the state that met [condition].
     */
    private fun awaitState(
        viewModel: ReaderViewModel,
        message: String,
        condition: (ReaderState) -> Boolean,
    ): ReaderState {
        var met: ReaderState? = null
        var last = viewModel.state.value
        try {
            awaitTrue(message) {
                last = viewModel.state.value
                last.takeIf(condition)?.also { met = it } != null
            }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; the reader was at ${summary(last)}", e.cause)
        }
        return checkNotNull(met)
    }

    private fun summary(state: ReaderState): String =
        "${state.phase::class.simpleName}, ${state.readingMode} (entry ${state.mangaReadingMode}) " +
            "generation ${state.viewerGeneration}, " +
            "page ${state.currentPage + 1} of ${state.totalPages}, scrubbing ${state.scrubbing}, " +
            "menu ${state.menuVisible}, dialog ${state.dialog}"

    private fun awaitTrue(message: String, timeoutMillis: Long = STATE_TIMEOUT_MILLIS, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$message (after $timeoutMillis ms)", e)
        }
    }

    private companion object {
        const val LOG_TAG = "SaikouReaderModeSwitch"

        const val FIXTURE_PREFIX = "saikou-reader-mode-"

        /** Five, so the target page is neither the first nor the last in either direction. */
        const val PAGE_COUNT = 5

        /** Page 3, 0-based. */
        const val TARGET_PAGE = 2

        /** `ReaderBenchHooks.LOG_TAG`, and the prefix of the marker `ReaderViewModel` writes per viewer built. */
        const val VIEWER_MARKER_TAG = "SaikouBench"
        const val VIEWER_MARKER = "reader.viewer mode="

        /** A cold activity and a session, a write through the entry's flow, or a page move. */
        const val STATE_TIMEOUT_MILLIS = 30_000L

        /** A viewer built, positioned and drawn. */
        const val SCREEN_TIMEOUT_MILLIS = 15_000L

        const val SETTLE_MILLIS = 1_500L

        /** The note the page note method writes on page 1. */
        const val NOTE = "art reference"

        const val TAP_MILLIS = 50L

        /** How long a late tap gets to open the menu before [openTheMenu]'s one retry. */
        const val LATE_TAP_WAIT_MILLIS = 3_000L

        /** Where on the slider the drag starts, as a fraction of its width: inside the track. */
        const val SLIDER_GRAB_FRACTION = 0.1f

        /** Per channel. The fixture's colours are 144 apart on every channel that differs. */
        const val COLOR_TOLERANCE = 24

    }
}
