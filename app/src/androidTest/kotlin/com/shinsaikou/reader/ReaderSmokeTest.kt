package com.shinsaikou.reader

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.shinsaikou.SaikouApp
import com.shinsaikou.core.common.preferences.SecurityPreferences.SecureScreenMode
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.util.Args
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationOverlayView
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference

/**
 * The reader opened on a real chapter, in both viewer families. Contract 01 section 8.3.
 *
 * `:reader` already has a device suite over the pipeline's leaves: the probe, the sample size rule,
 * the tall-strip splitter, the chapter cache, the page loaders and the image view. Every one of
 * them is exercised through its own class. What no test covers is the join: an activity started
 * from an intent, a session built out of database rows, a page list produced by a real source, an
 * image decoded through the real pipeline, and a page turn that moves the reader's own state. That
 * chain is what an owner experiences as "the reader works", and it has never been asserted.
 *
 * The fixture is a Local source entry this process writes itself, [ReaderFixture], which says why
 * it is built on the device rather than pushed. It needs nothing from the owner, so this test
 * asserts on every machine rather than returning early, which is why it has no argument gate.
 *
 * ### What "renders a page" can honestly mean from here
 *
 * The viewers are Android views inside the composition and they publish no semantics, so a Compose
 * assertion can see the reader's chrome and never a page. What is asserted instead is the state the
 * viewer is handed, plus the one mark the viewer leaves behind on the page it loaded: the session
 * reaches [ReaderPhase.Ready], the page list has the length the fixture has, and page one carries a
 * [com.shinsaikou.reader.pipeline.PageRoute].
 *
 * The route is the load-bearing half. A local page is [PageState.Ready] the moment the page list
 * exists, because its bytes are already on disk, so Ready on its own says no more than that the
 * file was listed, and it is exactly what kept this test green against a reader whose page area
 * stayed black (carry-over 12). The probe that decides a page's route runs inside the loader, and
 * the loader runs when the viewer's own holder first binds the page, so a route on page one is the
 * viewer saying it went through the page. That is the check `BenchContentTest.hold` makes for the
 * same reason, and this test now makes it in both families.
 *
 * A page that failed to decode still lands in [PageState.Error] and fails here with its own error,
 * which is the other failure this test exists to catch: the reader's first cbz page killed the
 * process once already (ruling R27).
 *
 * `ReaderActivity`, `ReaderIntents` and `ReaderViewModel` are named without imports because this
 * test shares their package, which is the package contract 01 section 8.3 puts this file in.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = instrumentation.targetContext

    private val app = context.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    private lateinit var fixture: ReaderFixture

    private val mangaId: Long get() = fixture.mangaId

    private val chapterId: Long get() = fixture.chapters.single().id

    @Before
    fun buildTheFixtureAndSeedTheRows() {
        fixture = ReaderFixture.create(context, graph, FIXTURE_PREFIX, listOf(PAGE_COUNT))
    }

    /** [ReaderFixture.remove] soft-deletes the rows and deletes the directory. */
    @After
    fun removeTheFixture() {
        if (::fixture.isInitialized) fixture.remove()
    }

    @Test
    fun thePagedViewerOpensThePageAndTurnsIt() {
        openAndTurnAPage(ReadingMode.LEFT_TO_RIGHT)
    }

    @Test
    fun theWebtoonViewerOpensThePageAndTurnsIt() {
        openAndTurnAPage(ReadingMode.WEBTOON)
    }

    @Test
    fun thePagedViewerKeepsItsSessionThroughARotationIntoLandscape() {
        openAndTurnAPage(ReadingMode.LEFT_TO_RIGHT, rotateIntoLandscape = true)
    }

    @Test
    fun theWebtoonViewerKeepsItsSessionThroughARotationIntoLandscape() {
        openAndTurnAPage(ReadingMode.WEBTOON, rotateIntoLandscape = true)
    }

    /**
     * A swipe turns the page, in each of the three paged modes, sent to the window as a finger's
     * would arrive. The turns above send [ReaderEvent.PageSelected], the event the pager raises once
     * it has turned, so they stayed green while no swipe could turn a page: the page view's touch
     * down request and its release on the first move had Compose cancel the pager's gesture, and
     * only taps turned pages (`ViewerTouchFrame`).
     */
    @Test
    fun aSwipeToTheLeftTurnsALeftToRightPage() {
        swipeAndTurnAPage(ReadingMode.LEFT_TO_RIGHT, Swipe(0.8f, 0.5f, 0.2f, 0.5f))
    }

    @Test
    fun aSwipeToTheRightTurnsARightToLeftPage() {
        swipeAndTurnAPage(ReadingMode.RIGHT_TO_LEFT, Swipe(0.2f, 0.5f, 0.8f, 0.5f))
    }

    @Test
    fun aSwipeUpTurnsAVerticalPage() {
        swipeAndTurnAPage(ReadingMode.VERTICAL, Swipe(0.5f, 0.75f, 0.5f, 0.25f))
    }

    /**
     * With "Show tap zones overlay" off and the first use overlay already seen, the tap regions stay
     * off the page when the reader opens. The overlay view was built visible, and its first
     * navigation returns early for a visible view, so the regions were drawn on every open until
     * the first touch. The tap zones are pinned to the default, which has regions to draw.
     */
    @Test
    fun theTapZonesOverlayStaysHiddenOnOpenWhileItsSettingIsOff() {
        val reader = graph.readerPreferences
        pinned(reader.showNavigationOverlayOnStart to false, reader.showNavigationOverlayNewUser to false) {
            pinned(reader.navigationModePager to 0) {
                fixture.writeReadingMode(ReadingMode.LEFT_TO_RIGHT)
                openReady { scenario ->
                    awaitTrue(PAGE_TIMEOUT_MILLIS, "page 1's image view never became ready") {
                        pageImageReady(scenario)
                    }
                    var shown = true
                    scenario.onActivity { activity ->
                        shown = viewsUnder<ReaderNavigationOverlayView>(activity.window.decorView).any { it.isShown }
                    }
                    assertFalse("the tap zones overlay showed on open with its setting off", shown)
                }
            }
        }
    }

    /**
     * A tap opens the menu after a sideways fling on a zoomed long strip. The fling marked the strip
     * as scrolling by hand, which drops the next tap as one that stops a scroll, but a sideways
     * fling never scrolls the list, so nothing cleared the mark and every later tap was dropped.
     */
    @Test
    fun aTapOpensTheMenuAfterASidewaysFlingOnAZoomedLongStrip() {
        fixture.writeReadingMode(ReadingMode.WEBTOON)
        openReady { scenario ->
            val viewModel = viewModelOf(scenario)
            awaitTrue(PAGE_TIMEOUT_MILLIS, "page 1's image view never became ready") { pageImageReady(scenario) }

            tap(0.5f, 0.5f)
            SystemClock.sleep(DOUBLE_TAP_GAP_MILLIS)
            tap(0.5f, 0.5f)
            awaitTrue(TURN_TIMEOUT_MILLIS, "a double tap did not zoom the long strip") { stripScale(scenario) > 1f }

            send(Swipe(0.3f, 0.5f, 0.8f, 0.5f))
            SystemClock.sleep(SETTLE_MILLIS)
            assertFalse("the menu was already open before the tap", viewModel.state.value.menuVisible)

            tap(0.5f, 0.5f)
            awaitTrue(TURN_TIMEOUT_MILLIS, "a tap after a sideways fling did not open the menu") {
                viewModel.state.value.menuVisible
            }
        }
    }

    /**
     * Upstream's secure screen on the reader's window (phase 4 unit AL). `ReaderActivity` lives in
     * `:reader`, which cannot reach `:app`, so the flag on its window is what the application's
     * `AppLock` callbacks exist to deliver. Under Never the window has no `FLAG_SECURE` even with
     * incognito mode on, which Incognito would secure; Always set while the reader is open sets it
     * through the same live collection; and a reader opened under Always has it. Both preferences are
     * put back as they were.
     */
    @Test
    fun theReaderWindowIsSecureUnderAlwaysAndNotUnderNever() {
        val secureScreen = graph.securityPreferences.secureScreen
        val incognito = graph.basePreferences.incognitoMode
        val secureScreenWas = secureScreen.takeIf { it.isSet() }?.get()
        val incognitoWas = incognito.takeIf { it.isSet() }?.get()
        try {
            incognito.set(true)
            secureScreen.set(SecureScreenMode.NEVER)
            openReady { scenario ->
                assertFalse("the reader's window is secure under Never", windowIsSecure(scenario))
                secureScreen.set(SecureScreenMode.ALWAYS)
                awaitTrue(SECURE_TIMEOUT_MILLIS, "the reader's window stayed open to screenshots after Always was set") {
                    windowIsSecure(scenario)
                }
            }
            openReady { scenario ->
                awaitTrue(SECURE_TIMEOUT_MILLIS, "a reader opened under Always has no FLAG_SECURE") {
                    windowIsSecure(scenario)
                }
            }
        } finally {
            secureScreenWas?.let(secureScreen::set) ?: secureScreen.delete()
            incognitoWas?.let(incognito::set) ?: incognito.delete()
        }
    }

    /** The reader opened on page one of the fixture and [block] run once its session is ready. */
    private fun openReady(block: (ActivityScenario<ReaderActivity>) -> Unit) {
        val scenario = ActivityScenario.launch<ReaderActivity>(
            ReaderIntents.newIntent(context, mangaId, chapterId, page = 0),
        )
        try {
            val viewModel = viewModelOf(scenario)
            await(OPEN_TIMEOUT_MILLIS, "the session never became ready") {
                viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 }
            }
            block(scenario)
        } finally {
            scenario.close()
        }
    }

    private fun windowIsSecure(scenario: ActivityScenario<ReaderActivity>): Boolean {
        var secure = false
        scenario.onActivity { secure = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        return secure
    }

    /**
     * The mode is written onto the fixture's own entry before the reader opens, with the same
     * `viewerFlags.withReadingMode(mode)` update `BenchFixtureSeeder` performs, so both viewers are
     * exercised in the configuration an owner's library actually holds.
     *
     * An earlier version passed a reading-mode intent extra instead, on the objection that writing
     * the flag would leave the device holding a reading mode this test chose. The row is the test's
     * own and [removeTheFixture] soft-deletes it, so nothing of this test's choosing is visible in
     * the library afterwards. A soft delete marks the row rather than removing it, so the row and
     * the flag it carries do stay in the database; what the objection was about, an owner meeting a
     * chapter that opens in a mode they did not pick, cannot happen. The objection is spent and the
     * extra is retired with it (ruling R39). Reading the global preference remains no option: the
     * run would then depend on a setting the owner may have changed.
     */
    private fun openAndTurnAPage(mode: ReadingMode, rotateIntoLandscape: Boolean = false) {
        // What proves the write is assertReady below, which reads the mode back off the session the
        // reader built from that row.
        fixture.writeReadingMode(mode)

        // Page 0 by name: with no page the reader resumes where the chapter was left, and this
        // test turns from page 1.
        val scenario = ActivityScenario.launch<ReaderActivity>(
            ReaderIntents.newIntent(context, mangaId, chapterId, page = 0),
        )
        try {
            val viewModel = viewModelOf(scenario)

            val ready = await(OPEN_TIMEOUT_MILLIS, "the session never became ready") {
                viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 }
            }
            assertReady(mode, ready)

            val pages = ready.currentChapter?.pages
            assertNotNull("the current chapter published no page list", pages)

            // The route, not the status, is what says a viewer was here. A local page is Ready as
            // soon as the page list is built, so waiting for Ready would pass against a reader that
            // drew nothing at all; the route is set by the loader the viewer's holder calls. The
            // wait also ends on Error, so a page whose probe threw reports its own failure on the
            // next line instead of timing out with the wrong message.
            val first = pages!!.first()
            awaitTrue(PAGE_TIMEOUT_MILLIS, "the viewer never loaded page 1") {
                first.route != null || first.status is PageState.Error
            }
            assertTrue("page 1 did not decode: ${first.status}", first.status is PageState.Ready)
            logcat(LOG_TAG, LogPriority.INFO) {
                "${mode.name}: ${ready.totalPages} pages, page 1 route ${first.route}, " +
                    "${first.partCount} part(s)"
            }

            if (rotateIntoLandscape) rotateIntoLandscape(scenario, viewModel, first)

            // The viewer's own event, so the turn goes through the reducer the viewer drives rather
            // than through a shortcut written for the test. With the rotation above it is also the
            // assertion that a page still turns once the window has changed shape.
            instrumentation.runOnMainSync { viewModel.send(ReaderEvent.PageSelected(pages[1])) }
            val turned = await(TURN_TIMEOUT_MILLIS, "the reader stayed on page 1 after a page turn") {
                viewModel.state.first { it.currentPage == 1 }
            }
            assertEquals("the page turn moved the reader somewhere else", 1, turned.currentPage)
            assertEquals("the page turn changed the page count", PAGE_COUNT, turned.totalPages)
        } finally {
            scenario.close()
        }
    }

    /**
     * Opens page one in [mode], waits for its image view to be ready, sends [swipe] and waits for
     * page two. The wait is on the image view rather than the page list because the view's request
     * on touch down is what cancelled the swipe, and it makes it only once its image is ready: a
     * swipe sent before then would turn the page with or without the fix.
     */
    private fun swipeAndTurnAPage(mode: ReadingMode, swipe: Swipe) {
        fixture.writeReadingMode(mode)
        val scenario = ActivityScenario.launch<ReaderActivity>(
            ReaderIntents.newIntent(context, mangaId, chapterId, page = 0),
        )
        try {
            val viewModel = viewModelOf(scenario)
            val ready = await(OPEN_TIMEOUT_MILLIS, "the session never became ready") {
                viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 }
            }
            assertReady(mode, ready)
            awaitTrue(PAGE_TIMEOUT_MILLIS, "page 1's image view never became ready") { pageImageReady(scenario) }

            send(swipe)
            val turned = await(TURN_TIMEOUT_MILLIS, "a swipe left the ${mode.name} reader on page 1") {
                viewModel.state.first { it.currentPage == 1 }
            }
            assertEquals("the swipe moved the reader somewhere else", 1, turned.currentPage)
        } finally {
            scenario.close()
        }
    }

    /**
     * Whether an image view on screen holds a decoded image, read on the main thread. The pager keeps
     * the next page laid out beside the screen, and that one has no visible rectangle.
     */
    private fun pageImageReady(scenario: ActivityScenario<ReaderActivity>): Boolean {
        var ready = false
        scenario.onActivity { activity ->
            ready = imageViewsUnder(activity.window.decorView).any { it.isReady && it.getGlobalVisibleRect(Rect()) }
        }
        return ready
    }

    private fun imageViewsUnder(view: View): Sequence<SubsamplingScaleImageView> = viewsUnder(view)

    private inline fun <reified T : View> viewsUnder(view: View): Sequence<T> = sequence {
        val pending = ArrayDeque(listOf(view))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next is T) yield(next)
            if (next is ViewGroup) pending.addAll(next.children)
        }
    }

    /** The long strip's zoom, which it applies to the list as a scale; 1 when there is no strip. */
    private fun stripScale(scenario: ActivityScenario<ReaderActivity>): Float {
        var scale = 1f
        scenario.onActivity { activity ->
            scale = viewsUnder<WebtoonRecyclerView>(activity.window.decorView).firstOrNull()?.scaleX ?: 1f
        }
        return scale
    }

    /** A tap at a point in fractions of the display, sent to the window as [send]'s swipe is. */
    private fun tap(x: Float, y: Float) {
        val metrics = context.resources.displayMetrics
        val px = x * metrics.widthPixels
        val py = y * metrics.heightPixels
        val down = SystemClock.uptimeMillis()
        val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, px, py, 0)
        val release = MotionEvent.obtain(down, down + TAP_MILLIS, MotionEvent.ACTION_UP, px, py, 0)
        try {
            instrumentation.sendPointerSync(press)
            instrumentation.sendPointerSync(release)
        } finally {
            press.recycle()
            release.recycle()
        }
    }

    /** Sets each preference for [block] and puts it back as it was, set or unset, afterwards. */
    private fun pinned(vararg pins: Pair<Preference<*>, Any>, block: () -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val restores = pins.map { (preference, value) ->
            val typed = preference as Preference<Any>
            val was = typed.takeIf { it.isSet() }?.get()
            typed.set(value)
            ({ if (was != null) typed.set(was) else typed.delete() })
        }
        try {
            block()
        } finally {
            restores.forEach { it() }
        }
    }

    /**
     * A drag from one point to another in fractions of the display, as ten moves over 150 ms: far
     * and fast enough that the pager turns on either of its rules, distance or fling.
     */
    private fun send(swipe: Swipe) {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, step: Int): MotionEvent {
            val fraction = step / SWIPE_STEPS.toFloat()
            val x = (swipe.fromX + (swipe.toX - swipe.fromX) * fraction) * width
            val y = (swipe.fromY + (swipe.toY - swipe.fromY) * fraction) * height
            return MotionEvent.obtain(down, down + step * SWIPE_STEP_MILLIS, action, x, y, 0)
        }
        val events = buildList {
            add(event(MotionEvent.ACTION_DOWN, 0))
            for (step in 1..SWIPE_STEPS) add(event(MotionEvent.ACTION_MOVE, step))
            add(event(MotionEvent.ACTION_UP, SWIPE_STEPS))
        }
        try {
            events.forEach(instrumentation::sendPointerSync)
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    /** A swipe's start and end, each a fraction of the display's width and height. */
    private class Swipe(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    /**
     * The reader rotated, and the only assertion anywhere that it lays out in landscape.
     *
     * The phrase "reader tested in landscape" was true of one number and nothing else: a webtoon
     * scroll jank figure from a bench script, measured on the tablet profile, which says how
     * smoothly the strip moved and nothing about whether the other viewer survives a rotation at
     * all. This is the behaviour half, in both families.
     *
     * Portrait is forced first so that the move into landscape is a real rotation on every device
     * the gate runs. The tablet AVD is landscape by nature, so a test that only asked for landscape
     * would assert nothing there, which is the R29 shape.
     *
     * The override goes in through [ReaderEvent.SetOrientation], the event the reader's own
     * orientation chip sends, rather than by writing `requestedOrientation` from the test. That is
     * the production path end to end: the reducer writes the flag on the entry, the manga flow
     * brings back a new effective orientation, and the screen's `ApplyOrientation` effect is what
     * turns the window. Poking the activity instead would prove the platform rotates and nothing
     * about the reader. The entry it writes to is this test's own row, which [removeTheFixture]
     * soft-deletes, the same argument ruling R39 settled for the reading mode write.
     *
     * Four things are asserted across the turn, and the first two are the ones a regression would
     * break silently. The activity instance has to be the same one: `ReaderActivity` declares
     * `configChanges` for orientation and screen size, and dropping either from the manifest would
     * have the platform recreate it, rebuild the session from the database and land the owner back
     * on the page the history row remembers rather than the page they were on. The viewer
     * generation has to be unchanged, because only a reading mode change may rebuild a viewer and a
     * rotation that bumped it would decode every page again. The session has to still be ready on
     * the same chapter and page, and page one has to still hold its decode.
     *
     * ### Two paths, because a large display ignores the chip (ruling R62)
     *
     * From API 36, for an application targeting 36, the platform ignores a fixed orientation
     * request on a display whose smallest width is at least 600 dp: the activity manager logs
     * "Ignoring requested fixed orientation ... since target sdk 36" and the window never turns.
     * Saikou follows the platform rather than opting out through
     * `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`, which lapses at API 37 anyway. On such a
     * display the chip has nothing to drive, so the test turns the display itself with
     * [UiAutomation.setRotation] and asserts the same invariants across that turn, then puts the
     * device's rotation setting back the way it found it. Everywhere the platform honours the
     * request, the chip path above is the one taken. Which path ran is logged, and under
     * `saikou.expect.tablet`, which the gate's tablet step passes, the large display path is the one
     * required: a tablet run that fell back to the chip would be asserting a different device.
     */
    private fun rotateIntoLandscape(
        scenario: ActivityScenario<ReaderActivity>,
        viewModel: ReaderViewModel,
        firstPage: ReaderPage,
    ) {
        val largeDisplay = platformIgnoresOrientationRequests()
        if (Args.expectTablet && !largeDisplay) {
            throw AssertionError(
                "saikou.expect.tablet is true, but the display is not one the platform ignores " +
                    "orientation requests on, so the large display rotation path was not run.",
            )
        }
        logcat(LOG_TAG, LogPriority.INFO) {
            if (largeDisplay) {
                "rotation path: large display, turned with UiAutomation.setRotation"
            } else {
                "rotation path: the reader's orientation chip"
            }
        }
        val restoreRotation = if (largeDisplay) rotationRestorer() else null
        try {
            assertTheSessionSurvivesTheTurn(scenario, viewModel, firstPage) { orientation, expected ->
                if (largeDisplay) {
                    turnTheDisplayTo(scenario, expected)
                } else {
                    turnTo(scenario, viewModel, orientation, expected)
                }
            }
        } finally {
            restoreRotation?.invoke()
        }
    }

    /** Portrait, then landscape through [turn], and the four invariants across the second turn. */
    private fun assertTheSessionSurvivesTheTurn(
        scenario: ActivityScenario<ReaderActivity>,
        viewModel: ReaderViewModel,
        firstPage: ReaderPage,
        turn: (orientation: ReaderOrientation, expected: Int) -> Unit,
    ) {
        turn(ReaderOrientation.LOCKED_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        val before = activityOf(scenario)
        val portrait = viewModel.state.value

        turn(ReaderOrientation.LOCKED_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        val landscape = viewModel.state.value

        assertSame(
            "the rotation recreated the activity; ReaderActivity is meant to handle the change itself",
            before,
            activityOf(scenario),
        )
        assertEquals(
            "the rotation rebuilt the viewer, which only a reading mode change may do",
            portrait.viewerGeneration,
            landscape.viewerGeneration,
        )
        assertTrue("the session left its ready phase during the rotation", landscape.phase is ReaderPhase.Ready)
        assertEquals(
            "the rotation opened another chapter",
            portrait.currentChapter?.chapter?.id,
            landscape.currentChapter?.chapter?.id,
        )
        assertEquals("the rotation changed the page count", portrait.totalPages, landscape.totalPages)
        assertEquals("the rotation moved the reader to another page", portrait.currentPage, landscape.currentPage)
        assertTrue(
            "page 1 lost its decode across the rotation: ${firstPage.status}",
            firstPage.status is PageState.Ready,
        )
        logcat(LOG_TAG, LogPriority.INFO) {
            "rotated into landscape on ${landscape.totalPages} pages, page ${landscape.currentPage}"
        }
    }

    /**
     * One leg of the rotation. Both waits are needed and neither implies the other: the state flow
     * says the reader took the override, and the configuration says the window actually turned,
     * which is a round trip through the activity manager that the flow knows nothing about.
     */
    private fun turnTo(
        scenario: ActivityScenario<ReaderActivity>,
        viewModel: ReaderViewModel,
        orientation: ReaderOrientation,
        expected: Int,
    ) {
        instrumentation.runOnMainSync { viewModel.send(ReaderEvent.SetOrientation(orientation)) }
        await(ROTATE_TIMEOUT_MILLIS, "the reader never took the $orientation override") {
            viewModel.state.first { it.orientation == orientation }
        }
        awaitTrue(ROTATE_TIMEOUT_MILLIS, "the window never turned to $orientation") {
            activityOf(scenario).resources.configuration.orientation == expected
        }
    }

    /**
     * Whether this is a display the platform turns no fixed orientation request on: API 36 or
     * later, an application targeting 36 or later, and a smallest width of at least 600 dp. The
     * width is read off the configuration rather than a device name, like the tablet UI breakpoint.
     */
    private fun platformIgnoresOrientationRequests(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.BAKLAVA &&
            context.resources.configuration.smallestScreenWidthDp >= LARGE_DISPLAY_SMALLEST_WIDTH_DP

    /**
     * One leg of the rotation on a large display. The freeze is chosen from the display's natural
     * orientation, which is landscape on the tablet and portrait on a phone: rotation 0 is the
     * natural one, and 90 the other.
     */
    private fun turnTheDisplayTo(scenario: ActivityScenario<ReaderActivity>, expected: Int) {
        val current = activityOf(scenario).resources.configuration.orientation
        val rotation = checkNotNull(
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        ) { "no default display" }.rotation
        val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val natural = when {
            !sideways -> current
            current == Configuration.ORIENTATION_PORTRAIT -> Configuration.ORIENTATION_LANDSCAPE
            else -> Configuration.ORIENTATION_PORTRAIT
        }
        val freeze = if (expected == natural) UiAutomation.ROTATION_FREEZE_0 else UiAutomation.ROTATION_FREEZE_90
        assertTrue("UiAutomation refused to set the rotation", instrumentation.uiAutomation.setRotation(freeze))
        val name = if (expected == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
        awaitTrue(ROTATE_TIMEOUT_MILLIS, "the display never turned to $name") {
            activityOf(scenario).resources.configuration.orientation == expected
        }
    }

    /**
     * Puts the device's rotation back as it was: the rotation it was locked to, and then auto-rotate
     * on again if it was on. Unfreezing blindly would switch auto-rotate on for a device that had it
     * off, and every later test on that AVD would run on whatever the emulated sensor says. The
     * freeze comes first in both cases because an unfreeze keeps the locked rotation it finds.
     */
    private fun rotationRestorer(): () -> Unit {
        val resolver = context.contentResolver
        val autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        val locked = when (Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)) {
            Surface.ROTATION_90 -> UiAutomation.ROTATION_FREEZE_90
            Surface.ROTATION_180 -> UiAutomation.ROTATION_FREEZE_180
            Surface.ROTATION_270 -> UiAutomation.ROTATION_FREEZE_270
            else -> UiAutomation.ROTATION_FREEZE_0
        }
        return {
            instrumentation.uiAutomation.setRotation(locked)
            if (autoRotate) instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    /** The activity the scenario is holding, read on the main thread and handed back. */
    private fun activityOf(scenario: ActivityScenario<ReaderActivity>): ReaderActivity {
        lateinit var activity: ReaderActivity
        scenario.onActivity { activity = it }
        return activity
    }

    private fun assertReady(mode: ReadingMode, ready: ReaderState) {
        assertEquals("the entry did not carry the reading mode this test wrote", mode, ready.readingMode)
        assertEquals("the reader opened at another page", 0, ready.currentPage)
        assertEquals("the page list is not the fixture's", PAGE_COUNT, ready.totalPages)
        assertEquals(
            "the reader opened another chapter",
            chapterId,
            ready.currentChapter?.chapter?.id,
        )
    }

    /**
     * The activity's own view model, not a second one. The instance is already in the activity's
     * store under the default key, so the provider returns it and never calls a factory; a factory
     * built here would construct a second reader session against the same rows.
     */
    @Suppress("DEPRECATION")
    private fun viewModelOf(scenario: ActivityScenario<ReaderActivity>): ReaderViewModel {
        lateinit var viewModel: ReaderViewModel
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java]
        }
        return viewModel
    }

    /** A bounded wait with a message: an unbounded one reports only that the runner gave up. */
    private fun <T> await(timeoutMillis: Long, message: String, block: suspend () -> T): T =
        runBlocking {
            try {
                withTimeout(timeoutMillis) { block() }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("$message (after $timeoutMillis ms)", e)
            }
        }

    /**
     * The same bounded wait for state the pipeline mutates on the page rather than publishes in a
     * flow. `route` is one of those: the loader sets it on the page the viewer is holding, so there
     * is nothing to collect and the test polls instead. `BenchContentTest` waits the same way.
     */
    private fun awaitTrue(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("$message (after $timeoutMillis ms)")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val LOG_TAG = "SaikouReaderSmoke"

        const val FIXTURE_PREFIX = "saikou-reader-smoke-"

        /** Three, so a page turn lands on a page that is neither the first nor the last. */
        const val PAGE_COUNT = 3

        /** A cold activity, a session build and a page list off the file system. */
        const val OPEN_TIMEOUT_MILLIS = 30_000L

        /** The viewer binding page 1 and the loader probing a small PNG behind it. */
        const val PAGE_TIMEOUT_MILLIS = 20_000L

        /** One trip through the reducer. */
        const val TURN_TIMEOUT_MILLIS = 10_000L

        const val SWIPE_STEPS = 10

        const val TAP_MILLIS = 50L

        /** Inside the platform's double tap window and past its minimum. */
        const val DOUBLE_TAP_GAP_MILLIS = 80L

        /** The zoomed strip's fling animation, which runs no longer than this. */
        const val SETTLE_MILLIS = 1_500L

        const val SWIPE_STEP_MILLIS = 15L

        /** A write to the entry, the flow coming back, and the platform turning the window. */
        const val ROTATE_TIMEOUT_MILLIS = 20_000L

        /** The smallest width from which API 36 ignores a fixed orientation request (R62). */
        const val LARGE_DISPLAY_SMALLEST_WIDTH_DP = 600

        const val POLL_MILLIS = 100L

        /** A preference write reaching the activity's collector and the window. */
        const val SECURE_TIMEOUT_MILLIS = 5_000L
    }
}
