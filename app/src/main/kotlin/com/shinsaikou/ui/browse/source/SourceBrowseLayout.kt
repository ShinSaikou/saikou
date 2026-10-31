package com.shinsaikou.ui.browse.source

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.shinsaikou.i18n.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.Badge

/**
 * How a source's listing is drawn, apart from the screen so the choice can be tested without
 * composing anything.
 *
 * Four stored modes give three layouts. Upstream's menu offers list, compact grid and comfortable grid;
 * the fourth value, the cover-only grid, has no menu entry but a restored backup can hold it, and it
 * is drawn as the compact grid without titles, as the library draws it.
 */
internal sealed interface BrowseLayout {
    data object List : BrowseLayout
    data class CompactGrid(val showTitles: Boolean) : BrowseLayout
    data object ComfortableGrid : BrowseLayout
}

internal fun browseLayoutOf(mode: LibraryDisplayMode): BrowseLayout = when (mode) {
    LibraryDisplayMode.List -> BrowseLayout.List
    LibraryDisplayMode.CompactGrid -> BrowseLayout.CompactGrid(showTitles = true)
    LibraryDisplayMode.CoverOnlyGrid -> BrowseLayout.CompactGrid(showTitles = false)
    LibraryDisplayMode.ComfortableGrid -> BrowseLayout.ComfortableGrid
}

/**
 * Whether the listing has scrolled close enough to its end to ask for the next page. The threshold
 * is in rows so it does not depend on the row height, and a grid's row is [LazyGridLayoutInfo.maxSpan]
 * cells, so a grid asks as early as the list does however many columns it has.
 */
internal fun LazyListLayoutInfo.isNearEnd(): Boolean =
    isNearEnd(visibleItemsInfo.lastOrNull()?.index, totalItemsCount, cellsPerRow = 1)

internal fun LazyGridLayoutInfo.isNearEnd(): Boolean =
    isNearEnd(visibleItemsInfo.lastOrNull()?.index, totalItemsCount, cellsPerRow = maxSpan)

/** The two scroll states' layout info are sealed, so the rule itself takes their three numbers. */
internal fun isNearEnd(lastVisibleIndex: Int?, totalItemsCount: Int, cellsPerRow: Int): Boolean =
    lastVisibleIndex != null && lastVisibleIndex >= totalItemsCount - PREFETCH_ROWS * cellsPerRow.coerceAtLeast(1)

/** Where the listing stands for [requestPagesNearEnd]: near its end or not, holding [page] pages. */
internal data class ListingPosition(val nearEnd: Boolean, val page: Int)

/**
 * The position as laid out, or null while the layout is a frame behind the listing: it does not yet
 * hold the [listedItems] items the screen put in it (the header, the entries and the spinner).
 */
internal fun LazyListLayoutInfo.listingPosition(listedItems: Int, page: Int): ListingPosition? =
    listingPosition(totalItemsCount, listedItems, page) { isNearEnd() }

internal fun LazyGridLayoutInfo.listingPosition(listedItems: Int, page: Int): ListingPosition? =
    listingPosition(totalItemsCount, listedItems, page) { isNearEnd() }

internal fun listingPosition(laidOutItems: Int, listedItems: Int, page: Int, nearEnd: () -> Boolean): ListingPosition? =
    if (laidOutItems == listedItems) ListingPosition(nearEnd(), page) else null

/**
 * The next page is requested from the scroll position rather than from a button at the bottom.
 * [loadNextPage] runs once each time the listing comes near its end, and once more each time a page
 * lands while it is still there. The page is in the key because a page too short to fill the screen
 * leaves the listing near its end: a key of the flag alone never changes again, and the listing
 * stopped after one or two pages, most readily in a grid, whose threshold grows with its columns.
 *
 * A null position is skipped, so a layout a frame behind cannot ask with the last page's position,
 * and a page that failed (the spinner gone, the page number unchanged) is not asked for again until
 * the listing is scrolled away from its end and back. The view model ignores a request while a page
 * is on its way or when the source has no more.
 */
internal suspend fun requestPagesNearEnd(position: () -> ListingPosition?, loadNextPage: () -> Unit) {
    snapshotFlow(position).filterNotNull().distinctUntilChanged().collect { if (it.nearEnd) loadNextPage() }
}

/**
 * Upstream's in-library badge on a grid cell or a list row, with the words a screen reader needs,
 * since the glyph alone says nothing.
 */
@Composable
internal fun RowScope.InLibraryBadge() {
    val description = LocalResources.current.getString(R.string.source_in_library)
    Badge(
        imageVector = Icons.Outlined.CollectionsBookmark,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    )
}

/** Upstream's `BrowseFavoriteCoverAlpha`: an entry already in the library is drawn faded in browse. */
internal const val IN_LIBRARY_COVER_ALPHA = 0.34f

/** How a listing's entry is marked for being in the library: upstream's badge, and its cover faded. */
internal data class InLibraryMark(val badge: Boolean, val coverAlpha: Float)

/**
 * The one mark every layout draws, as upstream's list and both its grids do (`BrowseSourceList`,
 * `BrowseSourceCompactGrid`, `BrowseSourceComfortableGrid`); until phase 4 unit MP1 the list said
 * "In library" in a detail line under the title instead.
 */
internal fun inLibraryMark(inLibrary: Boolean): InLibraryMark =
    if (inLibrary) InLibraryMark(badge = true, coverAlpha = IN_LIBRARY_COVER_ALPHA) else NOT_IN_LIBRARY

private val NOT_IN_LIBRARY = InLibraryMark(badge = false, coverAlpha = 1f)

private const val PREFETCH_ROWS = 6
