package com.shinsaikou.reader.viewer

import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.TwoPageLayout
import com.shinsaikou.reader.pipeline.PageRoute
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

// The Experimental two pages side by side (ruling R165 item 2). The pairing is pure, so it is tested
// apart from the views: the paged viewer only asks it which pages share a spread and draws them.

/** How a page takes part in the pairing. */
enum class SpreadShape {
    /** Its size is not known yet. It pairs as [NARROW] until it turns out wide. */
    UNKNOWN,

    NARROW,

    /** Stands alone, and the pairing starts again after it. */
    WIDE,
}

/**
 * One spread of a chapter: [first] alone, or [first] and [second] side by side. Both are positions in
 * the list handed to [SpreadPlanner.plan], in reading order.
 */
data class Spread(val first: Int, val second: Int? = null) {

    val positions: List<Int> get() = listOfNotNull(first, second)

    /** Left to right as drawn: in right to left the earlier page sits on the right. */
    fun onScreen(rightToLeft: Boolean): List<Int> = if (rightToLeft) positions.reversed() else positions
}

object SpreadPlanner {

    /**
     * The spreads of one chapter whose pages have [shapes], in reading order. The first page stands
     * alone, as in print; after it pages pair in order. A wide page stands alone, and the pages after
     * it pair from the next one. A page left over before a wide page or at the end stands alone.
     *
     * [shift] moves the pairing of the chapter's first run of narrow pages by one: the first page
     * then pairs with the second. When the chapter opens on a wide page, that run starts after it
     * and, shifted, its first page stands alone.
     */
    fun plan(shapes: List<SpreadShape>, shift: Boolean): List<Spread> {
        val spreads = mutableListOf<Spread>()
        var position = 0
        var firstRun = true
        while (position < shapes.size) {
            if (shapes[position] == SpreadShape.WIDE) {
                spreads += Spread(position)
                position++
                continue
            }
            if (firstRun && (position == 0) != shift) {
                spreads += Spread(position)
                position++
            }
            firstRun = false
            while (position < shapes.size && shapes[position] != SpreadShape.WIDE) {
                val next = position + 1
                if (next < shapes.size && shapes[next] != SpreadShape.WIDE) {
                    spreads += Spread(position, next)
                    position += 2
                } else {
                    spreads += Spread(position)
                    position++
                }
            }
        }
        return spreads
    }
}

/**
 * Two pages of one chapter shown side by side as one item of the paged viewer, [first] the earlier.
 * Equal when it holds the same two pages, so an item built again for the same pair keeps its view.
 */
data class PageSpread(val first: ReaderPage, val second: ReaderPage) {

    val pages: List<ReaderPage> get() = listOf(first, second)

    operator fun contains(page: ReaderPage): Boolean = page === first || page === second

    /** Left to right as drawn: in right to left the earlier page sits on the right. */
    fun onScreen(rightToLeft: Boolean): List<ReaderPage> = if (rightToLeft) listOf(second, first) else pages
}

/**
 * How [page] pairs. [measured] is what a spread found when it opened the page's image, for a page
 * whose route was not known yet. A tall page cut into parts and each of its parts stand alone, as do
 * an animated page, which has a view of its own, and a page no decoder reads, which shows its error.
 */
fun spreadShape(page: ReaderPage, measured: SpreadShape? = null): SpreadShape = when {
    page is InsertPage || page.partCount > 1 -> SpreadShape.WIDE
    measured != null -> measured
    else -> when (val route = page.route) {
        null -> SpreadShape.UNKNOWN
        is PageRoute.Region -> if (route.width > route.height) SpreadShape.WIDE else SpreadShape.NARROW
        is PageRoute.Animated, is PageRoute.Deferred, PageRoute.Unsupported -> SpreadShape.WIDE
    }
}

/**
 * The paged viewer's items for one chapter's [pages] while two pages show: a page alone as itself, a
 * pair as a [PageSpread].
 */
fun spreadItems(
    pages: List<ReaderPage>,
    shift: Boolean,
    measured: (ReaderPage) -> SpreadShape? = { null },
): List<Any> {
    val shapes = pages.map { spreadShape(it, measured(it)) }
    return SpreadPlanner.plan(shapes, shift).map { spread ->
        val second = spread.second
        if (second == null) pages[spread.first] else PageSpread(pages[spread.first], pages[second])
    }
}

/** Whether [item] is [page] or a spread that shows it. */
fun holdsPage(item: Any?, page: ReaderPage): Boolean = item === page || item is PageSpread && page in item

/** The position of the item that shows [page]: the page itself, or the spread it is part of; -1 if none. */
fun List<Any>.positionShowing(page: ReaderPage): Int = indexOfFirst { holdsPage(it, page) }

/**
 * The page that stands for [item] when the items are built again: a spread's first page, a split
 * half's page (the halves are not in the chapter's list), or the page itself. Null for a transition.
 */
fun leadPage(item: Any?): ReaderPage? = when (item) {
    is PageSpread -> item.first
    is InsertPage -> if (item.partIndex == 0) item.parent else item
    is ReaderPage -> item
    else -> null
}

/**
 * Whether two pages show side by side: the Experimental switch is on, the mode is one of the two
 * horizontal paged modes, and [layout] asks for it always or, in landscape, while [landscape].
 */
fun twoPagesShown(enabled: Boolean, layout: TwoPageLayout, mode: ReadingMode, landscape: Boolean): Boolean {
    if (!enabled) return false
    if (mode != ReadingMode.LEFT_TO_RIGHT && mode != ReadingMode.RIGHT_TO_LEFT) return false
    return when (layout) {
        TwoPageLayout.NEVER -> false
        TwoPageLayout.IN_LANDSCAPE -> landscape
        TwoPageLayout.ALWAYS -> true
    }
}

/** The shifted entries with [entryId] added, or taken out when it is already there. */
fun Set<String>.withShiftToggled(entryId: Long): Set<String> {
    val id = entryId.toString()
    return if (id in this) this - id else this + id
}
