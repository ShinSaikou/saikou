package com.shinsaikou.ui

/**
 * The selection after a long press on the rows holding [pressed], with [order] the ids as the list
 * shows them: one chapter's row on the entry screen, a chapter's row or a fold's chapters on the
 * Updates tab, an entry in a source's listing or in a library tab. As in upstream, the first long
 * press starts a selection, and one on unselected rows
 * outside the selected span also selects every row between the span and them, so chapters 1 to 50
 * take two long presses. One that overlaps the span or sits inside it selects the pressed rows
 * alone, and one on rows that are all selected deselects them. Upstream keeps the span's ends in a
 * field that every tap moves and that select all and invert reset, after which its next long press
 * selects from the top of the list; here the ends are read from the selection itself, so they
 * always match what is shown selected.
 *
 * Ids missing from [order] take no part in the span and stay as they are in the selection; a press
 * none of whose ids is in [order] leaves the selection unchanged.
 */
internal fun longPressSelection(order: List<Long>, selection: Set<Long>, pressed: Collection<Long>): Set<Long> {
    if (selection.containsAll(pressed)) return selection - pressed.toSet()
    val block = pressed.map(order::indexOf).filter { it >= 0 }
    if (block.isEmpty()) return selection
    val blockStart = block.min()
    val blockEnd = block.max()
    val first = order.indexOfFirst { it in selection }
    val last = order.indexOfLast { it in selection }
    val gap = when {
        first < 0 -> IntRange.EMPTY
        blockEnd < first -> blockEnd + 1 until first
        blockStart > last -> last + 1 until blockStart
        else -> IntRange.EMPTY
    }
    return selection + block.map { order[it] } + gap.map { order[it] }
}

/** A long press on one row: the block rule above with a block of one. */
internal fun longPressSelection(order: List<Long>, selection: Set<Long>, pressed: Long): Set<Long> =
    longPressSelection(order, selection, listOf(pressed))
