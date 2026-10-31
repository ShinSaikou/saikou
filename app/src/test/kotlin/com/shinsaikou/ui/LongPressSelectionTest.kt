package com.shinsaikou.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A long press on a chapter row selects the run between the current selection and the row, by
 * upstream v0.21.0's `toggleSelection(fromLongPress = true)`, except that the span ends are read from
 * the selection itself. The list shown here is ids 1 to 10 in that order.
 */
class LongPressSelectionTest {

    private val order = (1L..10L).toList()

    private fun press(selection: Set<Long>, pressed: Long) = longPressSelection(order, selection, pressed)

    @Test
    fun `a long press with nothing selected selects only that chapter`() {
        press(emptySet(), 4L) shouldBe setOf(4L)
    }

    @Test
    fun `a long press after the span selects from just past its last chapter through the pressed one`() {
        press(setOf(2L, 3L), 6L) shouldBe setOf(2L, 3L, 4L, 5L, 6L)
    }

    @Test
    fun `a long press right after the span selects only the pressed chapter`() {
        press(setOf(2L, 3L), 4L) shouldBe setOf(2L, 3L, 4L)
    }

    @Test
    fun `a long press before the span selects from the pressed chapter up to just before its first`() {
        press(setOf(6L, 7L), 3L) shouldBe setOf(3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `a long press inside the span selects only the pressed chapter`() {
        press(setOf(2L, 8L), 5L) shouldBe setOf(2L, 5L, 8L)
    }

    @Test
    fun `a long press on a selected chapter deselects it alone`() {
        press(setOf(2L, 3L, 4L), 3L) shouldBe setOf(2L, 4L)
    }

    @Test
    fun `deselecting the only selected chapter empties the selection`() {
        press(setOf(5L), 5L) shouldBe emptySet()
    }

    @Test
    fun `a pressed id missing from the order leaves the selection unchanged`() {
        press(setOf(2L, 3L), 99L) shouldBe setOf(2L, 3L)
        press(emptySet(), 99L) shouldBe emptySet()
    }

    /** Select all then invert leaves an arbitrary set; upstream would fill from the top here. */
    @Test
    fun `after an arbitrary selection a press below the last selected fills only the gap after it`() {
        press(setOf(2L, 4L, 5L), 8L) shouldBe setOf(2L, 4L, 5L, 6L, 7L, 8L)
    }

    @Test
    fun `after an arbitrary selection a press above the first selected fills only the gap before it`() {
        press(setOf(6L, 8L, 9L), 3L) shouldBe setOf(3L, 4L, 5L, 6L, 8L, 9L)
    }

    @Test
    fun `the span ends follow list order, not id order`() {
        val shown = listOf(30L, 20L, 10L, 50L, 40L)
        longPressSelection(shown, setOf(30L), 50L) shouldBe setOf(30L, 20L, 10L, 50L)
        longPressSelection(shown, setOf(10L), 30L) shouldBe setOf(30L, 20L, 10L)
        longPressSelection(shown, setOf(10L), 40L) shouldBe setOf(10L, 50L, 40L)
    }

    @Test
    fun `selected ids missing from the order are ignored for the span and kept`() {
        // 77 was selected, then filtered out of the list; the span is 3..4.
        press(setOf(77L, 3L, 4L), 7L) shouldBe setOf(77L, 3L, 4L, 5L, 6L, 7L)
        press(setOf(77L, 3L, 4L), 1L) shouldBe setOf(77L, 1L, 2L, 3L, 4L)
    }

    @Test
    fun `a selection holding only ids missing from the order is treated as no span`() {
        press(setOf(77L), 5L) shouldBe setOf(77L, 5L)
    }

    @Test
    fun `deselecting a chapter keeps ids missing from the order`() {
        press(setOf(77L, 3L), 3L) shouldBe setOf(77L)
    }

    private fun pressBlock(selection: Set<Long>, vararg pressed: Long) =
        longPressSelection(order, selection, pressed.toList())

    @Test
    fun `a block whose ids are all selected is deselected and nothing else`() {
        pressBlock(setOf(2L, 3L, 4L, 8L), 2L, 3L, 4L) shouldBe setOf(8L)
    }

    @Test
    fun `a partly selected block selects the rest of it`() {
        pressBlock(setOf(3L), 3L, 4L, 5L) shouldBe setOf(3L, 4L, 5L)
    }

    @Test
    fun `a block wholly before the span fills the gap to it`() {
        pressBlock(setOf(8L, 9L), 2L, 3L) shouldBe setOf(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)
    }

    @Test
    fun `a block wholly after the span fills the gap to it`() {
        pressBlock(setOf(1L, 2L), 6L, 7L) shouldBe setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `a block overlapping the span selects only the block`() {
        pressBlock(setOf(2L, 6L), 4L, 5L, 6L, 7L) shouldBe setOf(2L, 4L, 5L, 6L, 7L)
    }

    @Test
    fun `a block with nothing selected is selected alone`() {
        pressBlock(emptySet(), 4L, 5L, 6L) shouldBe setOf(4L, 5L, 6L)
    }

    @Test
    fun `a block with holes leaves the holes unselected and fills only the gap to the span`() {
        // Block 2 and 4 (3 is a hole), span 8: the gap is 5 to 7.
        pressBlock(setOf(8L), 2L, 4L) shouldBe setOf(2L, 4L, 5L, 6L, 7L, 8L)
    }

    @Test
    fun `pressed ids missing from the order are dropped and the ones in the order form the block`() {
        // 99 is not in the order: it is not selected and does not move the block or the gap.
        pressBlock(setOf(8L), 99L, 4L, 5L) shouldBe setOf(4L, 5L, 6L, 7L, 8L)
    }

    @Test
    fun `an empty block leaves the selection unchanged`() {
        pressBlock(setOf(2L, 3L)) shouldBe setOf(2L, 3L)
        pressBlock(emptySet()) shouldBe emptySet()
    }

    @Test
    fun `a block keeps selected ids missing from the order`() {
        pressBlock(setOf(77L, 3L), 6L, 7L) shouldBe setOf(77L, 3L, 4L, 5L, 6L, 7L)
    }
}
