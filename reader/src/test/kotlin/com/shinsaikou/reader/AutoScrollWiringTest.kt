package com.shinsaikou.reader

import com.shinsaikou.domain.manga.model.ReadingMode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the Experimental long strip auto-scroll is hooked into the reader's composables and the
 * vendored viewer. Those need a Compose runtime or a view tree, which this module's JVM tests do not
 * have, so the hooks are read off the source text, as `SettingsSearchIndexTest` reads the settings
 * rows. `StripAutoScrollTest` covers what the controller does once started.
 */
class AutoScrollWiringTest {

    /** The unit test process runs in the `reader/` module directory. */
    private fun source(path: String): String = File("src/main/kotlin/$path").let { file ->
        check(file.isFile) { "Expected ${file.absolutePath}; the working directory is assumed to be reader/." }
        file.readText()
    }

    /**
     * The text of the function [name], from its `fun` to the brace that closes it. The body opens at
     * the first brace after the parameter list's closing parenthesis, found by depth, so a call in
     * the body that ends a line is not taken for the end of a one-line signature.
     */
    private fun body(source: String, name: String): String {
        val match = Regex("""\bfun (<[^>]*> )?$name\(""").find(source)
        checkNotNull(match) { "No function $name" }
        val start = match.range.first
        var parens = 0
        var close = match.range.last
        while (close < source.length) {
            when (source[close]) {
                '(' -> parens++
                ')' -> if (--parens == 0) break
            }
            close++
        }
        val open = source.indexOf('{', close)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, i + 1)
            }
        }
        error("Function $name is not closed")
    }

    private fun squashed(text: String) = text.replace(Regex("""\s+"""), " ")

    private val chrome get() = source("com/shinsaikou/reader/ui/ReaderChrome.kt")
    private val screen get() = source("com/shinsaikou/reader/ui/ReaderScreen.kt")
    private val viewer get() = source("eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt")

    // The bottom bar's button.

    @Test
    fun `the bar is handed the button only while the switch is on and the mode is a long strip`() {
        squashed(chrome) shouldContain
            "}.takeIf { stripAutoScroll && state.readingMode.isWebtoon }"
    }

    @Test
    fun `the switch the bar reads is the stored auto-scroll preference`() {
        squashed(chrome) shouldContain "val stripAutoScroll by preferences.stripAutoScroll.collectAsState()"
    }

    @Test
    fun `a tap on the button closes the menu first and then starts the scroll`() {
        val tap = squashed(chrome).substringAfter("onClickAutoScroll = {").substringBefore("}.takeIf")
        tap.trim() shouldBe "onEvent(ReaderEvent.HideMenu) onStartAutoScroll()"
    }

    @Test
    fun `the long strip and continuous vertical are the modes that count as a strip, the paged ones do not`() {
        ReadingMode.entries.filter { it.isWebtoon }.toSet() shouldBe
            setOf(ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL)
        listOf(ReadingMode.DEFAULT, ReadingMode.LEFT_TO_RIGHT, ReadingMode.RIGHT_TO_LEFT, ReadingMode.VERTICAL)
            .forEach { mode -> withClue(mode.name) { mode.isWebtoon shouldBe false } }
    }

    @Test
    fun `the screen starts the scroll on the strip viewer and on no other viewer`() {
        squashed(screen) shouldContain
            "onStartAutoScroll = { (viewerHolder.viewer as? WebtoonViewer)?.let { viewer -> viewer.startAutoScroll()"
    }

    // The tap zone overlay, which a first touch hides and auto-scroll never touches.

    private val overlay get() = source("eu/kanade/tachiyomi/ui/reader/ReaderNavigationOverlayView.kt")

    @Test
    fun `starting the scroll on the strip viewer counts one overlay dismissal, and nothing else counts one`() {
        val start = squashed(screen).substringAfter("onStartAutoScroll = {").substringBefore("},")
        start.trim() shouldBe
            "(viewerHolder.viewer as? WebtoonViewer)?.let { viewer -> viewer.startAutoScroll() overlayDismissals++ }"
        Regex("""overlayDismissals\+\+""").findAll(screen).count() shouldBe 1
    }

    @Test
    fun `the dismissals start at none and reach the overlay`() {
        squashed(screen) shouldContain "var overlayDismissals by remember { mutableIntStateOf(0) }"
        squashed(screen) shouldContain
            "ReaderNavigationOverlay(request = host.navigationOverlay.value, dismissals = overlayDismissals)"
    }

    @Test
    fun `a new dismissal hides the overlay through performClick, once, and the first value hides nothing`() {
        val composable = squashed(body(chrome, "ReaderNavigationOverlay"))
        composable shouldContain "val seen = remember { SeenDismissals(dismissals) }"
        composable shouldContain "if (dismissals != seen.count) { seen.count = dismissals view.performClick() }"
    }

    @Test
    fun `performClick is the path a touch on the overlay takes to hide it`() {
        squashed(body(overlay, "onTouchEvent")) shouldContain "performClick()"
        val click = squashed(body(overlay, "performClick"))
        click shouldContain "if (viewPropertyAnimator == null && isVisible) {"
        click shouldContain ".alpha(0f)"
        click shouldContain "isVisible = false"
    }

    // The shell's stops.

    @Test
    fun `the stops are keyed on the menu and the dialog, so the state open at the start does not stop it`() {
        val stops = squashed(body(screen, "AutoScrollStops"))
        stops shouldContain "val dialogOpen = state.dialog != null"
        stops shouldContain "LaunchedEffect(state.menuVisible, dialogOpen) {"
        stops shouldContain "if (state.menuVisible || dialogOpen) (holder.viewer as? WebtoonViewer)?.stopAutoScroll()"
    }

    @Test
    fun `the effect that stops on the menu or a dialog is the only one keyed on them, and it never starts`() {
        val stops = body(screen, "AutoScrollStops")
        stops shouldNotContain "startAutoScroll"
        Regex("""LaunchedEffect\(""").findAll(stops).count() shouldBe 1
    }

    @Test
    fun `the activity pausing stops the scroll`() {
        squashed(body(screen, "AutoScrollStops")) shouldContain
            "LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { (holder.viewer as? WebtoonViewer)?.stopAutoScroll() }"
    }

    @Test
    fun `the stops are composed with the screen`() {
        squashed(screen) shouldContain "AutoScrollStops(state, viewerHolder)"
    }

    // The viewer's stops.

    @Test
    fun `a move to a page stops the scroll before it moves`() {
        val move = body(viewer, "moveToPage")
        move.indexOf("autoScroll.stop()").let { it >= 0 && it < move.indexOf("adapter.items.indexOf(page)") } shouldBe true
    }

    @Test
    fun `destroy releases the scroll, which removes its frame callback and touch watcher`() {
        body(viewer, "destroy") shouldContain "autoScroll.release()"
    }

    @Test
    fun `the viewer starts the scroll at the speed in the reader preferences`() {
        squashed(viewer) shouldContain
            "fun startAutoScroll() = autoScroll.start(host.readerPreferences.stripAutoScrollSpeed.get())"
        squashed(viewer) shouldContain "fun stopAutoScroll() = autoScroll.stop()"
    }

    @Test
    fun `the viewer tells the controller what is coming from its own item list`() {
        squashed(viewer) shouldContain "StripAutoScroll(recycler) { stripHasMoreComing(adapter.items) }"
    }

    // The sheet's slider.

    @Test
    fun `the sheet shows the speed slider only inside the switch's check`() {
        val page = squashed(source("eu/kanade/presentation/reader/settings/ReadingModePage.kt"))
        val gated = page.substringAfter("val stripAutoScroll by viewModel.preferences.stripAutoScroll.collectAsState() if (stripAutoScroll) {")
        gated shouldContain "label = stringResource(R.string.pref_auto_scroll_speed)"
        gated shouldContain "onChange = viewModel.preferences.stripAutoScrollSpeed::set"
        // The slider is the only thing that mentions the speed, and it is after the gate.
        page.indexOf("pref_auto_scroll_speed") shouldBe page.indexOf("if (stripAutoScroll) {").let { gate ->
            page.indexOf("pref_auto_scroll_speed", gate)
        }
    }
}
