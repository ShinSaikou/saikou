package com.shinsaikou.ui.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.ui.chapter.ChapterDownload
import com.shinsaikou.ui.chapter.ChapterDownloadAction
import com.shinsaikou.ui.chapter.ChapterDownloadControl
import com.shinsaikou.ui.chapter.ChapterMarkedText
import com.shinsaikou.ui.chapter.chapterProgress
import com.shinsaikou.ui.chapter.chapterTextAlpha
import com.shinsaikou.ui.components.LIST_ROW_LEADING_WIDTH
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowBody
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.components.TopBarAction
import com.shinsaikou.ui.components.UpdateErrorAction
import com.shinsaikou.ui.components.UpdateErrorList
import com.shinsaikou.ui.components.UpdateErrorSiteStoppedNotice
import com.shinsaikou.ui.components.selectionTopBarColors
import com.shinsaikou.ui.util.UpdateErrorGroup
import com.shinsaikou.ui.util.relativeTimeSpanString
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.relativeDateText
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * [showErrorsOnArrival] is how the library update's error notification lands here: `Route.Updates`
 * is a tab compared by identity, so the flag travels beside the route rather than on it, and the
 * screen reports back through [onErrorsShown] so a rotation does not reopen the sheet.
 */
@Composable
fun UpdatesScreen(showErrorsOnArrival: Boolean = false, onErrorsShown: () -> Unit = {}) {
    val viewModel: UpdatesViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    // `rememberLazyListState` saves through its own Saver, so the row the owner scrolled to
    // survives a rotation instead of snapping back to the newest chapter.
    val listState = rememberLazyListState()
    val uiModels = remember(state.items, state.foldExpanded) { state.uiModels() }
    // Upstream's update row answers a long press with this feedback before it selects.
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UpdatesViewModel.Event.Snackbar -> {
                    val message = if (event.formatArgs.isEmpty()) {
                        resources.getString(event.messageRes)
                    } else {
                        resources.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    }
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    // Back leaves the selection before it leaves the tab. A long press is easy to trigger by
    // accident and the gesture that already means "undo that" has to be the way out.
    BackHandler(enabled = state.selectionMode, onBack = viewModel::clearSelection)

    LaunchedEffect(showErrorsOnArrival) {
        if (showErrorsOnArrival) {
            viewModel.showErrors()
            onErrorsShown()
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            if (state.selectionMode) {
                UpdatesSelectionBar(
                    count = state.selection.size,
                    scrollBehavior = scrollBehavior,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onInvertSelection = viewModel::invertSelection,
                    onMarkRead = { viewModel.markRead(true) },
                    onMarkUnread = { viewModel.markRead(false) },
                    onBookmark = { viewModel.toggleBookmark(true) },
                    onRemoveBookmark = { viewModel.toggleBookmark(false) },
                )
            } else {
                ScreenTopBar(
                    title = stringResource(R.string.updates_title),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        UpdateErrorAction(count = state.updateErrorCount, onClick = viewModel::showErrors)
                        // The bar has no room for a label, so the tint carries whether a filter is
                        // on, lit for the four tri-states as upstream's icon is.
                        TopBarAction(
                            label = stringResource(R.string.action_filter),
                            icon = Icons.Outlined.FilterList,
                            onClick = viewModel::showFilterDialog,
                            highlighted = state.hasActiveFilters,
                        )
                        TopBarAction(
                            label = stringResource(R.string.upcoming_title),
                            icon = Icons.Outlined.CalendarMonth,
                            onClick = { navigator.open(Route.Upcoming) },
                        )
                        // Upstream's bar ends with the library update, the same run the pull starts.
                        TopBarAction(
                            label = stringResource(R.string.action_update_library),
                            icon = Icons.Outlined.Refresh,
                            onClick = viewModel::refresh,
                        )
                    },
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        // The empty state is inside the refresh so a library with no updates yet can still be
        // checked with a pull.
        PullRefresh(
            refreshing = state.updating,
            // A pull during a selection would start a library run the owner did not ask for while
            // their finger was on a row, so the gesture steps aside until the selection is over.
            enabled = !state.loading && !state.selectionMode,
            onRefresh = viewModel::refresh,
            indicatorPadding = padding,
        ) {
            when {
                state.loading -> LoadingScreen(Modifier.padding(padding))
                state.items.isEmpty() -> EmptyScreen(
                    stringRes = R.string.updates_empty,
                    modifier = Modifier.padding(padding),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    // Upstream's line above the rows, so a schedule that stopped running shows here,
                    // and under it what the last automatic run skipped and why.
                    item(key = "updates-lastUpdated", contentType = "lastUpdated") {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = MaterialTheme.padding.medium,
                                vertical = MaterialTheme.padding.small,
                            ),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.updates_last_update_info,
                                    relativeTimeSpanString(state.lastUpdated),
                                ),
                                fontStyle = FontStyle.Italic,
                            )
                            skippedText(state.lastAutoSkipped)?.let { skipped ->
                                Text(
                                    text = skipped,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(
                        items = uiModels,
                        key = { it.key },
                        contentType = {
                            when (it) {
                                is UpdatesUiModel.Header -> "header"
                                is UpdatesUiModel.Fold -> "fold"
                                is UpdatesUiModel.Item -> "item"
                            }
                        },
                    ) { model ->
                        when (model) {
                            is UpdatesUiModel.Header -> ListGroupHeader(
                                text = relativeDateText(model.date, state.relativeTime, state.dateFormat),
                            )
                            is UpdatesUiModel.Fold -> UpdateFoldRow(
                                fold = model,
                                selected = state.selection.containsAll(model.chapterIds),
                                onToggle = { viewModel.toggleFold(model) },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.longPressFold(model)
                                },
                                onCover = {
                                    navigator.open(Route.MangaDetail(model.updates.first().titleId))
                                }.takeUnless { state.selectionMode },
                            )
                            is UpdatesUiModel.Item -> {
                                val update = model.update
                                UpdateRow(
                                    update = update,
                                    inFold = model.inFold,
                                    selected = update.chapterId in state.selection,
                                    download = state.downloadOf(update).takeIf { state.showsDownloadControl(update) },
                                    // Upstream's row: a tap reads the chapter, the cover opens the entry.
                                    onOpen = {
                                        if (state.selectionMode) {
                                            viewModel.toggleSelection(update.chapterId)
                                        } else {
                                            navigator.open(Route.Reader(update.mangaId, update.chapterId))
                                        }
                                    },
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.longPressChapter(update.chapterId)
                                    },
                                    onCover = {
                                        navigator.open(Route.MangaDetail(update.titleId))
                                    }.takeUnless { state.selectionMode },
                                    onDownloadAction = { action -> viewModel.runChapterDownloadAction(update, action) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        is UpdatesViewModel.Dialog.ErrorList -> UpdateErrorsSheet(
            groups = dialog.groups,
            stoppedEntries = dialog.stoppedEntries,
            onDismissRequest = viewModel::dismissDialog,
            onRow = { mangaId ->
                viewModel.dismissDialog()
                navigator.open(Route.MangaDetail(mangaId))
            },
            onOpenLibrarySettings = {
                viewModel.dismissDialog()
                navigator.open(Route.Settings(SettingsSection.LIBRARY))
            },
            onRetry = viewModel::retryErrors,
            onMigrate = { ids ->
                viewModel.dismissDialog()
                navigator.open(Route.MigrationConfig(ids))
            },
        )
        UpdatesViewModel.Dialog.Filter -> UpdatesFilterDialog(
            filters = state.filters,
            categories = state.categories,
            onDismissRequest = viewModel::dismissDialog,
            onFilter = viewModel::setFilter,
            onToggleExcludedScanlators = viewModel::toggleExcludedScanlators,
            onCycleCategory = viewModel::cycleCategory,
        )
        null -> Unit
    }
}

/** The skip rules in the order the line names them, each with its count's text. */
private val skipReasonTexts = listOf(
    LibraryUpdateRestrictions.MANGA_NON_COMPLETED to R.string.updates_skipped_completed,
    LibraryUpdateRestrictions.MANGA_HAS_UNREAD to R.string.updates_skipped_unread,
    LibraryUpdateRestrictions.MANGA_NON_READ to R.string.updates_skipped_not_started,
    LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD to R.string.updates_skipped_not_due,
    FlatLibraryUpdate.FETCH_ONCE to R.string.updates_skipped_fetch_once,
)

/**
 * "Last automatic update skipped 12: 5 completed, 7 with unread chapters", or null when it skipped
 * none. A reason this build does not name is counted in the total only.
 */
@Composable
private fun skippedText(skipped: Map<String, Int>): String? {
    val total = skipped.values.sum()
    if (total <= 0) return null
    val reasons = skipReasonTexts.mapNotNull { (reason, res) ->
        skipped[reason]?.let { stringResource(res, it) }
    }
    return stringResource(R.string.updates_last_auto_skipped_format, total, reasons.joinToString(", "))
}

/**
 * The last run's failures, one row per entry under the source that failed it, in a sheet from the
 * bottom. This tab is the one place they show. The message is the one the update run already
 * resolved and stored, not a fresh mapping of a throwable this screen never saw. When the run
 * stopped asking a site, [stoppedEntries] of the rows are that site's, and the notice above the
 * list says why, with the way to Settings > Library. Retry updates these entries again, and each
 * source's Migrate opens a migration of that source's rows. A drag down or a tap outside closes it.
 */
@Composable
private fun UpdateErrorsSheet(
    groups: List<UpdateErrorGroup>,
    stoppedEntries: Int,
    onDismissRequest: () -> Unit,
    onRow: (Long) -> Unit,
    onOpenLibrarySettings: () -> Unit,
    onRetry: () -> Unit,
    onMigrate: (List<Long>) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest, enableImplicitDismiss = true) {
        Column(modifier = Modifier.padding(top = MaterialTheme.padding.medium)) {
            Text(
                text = stringResource(R.string.update_errors_title),
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                style = MaterialTheme.typography.titleMedium,
            )
            UpdateErrorSiteStoppedNotice(
                count = stoppedEntries,
                onOpenLibrarySettings = onOpenLibrarySettings,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
            )
            UpdateErrorList(
                groups = groups,
                onRow = onRow,
                onMigrate = { group -> onMigrate(group.errors.map { it.mangaId }) },
                // Measured after Retry, so a short window shrinks the list rather than hiding the button.
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End).padding(MaterialTheme.padding.small),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * The bar the top bar becomes while rows are selected. It is [ScreenTopBar] rather than a second
 * `TopAppBar`, so the height and the scroll behaviour stay the same as the bar it replaces and the
 * swap does not shift the list under it; the back arrow leaves the selection, which is what the
 * back gesture does too.
 *
 * Only the two read actions get an icon. Everything else lives in the overflow because a phone bar
 * fits three actions and a title, and the count is the part that has to stay readable.
 */
@Composable
private fun UpdatesSelectionBar(
    count: Int,
    scrollBehavior: TopAppBarScrollBehavior?,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onBookmark: () -> Unit,
    onRemoveBookmark: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ScreenTopBar(
        title = stringResource(R.string.selection_count_format, count),
        onBack = onClearSelection,
        scrollBehavior = scrollBehavior,
        colors = selectionTopBarColors(),
        actions = {
            IconButton(onClick = onMarkRead) {
                Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.action_mark_as_read))
            }
            IconButton(onClick = onMarkUnread) {
                Icon(Icons.Outlined.RemoveDone, contentDescription = stringResource(R.string.action_mark_as_unread))
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.action_menu_overflow_description),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_bookmark)) },
                    leadingIcon = { Icon(Icons.Outlined.Bookmark, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onBookmark()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_bookmark)) },
                    leadingIcon = { Icon(Icons.Outlined.BookmarkRemove, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRemoveBookmark()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_select_all)) },
                    onClick = {
                        menuOpen = false
                        onSelectAll()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_invert_selection)) },
                    onClick = {
                        menuOpen = false
                        onInvertSelection()
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
 * One updated chapter, upstream's `UpdatesUiItem` on `ListRowBody` (unit ID3), in its colours,
 * alphas and spacing, with no divider: the entry's title over
 * the chapter's line, which carries the unread dot and the bookmark before the chapter name, its
 * scanlator and "Page: N" while partly read; a read row is faded; the download control sits at the
 * end, [download] null where the row draws none (a Local chapter, or rows selected). The row has no
 * buttons of its own (plan 2.6): a tap reads the chapter, as upstream's does, the cover opens the
 * entry ([onCover], null while rows are selected), and a long press selects.
 *
 * A row [inFold] sits under its fold's row, which already shows the cover and the title, so it
 * keeps the cover's slot empty and shows the chapter's line alone.
 */
@Composable
private fun UpdateRow(
    update: UpdatesWithRelations,
    inFold: Boolean,
    selected: Boolean,
    download: ChapterDownload?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onCover: (() -> Unit)?,
    onDownloadAction: (ChapterDownloadAction) -> Unit,
) {
    ListRowBody(
        onClick = onOpen,
        onLongClick = onLongPress,
        selected = selected,
        contentPadding = UPDATE_ROW_PADDING,
        leadingGap = UPDATE_ROW_GAP,
        leading = {
            if (!inFold) {
                MangaCover(
                    update.coverData,
                    modifier = Modifier
                        .width(LIST_ROW_LEADING_WIDTH)
                        .then(if (onCover != null) Modifier.clickable(onClick = onCover) else Modifier),
                    contentDescription = update.displayTitle,
                )
            }
        },
        trailing = {
            if (download != null) {
                ChapterDownloadControl(
                    download,
                    onDownloadAction,
                    modifier = Modifier.padding(start = UPDATE_ROW_END_GAP),
                )
            }
        },
    ) {
        if (!inFold) {
            Text(
                update.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = chapterTextAlpha(update.read)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ChapterMarkedText(
            text = chapterDetail(update),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current,
            read = update.read,
            bookmark = update.bookmark,
        )
    }
}

/**
 * One entry's consecutive chapters within a day, as one row: the cover, the title and the count,
 * with a chevron saying whether the chapters show under it, and saying so to a screen reader.
 *
 * A tap opens or closes the fold, in selection mode too, so a single chapter of a collapsed fold
 * can still be reached and picked. A long press selects every chapter in the fold, the hidden ones
 * included, with the chapters between it and the selection as a chapter row's long press does
 * ([UpdatesViewModel.longPressFold]), or leaves them all when every one already is; the row reads
 * as selected only then. The cover opens the entry, as an update row's does.
 */
@Composable
private fun UpdateFoldRow(
    fold: UpdatesUiModel.Fold,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onCover: (() -> Unit)?,
) {
    val first = fold.updates.first()
    ListRowBody(
        onClick = onToggle,
        onLongClick = onLongPress,
        selected = selected,
        contentPadding = UPDATE_ROW_PADDING,
        leadingGap = UPDATE_ROW_GAP,
        leading = {
            MangaCover(
                first.coverData,
                modifier = Modifier
                    .width(LIST_ROW_LEADING_WIDTH)
                    .then(if (onCover != null) Modifier.clickable(onClick = onCover) else Modifier),
                contentDescription = first.displayTitle,
            )
        },
        trailing = {
            // Upstream's chevrons carry no description; this one says what a tap on the row does, so a
            // screen reader tells an open fold from a closed one.
            Icon(
                if (fold.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(
                    if (fold.expanded) R.string.updates_fold_collapse else R.string.updates_fold_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = UPDATE_ROW_END_GAP),
            )
        },
    ) {
        ListRowTitle(first.displayTitle)
        ListRowDetail(pluralStringResource(R.plurals.manga_num_chapters, fold.updates.size, fold.updates.size))
    }
}

/** The chapter's line: its name, its scanlator, and upstream's "Page: N" while partly read. */
@Composable
private fun chapterDetail(update: UpdatesWithRelations): String = listOfNotNull(
    update.chapterName,
    update.scanlator?.takeIf { it.isNotBlank() },
    chapterProgress(update.read, update.lastPageRead),
).joinToString(" · ")

/**
 * Upstream's `UpdatesUiItem` spacing: 16 dp at the sides, the cover 6 dp from the top and bottom, 16 dp
 * between the cover and the text, and 16 dp after the text plus the indicator's own 4 dp before the
 * control at the end. The fold row takes the same, so a fold and its chapters line up.
 */
private val UPDATE_ROW_PADDING = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
private val UPDATE_ROW_GAP = 16.dp
private val UPDATE_ROW_END_GAP = 16.dp + 4.dp
