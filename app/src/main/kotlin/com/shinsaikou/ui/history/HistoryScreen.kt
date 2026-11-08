package com.shinsaikou.ui.history

import androidx.activity.compose.BackHandler
import android.text.format.DateFormat
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoAdultContent
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.LIST_ROW_LEADING_WIDTH
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SearchField
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.components.TopBarAction
import com.shinsaikou.ui.components.TopBarToggleAction
import com.shinsaikou.ui.components.selectionTopBarColors
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.relativeDateText
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.DecimalFormat

@Composable
fun HistoryScreen() {
    val viewModel: HistoryViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    // `rememberLazyListState` saves through its own Saver, so a rotation keeps the place in a long
    // history instead of dropping the owner back at the most recent entry.
    val listState = rememberLazyListState()
    // The field's open state and its text belong to the screen, not to the view model.
    // `StateViewModel` has no `SavedStateHandle`, so a process death would hand back a view model
    // with an empty query while the field was still showing one.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val uiModels = remember(state.items) { historyUiModels(state.items) }

    // A restored query has to reach the view model again, because the view model came back new.
    LaunchedEffect(viewModel) {
        if (query.isNotEmpty()) viewModel.search(query)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HistoryViewModel.Event.Snackbar ->
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
                is HistoryViewModel.Event.OpenReader ->
                    navigator.open(Route.Reader(event.mangaId, event.chapterId))
            }
        }
    }

    fun closeSearch() {
        searchOpen = false
        query = ""
        viewModel.search("")
    }

    // Back unwinds one layer at a time, selection first, then the search, and only then the tab.
    BackHandler(enabled = state.selectionMode || searchOpen) {
        if (state.selectionMode) viewModel.clearSelection() else closeSearch()
    }

    Scaffold(
        topBar = { scrollBehavior ->
            if (state.selectionMode) {
                HistorySelectionBar(
                    count = state.selection.size,
                    scrollBehavior = scrollBehavior,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onRemoveSelected = viewModel::askRemoveSelected,
                )
            } else {
                ScreenTopBar(
                    title = stringResource(R.string.history_title),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (searchOpen) {
                            TopBarAction(
                                label = stringResource(R.string.action_close),
                                icon = Icons.Outlined.Close,
                                onClick = ::closeSearch,
                            )
                        } else {
                            TopBarAction(
                                label = stringResource(R.string.action_search),
                                icon = Icons.Outlined.Search,
                                onClick = { searchOpen = true },
                            )
                        }
                        TopBarToggleAction(
                            label = stringResource(
                                if (state.hideNsfw) R.string.history_show_nsfw else R.string.history_hide_nsfw,
                            ),
                            icon = Icons.Outlined.NoAdultContent,
                            checked = state.hideNsfw,
                            onCheckedChange = viewModel::setHideNsfw,
                        )
                        TopBarAction(
                            label = stringResource(R.string.action_clear_all),
                            icon = Icons.Outlined.DeleteSweep,
                            onClick = viewModel::askClearAll,
                        )
                    },
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        // The field sits outside the list so it stays reachable when a query matches nothing.
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (searchOpen) {
                SearchField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.search(it)
                    },
                    label = stringResource(R.string.history_search_hint),
                )
            }
            when {
                state.loading -> LoadingScreen(Modifier.weight(1f))
                state.items.isEmpty() -> EmptyScreen(
                    stringRes = when {
                        // Said first, or a history of nothing but NSFW reads as never having read.
                        state.hiddenCount > 0 -> R.string.history_empty_nsfw_hidden
                        state.query.isBlank() -> R.string.history_empty
                        else -> R.string.empty_search_results
                    },
                    modifier = Modifier.weight(1f),
                )
                else -> LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(
                        items = uiModels,
                        key = { it.key },
                        contentType = {
                            when (it) {
                                is HistoryUiModel.Header -> "header"
                                is HistoryUiModel.Item -> "item"
                            }
                        },
                    ) { model ->
                        when (model) {
                            is HistoryUiModel.Header -> ListGroupHeader(
                                text = relativeDateText(model.date, state.relativeTime, state.dateFormat),
                            )
                            is HistoryUiModel.Item -> {
                                val item = model.history
                                HistoryRow(
                                    item = item,
                                    selected = item.id in state.selection,
                                    selectionMode = state.selectionMode,
                                    // Upstream's row: a tap resumes the chapter, the cover opens the entry.
                                    onOpen = {
                                        if (state.selectionMode) {
                                            viewModel.toggleSelection(item.id)
                                        } else {
                                            viewModel.resume(item)
                                        }
                                    },
                                    onLongPress = { viewModel.toggleSelection(item.id) },
                                    onCover = {
                                        navigator.open(Route.MangaDetail(item.mangaId))
                                    }.takeUnless { state.selectionMode },
                                    onDelete = { viewModel.askRemove(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        HistoryViewModel.Dialog.ClearAll -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.history_clear_all_title)) },
            text = { Text(stringResource(R.string.history_clear_all_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::clearAll) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        is HistoryViewModel.Dialog.Remove -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.history_remove_selected_title)) },
            text = { Text(stringResource(R.string.history_remove_message_format, dialog.item.displayTitle)) },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(dialog.item) }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        is HistoryViewModel.Dialog.RemoveSelected -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.history_remove_selected_title)) },
            text = { Text(stringResource(R.string.history_remove_selected_message_format, dialog.count)) },
            confirmButton = {
                TextButton(onClick = viewModel::removeSelected) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        null -> Unit
    }
}

/**
 * The bar the top bar becomes while rows are selected. It is [ScreenTopBar] rather than a second
 * `TopAppBar`, so the swap keeps the same height and scroll behaviour and the list under it does
 * not shift; the back arrow leaves the selection, which is what the back gesture does too.
 *
 * Removal is the only bulk action history has, so it is the only one with an icon.
 */
@Composable
private fun HistorySelectionBar(
    count: Int,
    scrollBehavior: TopAppBarScrollBehavior?,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onRemoveSelected: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ScreenTopBar(
        title = stringResource(R.string.selection_count_format, count),
        onBack = onClearSelection,
        scrollBehavior = scrollBehavior,
        colors = selectionTopBarColors(),
        actions = {
            IconButton(onClick = onRemoveSelected) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_remove))
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.action_menu_overflow_description),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_select_all)) },
                    onClick = {
                        menuOpen = false
                        onSelectAll()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_deselect_all)) },
                    onClick = {
                        menuOpen = false
                        onClearSelection()
                    },
                )
            }
        },
    )
}

/**
 * Upstream's history row: a tap resumes the chapter ([onOpen]), the cover opens the entry
 * ([onCover], null while rows are selected), and the one button removes the entry's history. The
 * row fills [ListRowLayout] itself, which keeps the cover slot, the paddings and the divider of
 * every other list row.
 *
 * The delete button disappears while [selectionMode] is on. Leaving it would put a delete
 * button inside a row whose only job then is to be selected, exactly where a thumb reaching for
 * the row lands, and that one is not undoable.
 */
@Composable
private fun HistoryRow(
    item: HistoryWithRelations,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onCover: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    ListRowLayout(
        onClick = onOpen,
        onLongClick = onLongPress,
        selected = selected,
        leading = {
            MangaCover(
                item.coverData,
                modifier = Modifier
                    .width(LIST_ROW_LEADING_WIDTH)
                    // The row resumes and the cover opens the entry, so a screen reader names the cover's
                    // action. A long press on the cover selects, as one on the rest of the row does.
                    .then(
                        if (onCover != null) {
                            Modifier.combinedClickable(
                                onClickLabel = stringResource(R.string.history_open_entry),
                                onLongClick = onLongPress,
                                onClick = onCover,
                            )
                        } else {
                            Modifier
                        },
                    ),
                contentDescription = item.displayTitle,
            )
        },
        trailing = {
            if (!selectionMode) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
    ) {
        ListRowTitle(item.displayTitle)
        ListRowDetail(historyDetail(item), maxLines = 1)
    }
}

/**
 * "Chapter 12 - 14:05", the time the chapter was last read in the device's zone and its 12 or 24
 * hour setting, under the day header above. An unrecognised chapter number shows the time alone,
 * as upstream does.
 */
@Composable
private fun historyDetail(item: HistoryWithRelations): String {
    val context = LocalContext.current
    val time = remember(item.readAt) {
        item.readAt?.let { DateFormat.getTimeFormat(context).format(it) }
    }
    val number = remember(item.chapterNumber) { DecimalFormat("#.###").format(item.chapterNumber) }
    return when {
        item.chapterNumber < 0 -> time.orEmpty()
        time == null -> stringResource(R.string.history_chapter_format, number)
        else -> stringResource(R.string.history_chapter_time_format, number, time)
    }
}
