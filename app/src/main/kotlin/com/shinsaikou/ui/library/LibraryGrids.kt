package com.shinsaikou.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.shinsaikou.ui.components.GRID_SPACING
import com.shinsaikou.ui.components.MangaComfortableGridItem
import com.shinsaikou.ui.components.MangaCompactGridItem
import com.shinsaikou.ui.components.MangaRowItem
import com.shinsaikou.ui.components.mangaGridCells
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

/**
 * The three ways a category's rows are drawn. Each is handed the rows already sorted and badged, so
 * a grid decides layout and nothing else, and each takes its own scroll state because the pager
 * keeps one per category.
 *
 * `columns` is 0 for "fit as many as the width allows", which is what the slider's lower end
 * stores; any other number is that many columns exactly. The list mode has no columns to set.
 *
 * A non-null `onReorder` draws a drag handle on every cell (phase 4 unit MS), in the idiom of
 * upstream's Categories screen (`CategoryScreen.kt`, `CategoryListItem.kt`): `sh.calvin.reorderable`,
 * the item wrapped in `ReorderableItem` and only the handle carrying `draggableHandle()`. Where
 * upstream writes on every step of a drag, a page writes once, when the drop settles, since one step
 * here rewrites positions across the library. It hands over the order it drew, and the view model
 * answers whether it wrote it. Null draws the cells exactly as before.
 *
 * A `header` is drawn above the first row, across every column: the library's "Search all sources"
 * while a search is up. A page has no handles then (a search turns reordering off), so the drag's
 * indices never count it.
 */

@Composable
internal fun LibraryCompactGrid(
    items: List<LibraryViewModel.LibraryItemUi>,
    columns: Int,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    state: LazyGridState,
    coverOnly: Boolean,
    onReorder: ((mangaId: Long, order: List<Long>) -> Boolean)?,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    LibraryGrid(
        items = items,
        columns = columns,
        contentPadding = contentPadding,
        state = state,
        onReorder = onReorder,
        header = header,
    ) { item, dragHandle ->
        MangaCompactGridItem(
            cover = item.item.manga,
            title = item.item.manga.displayTitle.takeUnless { coverOnly },
            coverDescription = item.item.manga.displayTitle,
            isSelected = item.id in selection,
            onClick = { onClick(item.id) },
            onLongClick = { onLongClick(item.id) },
            onContinueReading = continueReadingAction(item, onContinueReading),
            badges = { LibraryBadges(item) },
            dragHandle = dragHandle,
        )
    }
}

@Composable
internal fun LibraryComfortableGrid(
    items: List<LibraryViewModel.LibraryItemUi>,
    columns: Int,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    state: LazyGridState,
    onReorder: ((mangaId: Long, order: List<Long>) -> Boolean)?,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    LibraryGrid(
        items = items,
        columns = columns,
        contentPadding = contentPadding,
        state = state,
        onReorder = onReorder,
        header = header,
    ) { item, dragHandle ->
        MangaComfortableGridItem(
            cover = item.item.manga,
            title = item.item.manga.displayTitle,
            isSelected = item.id in selection,
            onClick = { onClick(item.id) },
            onLongClick = { onLongClick(item.id) },
            onContinueReading = continueReadingAction(item, onContinueReading),
            badges = { LibraryBadges(item) },
            dragHandle = dragHandle,
        )
    }
}

@Composable
internal fun LibraryList(
    items: List<LibraryViewModel.LibraryItemUi>,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    state: LazyListState,
    onReorder: ((mangaId: Long, order: List<Long>) -> Boolean)?,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val drag = remember { DragRows() }
    val reorderState = rememberReorderableLazyListState(state, contentPadding) { from, to ->
        drag.move(items, from.index, to.index)
    }
    LaunchedEffect(items) { if (!reorderState.isAnyItemDragging) drag.rows = null }
    ScrollbarLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding,
    ) {
        if (header != null) item(key = HEADER_KEY, contentType = HEADER_KEY) { header() }
        items(drag.rows ?: items, key = LibraryViewModel.LibraryItemUi::id) { item ->
            val row = @Composable { dragHandle: Modifier? ->
                MangaRowItem(
                    cover = item.item.manga,
                    title = item.item.manga.displayTitle,
                    isSelected = item.id in selection,
                    onClick = { onClick(item.id) },
                    onLongClick = { onLongClick(item.id) },
                    onContinueReading = continueReadingAction(item, onContinueReading),
                    badges = { LibraryBadges(item) },
                    dragHandle = dragHandle,
                )
            }
            if (onReorder == null) {
                row(null)
            } else {
                ReorderableItem(reorderState, key = item.id) {
                    row(Modifier.draggableHandle(onDragStopped = { drag.drop(item.id, onReorder) }))
                }
            }
        }
    }
}

/** The header's key, which no entry id can be: those are Longs. */
private const val HEADER_KEY = "library_header"

/** Null is how an item says it has nothing left to read, which is what removes the button. */
private fun continueReadingAction(
    item: LibraryViewModel.LibraryItemUi,
    onContinueReading: (Long) -> Unit,
): (() -> Unit)? = if (item.showContinueReading) ({ onContinueReading(item.id) }) else null

@Composable
private fun LibraryGrid(
    items: List<LibraryViewModel.LibraryItemUi>,
    columns: Int,
    contentPadding: PaddingValues,
    state: LazyGridState,
    onReorder: ((mangaId: Long, order: List<Long>) -> Boolean)?,
    header: (@Composable () -> Unit)?,
    cell: @Composable (item: LibraryViewModel.LibraryItemUi, dragHandle: Modifier?) -> Unit,
) {
    val drag = remember { DragRows() }
    val reorderState = rememberReorderableLazyGridState(state, contentPadding) { from, to ->
        drag.move(items, from.index, to.index)
    }
    LaunchedEffect(items) { if (!reorderState.isAnyItemDragging) drag.rows = null }
    LazyVerticalGrid(
        columns = mangaGridCells(columns),
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        if (header != null) {
            item(key = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }, contentType = HEADER_KEY) { header() }
        }
        items(drag.rows ?: items, key = LibraryViewModel.LibraryItemUi::id) { item ->
            if (onReorder == null) {
                cell(item, null)
            } else {
                ReorderableItem(reorderState, key = item.id) {
                    cell(item, Modifier.draggableHandle(onDragStopped = { drag.drop(item.id, onReorder) }))
                }
            }
        }
    }
}

/**
 * The rows a page draws while a drag is under way or has just landed: the drag's own copy, moved
 * on every step as upstream's Categories list is, until the view model's next rows arrive with the
 * order the drop wrote. Null the rest of the time, so outside a drag a page draws the view model's
 * rows as they come, a frame sooner than a copy synced after them would.
 */
@Stable
private class DragRows {
    var rows by mutableStateOf<List<LibraryViewModel.LibraryItemUi>?>(null)

    fun move(items: List<LibraryViewModel.LibraryItemUi>, from: Int, to: Int) {
        rows = (rows ?: items).toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Hands the view model the order the drag left on screen. When it writes nothing (a drop where
     * the entry started, or a tab that changed under the drag) the copy is dropped at once rather
     * than waiting for rows that will not come, so the page shows the tab as it now stands.
     */
    fun drop(mangaId: Long, onReorder: (Long, List<Long>) -> Boolean) {
        val order = rows?.map { it.id } ?: return
        if (!onReorder(mangaId, order)) rows = null
    }
}
