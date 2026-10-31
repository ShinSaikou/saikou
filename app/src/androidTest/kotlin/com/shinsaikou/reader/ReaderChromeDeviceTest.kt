package com.shinsaikou.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.settings.ReaderBackground
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.util.EvidenceCapture
import eu.kanade.presentation.theme.getThemeColorScheme
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import kotlin.math.abs

/**
 * The reader's chrome on a device (unit ID3, look rethink unit look-chrome): a message on the
 * reader's own snackbar, and the chapter transition page drawn on the reader's own background, as
 * upstream's is.
 *
 * The fixture is [ReaderFixture]'s, written by the test process, so nothing here returns early.
 * The page background is pinned for the run to the one furthest from the theme's background (white
 * under a dark theme, black under a light one), so a transition page that drew the theme's
 * background over it fails the check; "Always show chapter transition" is pinned on, since a restored backup can turn
 * it off and the pager then skips the transition into a loaded chapter; and the navigation overlay
 * is pinned off, since a new user's first reader tints the page's tap regions until a tap. All are
 * put back as found.
 */
@RunWith(AndroidJUnit4::class)
class ReaderChromeDeviceTest {

    /** The theme an evidence run names; nothing on any other run. */
    @get:Rule(order = 0)
    val evidenceTheme: TestRule = EvidenceCapture.themeRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    private lateinit var fixture: ReaderFixture
    private lateinit var pageBackground: ReaderBackground
    private val pins = mutableListOf<Pin<*>>()

    @Before
    fun buildTheFixtureAndPinThePreferences() {
        val reader = graph.readerPreferences
        pageBackground = if (isDark(systemNight())) ReaderBackground.WHITE else ReaderBackground.BLACK
        pins += Pin(reader.readerTheme, pageBackground.storedValue)
        pins += Pin(reader.alwaysShowChapterTransition, true)
        pins += Pin(reader.showNavigationOverlayNewUser, false)
        pins += Pin(reader.showNavigationOverlayOnStart, false)
        // The tap regions' overlay still comes up on a second navigation request whatever the two
        // pins above say, tinting the page's edges; with tap navigation off it never draws. These
        // tests page with the keyboard, so nothing here taps a region.
        pins += Pin(reader.navigationModePager, DISABLED_NAVIGATION)
        fixture = ReaderFixture.create(context, graph, FIXTURE_PREFIX, listOf(PAGE_COUNT, PAGE_COUNT))
        fixture.writeReadingMode(ReadingMode.LEFT_TO_RIGHT)
    }

    @After
    fun removeTheFixture() {
        if (::fixture.isInitialized) fixture.remove()
        pins.forEach { it.restore() }
    }

    /** A preference set for the run, and put back afterwards as it was, set or unset. */
    private class Pin<T>(private val preference: Preference<T>, value: T) {
        private val wasSet = preference.isSet()
        private val before = preference.get()

        init {
            preference.set(value)
        }

        fun restore() {
            if (wasSet) preference.set(before) else preference.delete()
        }
    }

    /**
     * `ReaderEffect.ShowMessage` shows on the reader's snackbar, not as a toast: a chapter
     * that fails to load while the reader is open says so in the snackbar's text, on this screen's
     * own composition. The failure is the reducer's real path for an adjacent chapter, sent as the
     * loader would send it.
     */
    @Test
    fun aMessageWhileTheReaderIsOpenShowsOnItsSnackbar() {
        val scenario = open(fixture.chapters.first().id)
        try {
            val viewModel = viewModelOf(scenario)
            val ready = awaitState(viewModel, "the session never became ready") {
                it.phase is ReaderPhase.Ready && it.totalPages > 0
            }
            val message = string(R.string.reader_chapter_load_error)
            assertTrue(
                "the message is on screen before it was sent",
                compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isEmpty(),
            )
            viewModel.send(ReaderEvent.ChapterLoadFailed(checkNotNull(ready.currentChapter), IllegalStateException(FAILURE)))
            awaitTrue("the reader's message never showed on its snackbar") {
                compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty()
            }
            capture("reader-snackbar")

            viewModel.send(ReaderEvent.ShowMenu)
            awaitState(viewModel, "the menu never opened") { it.menuVisible }
            // The view model's state is the screen's a frame later, so the capture waits for the bars
            // themselves: the bottom bar's Settings button is in the tree only while they are shown.
            awaitTrue("the reader's bars never came into the tree") {
                compose.onAllNodes(hasContentDescription(string(R.string.action_settings))).fetchSemanticsNodes().isNotEmpty()
            }
            capture("reader-bars")
            viewModel.send(ReaderEvent.ShowDialog(ReaderDialog.Settings))
            awaitState(viewModel, "the settings sheet never opened") { it.dialog == ReaderDialog.Settings }
            capture("reader-settings")
        } finally {
            scenario.close()
        }
    }

    /**
     * The transition page draws on the reader's background in both of its states: past the last
     * page of chapter 1 it names the next chapter, and past the last page of chapter 2 it says there
     * is none. The background around the transition's text is the page background pinned for the
     * run, not the theme's.
     */
    @Test
    fun theTransitionPageIsOnTheReaderBackgroundWithANextChapterAndWithNone() {
        // The fixture's rows share one source order and the local source lists names descending, so
        // the reader orders "Chapter 2" first with "Chapter 1" as its next (`PagerChapterPreloadTest`).
        val scenario = open(fixture.chapters[1].id)
        try {
            val viewModel = viewModelOf(scenario)
            awaitState(viewModel, "the session never became ready") {
                it.phase is ReaderPhase.Ready && it.totalPages > 0
            }
            val readerBackground = pageBackground.color(systemNight())
            assertTrue(
                "the pinned page background is the theme's background",
                !sameColor(themeBackgroundOf(), readerBackground),
            )

            // A transition page leaves the current chapter as it was, so "Next:" is chapter 2's and
            // "There's no next chapter" chapter 1's.
            pressRightUntilBackground(
                readerBackground,
                string(R.string.transition_next),
                fixture.chapters[1].id,
                viewModel,
            )
            capture("reader-transition-next")

            pressRightUntilBackground(
                readerBackground,
                string(R.string.transition_no_next),
                fixture.chapters[0].id,
                viewModel,
            )
            capture("reader-transition-no-next")
        } finally {
            scenario.close()
        }
    }

    /**
     * The transition page's text takes its night mode from the reader background preference, as
     * upstream's `createReaderThemeContext()` does, not from the app theme: a light app theme over the
     * black reader still draws light text, and a dark app theme over the white reader draws dark
     * text. The text's colour is read off the screen, as the most contrasting pixel inside the
     * bounds of the labels (the compose rule reaches the transition view's own composition root),
     * so it is compared with what is drawn and not with what the theme says. Both transition states
     * are read: "Finished:" on the page with a next chapter, and "Finished:" with the "no next
     * chapter" card on the last one.
     */
    @Test
    fun theTransitionTextFollowsTheReaderBackgroundNotTheAppTheme() {
        val ui = graph.uiPreferences
        val reader = graph.readerPreferences
        val cases = listOf(
            Triple(ThemeMode.LIGHT, ReaderBackground.BLACK, true),
            Triple(ThemeMode.DARK, ReaderBackground.WHITE, false),
        )
        for ((appMode, background, lightText) in cases) {
            val casePins = listOf(Pin(ui.themeMode, appMode), Pin(reader.readerTheme, background.storedValue))
            val scenario = open(fixture.chapters[1].id)
            try {
                val viewModel = viewModelOf(scenario)
                awaitState(viewModel, "the session never became ready") {
                    it.phase is ReaderPhase.Ready && it.totalPages > 0
                }
                val backgroundColor = background.color(systemNight())
                val label = "$appMode app theme over the $background reader"

                pressRightUntilBackground(
                    backgroundColor,
                    string(R.string.transition_next),
                    fixture.chapters[1].id,
                    viewModel,
                )
                assertTextContrasts("$label, with a next chapter", string(R.string.transition_finished), backgroundColor, lightText)
                assertTextContrasts("$label, with a next chapter", string(R.string.transition_next), backgroundColor, lightText)

                pressRightUntilBackground(
                    backgroundColor,
                    string(R.string.transition_no_next),
                    fixture.chapters[0].id,
                    viewModel,
                )
                assertTextContrasts("$label, with no next chapter", string(R.string.transition_finished), backgroundColor, lightText)
                assertTextContrasts("$label, with no next chapter", string(R.string.transition_no_next), backgroundColor, lightText)
            } finally {
                scenario.close()
                casePins.asReversed().forEach { it.restore() }
            }
        }
    }

    private fun open(chapterId: Long): ActivityScenario<ReaderActivity> =
        ActivityScenario.launch(ReaderIntents.newIntent(context, fixture.mangaId, chapterId, page = 0))

    /**
     * The theme's background as `rememberReaderThemeValues` resolves it (app theme, mode, pure
     * black). A precondition guard: it keeps the pinned reader background different from the
     * theme's, so a page that drew the theme's background fails the edge check. What the test
     * asserts is the reader background preference, not this resolution.
     */
    private fun themeBackgroundOf(): Int {
        val ui = graph.uiPreferences
        val scheme = getThemeColorScheme(context, ui.appTheme.get(), isDark(systemNight()), ui.themeDarkAmoled.get())
        return scheme.background.toArgb()
    }

    private fun isDark(systemNight: Boolean): Boolean = when (graph.uiPreferences.themeMode.get()) {
        ThemeMode.SYSTEM -> systemNight
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    private fun systemNight(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Presses DPAD right (the next page in LTR, through the activity's key dispatch) one page at a
     * time until the screen is the transition page that shows [text] while [chapterId] is current:
     * its side margin, half way down, is [background], and the node showing [text] lies inside the
     * screen. The pager preloads its neighbours, so [text] alone is in the tree a page early, with
     * its node off screen; and a page that is still loading is [background] too, so the margin alone
     * proves nothing. All of it has to hold for [STABLE_MILLIS] without a break, so a capture is
     * of the transition and not of the frame before it. A press sent before the window takes key
     * focus is lost, so the presses are counted up to a bound, not fixed.
     */
    private fun pressRightUntilBackground(background: Int, text: String, chapterId: Long, viewModel: ReaderViewModel) {
        var pixel = 0
        repeat(MAX_PRESSES) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            var holdingSince = 0L
            val shown = try {
                compose.waitUntil(BACKGROUND_TIMEOUT_MILLIS) {
                    pixel = edgePixel()
                    val onScreen = sameColor(pixel, background) &&
                        viewModel.state.value.currentChapter?.chapter?.id == chapterId &&
                        onScreenBounds(text) != null
                    if (!onScreen) {
                        holdingSince = 0L
                        false
                    } else {
                        if (holdingSince == 0L) holdingSince = SystemClock.uptimeMillis()
                        SystemClock.uptimeMillis() - holdingSince >= STABLE_MILLIS
                    }
                }
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
            if (shown) return
        }
        val state = viewModel.state.value
        throw AssertionError(
            "the transition page showing \"$text\" never came up on the reader's background after " +
                "$MAX_PRESSES presses: its edge was ${Integer.toHexString(pixel)}, the background " +
                "${Integer.toHexString(background)}, on chapter " +
                "${state.currentChapter?.chapter?.id}, page ${state.currentPage + 1} of ${state.totalPages}",
        )
    }

    /** The bounds in the window of the node whose text contains [text], when it lies inside the screen. */
    private fun onScreenBounds(text: String): Rect? {
        val metrics = context.resources.displayMetrics
        return compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes()
            .map { it.boundsInWindow }
            .firstOrNull { it.width > 0f && it.height > 0f && it.center.x in 0f..metrics.widthPixels.toFloat() && it.center.y in 0f..metrics.heightPixels.toFloat() }
    }

    /**
     * Reads the drawn colour of the text containing [text]: the pixel inside its bounds furthest
     * from [background], which is the glyphs' own colour at the core of a stroke. Light text must
     * read light and dark text dark, and a reading that is near the background (dark on black) fails.
     */
    private fun assertTextContrasts(what: String, text: String, background: Int, lightText: Boolean) {
        val bounds = onScreenBounds(text)
        assertNotNull("$what: \"$text\" is not on screen", bounds)
        checkNotNull(bounds)
        val drawn = withScreenshot { shot ->
            val left = bounds.left.toInt().coerceIn(0, shot.width - 1)
            val right = bounds.right.toInt().coerceIn(left + 1, shot.width)
            val top = bounds.top.toInt().coerceIn(0, shot.height - 1)
            val bottom = bounds.bottom.toInt().coerceIn(top + 1, shot.height)
            var best = background
            for (y in top until bottom) {
                for (x in left until right) {
                    val candidate = shot.getPixel(x, y)
                    if (distance(candidate, background) > distance(best, background)) best = candidate
                }
            }
            best
        }
        val luma = (Color.red(drawn) * 299 + Color.green(drawn) * 587 + Color.blue(drawn) * 114) / 1000
        val readable = if (lightText) luma >= LIGHT_TEXT_MIN_LUMA else luma <= DARK_TEXT_MAX_LUMA
        assertTrue(
            "$what: \"$text\" is drawn in ${Integer.toHexString(drawn)} (luma $luma) on " +
                "${Integer.toHexString(background)}, expected ${if (lightText) "light" else "dark"} text",
            readable,
        )
    }

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun edgePixel(): Int = withScreenshot { it.getPixel((EDGE_DP * context.resources.displayMetrics.density).toInt(), it.height / 2) }

    private fun <T> withScreenshot(read: (Bitmap) -> T): T {
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation took no screenshot" }
        val readable = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        return try {
            read(readable)
        } finally {
            if (readable !== shot) readable.recycle()
            shot.recycle()
        }
    }

    private fun sameColor(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
            abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
            abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE

    @Suppress("DEPRECATION")
    private fun viewModelOf(scenario: ActivityScenario<ReaderActivity>): ReaderViewModel {
        lateinit var viewModel: ReaderViewModel
        scenario.onActivity { activity -> viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java] }
        return viewModel
    }

    private fun awaitState(viewModel: ReaderViewModel, message: String, condition: (ReaderState) -> Boolean): ReaderState {
        var met: ReaderState? = null
        awaitTrue(message) { viewModel.state.value.takeIf(condition)?.also { met = it } != null }
        return checkNotNull(met)
    }

    /** Through the compose rule, which drives every composition in the process, as `ReaderModeSwitchTest` waits. */
    private fun awaitTrue(message: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(STATE_TIMEOUT_MILLIS) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$message (after $STATE_TIMEOUT_MILLIS ms)", e)
        }
    }

    /**
     * An evidence screenshot once the reader's animations are done: the snackbar, the bars and the
     * settings sheet are in the tree before their enter animations have drawn them, and the compose
     * rule's idle waits those out where the instrumentation's does not.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        EvidenceCapture.screenshot(EVIDENCE_UNIT, name)
    }

    private fun string(id: Int): String = context.getString(id)

    private companion object {
        const val EVIDENCE_UNIT = "look-chrome"
        const val FIXTURE_PREFIX = "saikou-reader-chrome-"
        const val PAGE_COUNT = 2
        const val FAILURE = "phase5-chrome-2 fixture failure"

        /** `PagerConfig.updateNavigation`'s `DisabledNavigation`. */
        const val DISABLED_NAVIGATION = 5

        /** Inside the pager holder's 64 dp side padding. */
        const val EDGE_DP = 16
        const val COLOR_TOLERANCE = 3
        const val BACKGROUND_TIMEOUT_MILLIS = 2_000L

        /** How long the transition has to hold on screen before it counts as shown. */
        const val STABLE_MILLIS = 300L

        /** Light text on black reads above this luma, dark text on white below the other. */
        const val LIGHT_TEXT_MIN_LUMA = 160
        const val DARK_TEXT_MAX_LUMA = 96

        /** Two pages and a transition to each target, with room for presses lost before focus. */
        const val MAX_PRESSES = 8

        const val STATE_TIMEOUT_MILLIS = 30_000L
    }
}
