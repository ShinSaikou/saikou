// Fresh: exercises the internal slider wiring in the vendored ChapterNavigator, so it sits in that package.
package eu.kanade.presentation.reader.components

import androidx.compose.material3.SliderState
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * A tap on the slider's track (plan unit K2 item 3, found by unit K's device run).
 *
 * With `onValueChange` set, the slider reports a value and does not take it in; the caller is
 * expected to recompose it back. A tap reports the value and then finishes within one gesture, so
 * no recomposition comes between the two calls. Both are made here in that order, on a real
 * [SliderState], with nothing recomposing, which is the moment a tap produces.
 */
class ChapterNavigatorSliderTest {

    @Test
    fun `a tap on the track releases on the page it was tapped at`() {
        val (state, moved, released) = sliderOnPage(1)

        state.onValueChange!!(4f)
        state.onValueChangeFinished!!()

        moved shouldContainExactly listOf(3)
        released shouldContainExactly listOf(3)
    }

    @Test
    fun `a drag releases on the last page it reported`() {
        val (state, moved, released) = sliderOnPage(1)

        state.onValueChange!!(2f)
        state.onValueChange!!(3f)
        state.onValueChange!!(5f)
        state.onValueChangeFinished!!()

        moved shouldContainExactly listOf(1, 2, 4)
        released shouldContainExactly listOf(4)
    }

    /** The five-page slider `ChapterNavigator` builds, on 1-based page [page], wired as it wires it. */
    private fun sliderOnPage(page: Int): Triple<SliderState, List<Int>, List<Int>> {
        val moved = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val state = SliderState(value = page.toFloat(), steps = PAGES - 2, valueRange = 1f..PAGES.toFloat())
        state.reportPageIndex(onPageIndexChange = moved::add, onPageIndexRelease = released::add)
        return Triple(state, moved, released)
    }

    private companion object {
        const val PAGES = 5
    }
}
