package com.shinsaikou.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.search.AndNode
import tachiyomi.domain.library.model.search.GeneralQueryNode
import tachiyomi.domain.library.model.search.QueryNode
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * The category tabs and the pages under them.
 *
 * The pager is the source of truth for which category is showing: a tab tap scrolls it and the
 * settled page is reported back to the view model, so a swipe and a tap end in the same place and
 * only one of them has to write the preference.
 */

/**
 * One tab per category, scrollable because a restored library can carry seventeen of them, with the
 * entry count on each when the owner asked for it. Upstream's `LibraryTabs`.
 *
 * [categories] is in the tree's order, so a child's tab follows its parent's; [parents] is each
 * shown child's parent by the child's id, named in a line above the child's own name (phase 5
 * unit CT). While any child is shown every tab takes the two-line height, so the names line up;
 * with none, the row is upstream's one-line row.
 */
@Composable
internal fun LibraryTabs(
    categories: List<Category>,
    parents: Map<Long, Category>,
    selectedIndex: Int,
    itemCount: ((Category) -> Int)?,
    onTab: (Int) -> Unit,
) {
    val twoLine = parents.isNotEmpty()
    PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 0.dp) {
        categories.forEachIndexed { index, category ->
            if (!twoLine) {
                Tab(
                    selected = selectedIndex == index,
                    onClick = { onTab(index) },
                    text = { TabText(text = category.visualName, badgeCount = itemCount?.invoke(category)) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
                return@forEachIndexed
            }
            // Material's own content slot, which centres its column both ways, at the one-line tab's
            // 48 dp plus the parent's line, with the one-line tab's 16 dp side padding.
            Tab(
                selected = selectedIndex == index,
                onClick = { onTab(index) },
                modifier = Modifier.height(TWO_LINE_TAB_HEIGHT),
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = TAB_SIDE_PADDING),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    parents[category.id]?.let { parent ->
                        Text(
                            text = parent.visualName,
                            modifier = Modifier.secondaryItemAlpha(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TabText(text = category.visualName, badgeCount = itemCount?.invoke(category))
                }
            }
        }
    }
}

/** A sub-category row's tab: Material's 48 dp text tab plus one 16 dp `labelSmall` line. */
private val TWO_LINE_TAB_HEIGHT = 64.dp

/** Material's side padding around a text tab's label. */
private val TAB_SIDE_PADDING = 16.dp

@Composable
internal fun LibraryPager(
    state: LibraryViewModel.State,
    pagerState: PagerState,
    contentPadding: PaddingValues,
    onClick: (Long) -> Unit,
    onLongClick: (categoryId: Long, mangaId: Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    onReorder: (categoryId: Long, mangaId: Long, order: List<Long>) -> Boolean,
    onGlobalSearch: (String) -> Unit,
) {
    // Upstream's "Search for X globally" over every page while a search is up, so an entry the
    // library does not have is one tap from the sources (upstream's `GlobalSearchItem`).
    val globalSearch: (@Composable () -> Unit)? = globalSearchQuery(state.query)?.let { query ->
        { GlobalSearchButton(query = query, onClick = { onGlobalSearch(query) }) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
    ) { page ->
        val category = state.categories.getOrNull(page) ?: return@HorizontalPager
        val items = state.items(category.id)
        if (items.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize()) {
                globalSearch?.invoke()
                EmptyScreen(stringRes = state.emptyPageMessage, modifier = Modifier.weight(1f))
            }
            return@HorizontalPager
        }
        // A handle only while the drag can write the whole tab in the order it shows (phase 4 unit MS).
        val reorder: ((Long, List<Long>) -> Boolean)? = if (state.canReorder) {
            { mangaId, order -> onReorder(category.id, mangaId, order) }
        } else {
            null
        }
        // The page names its category, so a range is taken over the rows this page shows.
        val onLongClickInPage: (Long) -> Unit = { mangaId -> onLongClick(category.id, mangaId) }
        // Keyed by category id, not by page index: the scroll position belongs to the category, and
        // a category that moves because another one was emptied by a filter must take it along.
        key(category.id) {
            when (state.displayMode) {
                LibraryDisplayMode.List -> LibraryList(
                    header = globalSearch,
                    items = items,
                    selection = state.selection,
                    contentPadding = contentPadding,
                    state = rememberLazyListState(),
                    onReorder = reorder,
                    onClick = onClick,
                    onLongClick = onLongClickInPage,
                    onContinueReading = onContinueReading,
                )
                LibraryDisplayMode.ComfortableGrid -> LibraryComfortableGrid(
                    header = globalSearch,
                    items = items,
                    columns = state.columns,
                    selection = state.selection,
                    contentPadding = contentPadding,
                    state = rememberLazyGridState(),
                    onReorder = reorder,
                    onClick = onClick,
                    onLongClick = onLongClickInPage,
                    onContinueReading = onContinueReading,
                )
                LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> LibraryCompactGrid(
                    header = globalSearch,
                    items = items,
                    columns = state.columns,
                    selection = state.selection,
                    contentPadding = contentPadding,
                    state = rememberLazyGridState(),
                    coverOnly = state.displayMode == LibraryDisplayMode.CoverOnlyGrid,
                    onReorder = reorder,
                    onClick = onClick,
                    onLongClick = onLongClickInPage,
                    onContinueReading = onContinueReading,
                )
            }
        }
    }
}

/**
 * Why a page is empty, which is three different things and only one of them is a search.
 *
 * The filtered case is the one that matters for the app-wide downloaded-only switch: with nothing
 * downloaded it empties every category, and "no entries match the active filters" is the honest
 * answer to that rather than a library that reads as lost.
 */
@get:StringRes
private val LibraryViewModel.State.emptyPageMessage: Int
    get() = when {
        query.isNotEmpty() -> R.string.empty_search_results
        hasActiveFilters -> R.string.library_empty_filtered
        else -> R.string.library_category_empty
    }

/**
 * The library search to offer as a search of every source: its words while it is only words, null
 * while it is blank or uses the library's own syntax (a field such as `genre:"X"`, a comparison, a
 * negation, an OR), which no source would read the same way. A genre tapped on an entry's screen
 * searches the library as `genre:"X"`, and offering that to the sources would search for the syntax.
 */
internal fun globalSearchQuery(query: String): String? {
    if (query.isBlank()) return null
    val words = mutableListOf<String>()
    fun plain(node: QueryNode): Boolean = when (node) {
        is GeneralQueryNode -> !node.negated && words.add(node.value)
        is AndNode -> node.children.all(::plain)
        else -> false
    }
    if (!plain(QueryNode.from(query))) return null
    return words.joinToString(" ").takeIf { it.isNotBlank() }
}

/** Upstream's `GlobalSearchItem`: a full width text button above a page's entries. */
@Composable
internal fun GlobalSearchButton(query: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.library_search_globally_format, query))
    }
}

/** The system category has no name of its own; it is whatever the owner is shown for "no category". */
internal val Category.visualName: String
    @Composable get() = if (isSystemCategory) stringResource(R.string.library_default_category) else name
