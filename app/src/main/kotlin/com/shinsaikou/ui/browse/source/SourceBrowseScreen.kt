package com.shinsaikou.ui.browse.source

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.MigrationPick
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SourceBrowseMode
import com.shinsaikou.presentation.core.util.isLandscape
import com.shinsaikou.source.SourceIds
import com.shinsaikou.ui.components.ChangeCategoryDialog
import com.shinsaikou.ui.components.GRID_SPACING
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.MangaComfortableGridItem
import com.shinsaikou.ui.components.MangaCompactGridItem
import com.shinsaikou.ui.components.MangaListRow
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SearchField
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.components.mangaGridCells
import com.shinsaikou.ui.components.selectionTopBarColors
import com.shinsaikou.ui.migration.MigrateMangaDialog
import com.shinsaikou.ui.storage.StorageLocationViewModel
import com.shinsaikou.ui.storage.rememberStorageLocationPicker
import com.shinsaikou.ui.util.ErrorText
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.RadioMenuItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground

/**
 * One source's listing: the popular or latest page, or the results of a search against the source's
 * own filter tree.
 *
 * [query] and [mode] come from the route rather than from a tap, so a deep link and the section of
 * the app that opened it (global search asking for "more from this source", say) land on the same
 * listing the owner asked for instead of on the front page.
 *
 * With [migratingMangaId] the listing is in pick mode (phase 4 unit MB): a tap picks the result as
 * that entry's migration target, as a tap in manual search does, and a long press opens it. A pick
 * goes back to the migration list below; with none, MA's dialog migrates the one entry and a failure
 * is said in a snackbar.
 */
@Composable
fun SourceBrowseScreen(
    sourceId: Long,
    query: String? = null,
    mode: SourceBrowseMode = SourceBrowseMode.POPULAR,
    migratingMangaId: Long? = null,
) {
    val viewModel: SourceBrowseViewModel =
        assistedMetroViewModel<SourceBrowseViewModel, SourceBrowseViewModel.Factory> {
            create(sourceId, query, mode, migratingMangaId)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val onOpen: (Manga) -> Unit = { manga -> navigator.open(Route.MangaDetail(manga.id, fromSource = true)) }
    val onPick: (Manga) -> Unit = if (migratingMangaId == null) {
        onOpen
    } else {
        { manga ->
            when (navigator.pickMigrationTarget(migratingMangaId, manga.id)) {
                MigrationPick.RETURNED -> Unit
                // The listing stays on screen, so it says so itself rather than in a toast.
                MigrationPick.IN_BATCH -> scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.migration_pick_in_batch))
                }
                MigrationPick.NO_LIST -> viewModel.showMigrateDialog(manga)
            }
        }
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // The flags survive a rotation. The list position carries its own saver; these do not, and a
    // rotation that closes a half filled filter sheet throws the whole selection away.
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var displayMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The column count is per orientation, as in the library, and only the screen can see which.
    val columns = if (isLandscape()) state.landscapeColumns else state.portraitColumns

    // Back leaves the selection before it leaves the listing, as on the entry screen.
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SourceBrowseViewModel.Event.Added ->
                    resources.getQuantityString(R.plurals.source_added_to_library_count, event.count, event.count)
                SourceBrowseViewModel.Event.AllInLibrary -> resources.getString(R.string.source_selection_all_in_library)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // A later page that failed, in every layout: upstream's BrowseSourceContent says it in a snackbar
    // that stays until its Retry, which asks for the failed page again with the entries kept. A new
    // request takes the error away, and with it this snackbar. The first page's failure stays in
    // the listing's header, with its own retry from page one.
    val nextPageError = state.nextPageError
    LaunchedEffect(nextPageError) {
        if (nextPageError != null) {
            val result = snackbarHostState.showSnackbar(
                message = ErrorText.of(resources, nextPageError),
                actionLabel = resources.getString(R.string.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> viewModel.retryNextPage()
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            if (state.selectionMode) {
                SelectionBar(
                    count = state.selection.size,
                    scrollBehavior = scrollBehavior,
                    onClearSelection = viewModel::clearSelection,
                    onAddToLibrary = viewModel::addSelectedToLibrary,
                    onSelectAll = viewModel::selectAll,
                    onInvertSelection = viewModel::invertSelection,
                )
            } else {
                ScreenTopBar(
                    title = state.sourceName ?: stringResource(R.string.source_title_fallback),
                    onBack = navigator::back,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        DisplayModeMenu(
                            displayMode = state.displayMode,
                            expanded = displayMenuExpanded,
                            onExpandedChange = { displayMenuExpanded = it },
                            onDisplayMode = viewModel::setDisplayMode,
                        )
                        // A source that declares no filters gets no button: an action that can only open
                        // an empty sheet is worse than no action at all.
                        if (state.filters.list.isNotEmpty()) {
                            IconButton(onClick = { filterSheetOpen = true }) {
                                Icon(
                                    Icons.Outlined.FilterList,
                                    // Whether filters are in force is said in words as well as drawn in
                                    // colour, because a tint is unreachable without sight.
                                    contentDescription = stringResource(
                                        if (state.filtersApplied) {
                                            R.string.source_filters_active
                                        } else {
                                            R.string.action_filter
                                        },
                                    ),
                                    tint = if (state.filtersApplied) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        SourceOverflowMenu(
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = it },
                            homeUrl = state.homeUrl,
                            onSettings = { navigator.open(Route.SourceSettings(sourceId)) },
                            onOpenInWebView = { url ->
                                navigator.open(Route.WebView(url = url, sourceId = sourceId, title = state.sourceName))
                            },
                        )
                    },
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            !state.installed -> EmptyScreen(
                stringRes = R.string.error_source_not_installed,
                modifier = Modifier.padding(padding),
            )
            else -> SourceListing(
                state = state,
                layout = browseLayoutOf(state.displayMode),
                columns = columns,
                listState = listState,
                gridState = gridState,
                modifier = Modifier.fillMaxSize().padding(padding),
                header = {
                    val homeUrl = state.homeUrl
                    val openHome: (() -> Unit)? = homeUrl?.let {
                        { navigator.open(Route.WebView(url = it, sourceId = sourceId, title = state.sourceName)) }
                    }
                    CompositionLocalProvider(LocalErrorOpenInWebView provides openHome) {
                        ListingHeader(
                            state = state,
                            empty = if (sourceId == SourceIds.LOCAL && state.query.isBlank()) {
                                { LocalEmptyListing(onChosen = viewModel::retry) }
                            } else {
                                null
                            },
                            onQueryChange = viewModel::setQuery,
                            onSubmit = viewModel::submitSearch,
                            onMode = viewModel::setMode,
                            onClearSearch = viewModel::clearSearch,
                            onRetry = viewModel::retry,
                            errorText = { ErrorText.of(resources, it) },
                        )
                    }
                },
                onLoadNextPage = viewModel::loadNextPage,
                onClick = { manga ->
                    if (state.selectionMode) viewModel.toggleSelection(manga.id) else onPick(manga)
                },
                onLongClick = if (migratingMangaId != null) {
                    onOpen
                } else {
                    { manga ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.longPressEntry(manga.id)
                    }
                },
            )
        }
    }

    state.addCategories?.let { rows ->
        ChangeCategoryDialog(
            rows = rows,
            onDismissRequest = viewModel::dismissAddCategories,
            onConfirm = viewModel::confirmAddCategories,
        )
    }

    val current = state.migratingFrom
    val target = state.migrateTarget
    if (current != null && target != null) {
        MigrateMangaDialog(
            current = current,
            target = target,
            onClickTitle = { onOpen(target) },
            onDismissRequest = viewModel::dismissMigrateDialog,
            onFailed = {
                viewModel.dismissMigrateDialog()
                scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.migration_single_failed)) }
            },
            onComplete = { navigator.completeSingleMigration(target.id) },
        )
    }

    if (filterSheetOpen) {
        SourceFilterSheet(
            filters = state.filters,
            onDismissRequest = { filterSheetOpen = false },
            onReset = viewModel::resetFilters,
            // The sheet returns the very tree it was handed, edited in place. Handing it straight
            // back is the whole point: see SourceFilterSheet for what a rebuilt tree would cost.
            onApply = { edited ->
                viewModel.setFilters(edited)
                viewModel.applyFilters()
            },
        )
    }
}

/**
 * The listing in the stored display mode. The search field, the mode chips and the notices come
 * first and the load-more spinner last, each across the whole width in a grid, and the next page
 * is asked for from whichever scroll state is on screen. [onLongClick] selects outside pick mode
 * (upstream's long press adds the one entry to the library instead) and opens the result in pick mode,
 * where a tap picks it; an entry in [State.selection][SourceBrowseViewModel.State.selection] is drawn
 * selected.
 */
@Composable
private fun SourceListing(
    state: SourceBrowseViewModel.State,
    layout: BrowseLayout,
    columns: Int,
    listState: LazyListState,
    gridState: LazyGridState,
    modifier: Modifier,
    header: @Composable () -> Unit,
    onLoadNextPage: () -> Unit,
    onClick: (Manga) -> Unit,
    onLongClick: (Manga) -> Unit,
) {
    // Read by the page trigger through state objects, so it sees each page land without restarting.
    val listedItems by rememberUpdatedState(1 + state.items.size + if (state.loadingMore) 1 else 0)
    val page by rememberUpdatedState(state.page)
    when (layout) {
        BrowseLayout.List -> {
            LaunchedEffect(listState) {
                requestPagesNearEnd({ listState.layoutInfo.listingPosition(listedItems, page) }, onLoadNextPage)
            }
            LazyColumn(state = listState, modifier = modifier) {
                item(key = HEADER_KEY) { header() }
                items(state.items, key = Manga::id) { manga ->
                    val mark = inLibraryMark(state.inLibrary(manga))
                    MangaListRow(
                        cover = manga,
                        title = manga.title,
                        detail = "",
                        modifier = Modifier.selectedBackground(manga.id in state.selection),
                        onClick = { onClick(manga) },
                        onLongClick = { onLongClick(manga) },
                        coverAlpha = mark.coverAlpha,
                        badges = if (mark.badge) ({ InLibraryBadge() }) else null,
                    )
                }
                if (state.loadingMore) {
                    item(key = LOADING_MORE_KEY) { LoadingMore() }
                }
            }
        }
        is BrowseLayout.CompactGrid, BrowseLayout.ComfortableGrid -> {
            LaunchedEffect(gridState) {
                requestPagesNearEnd({ gridState.layoutInfo.listingPosition(listedItems, page) }, onLoadNextPage)
            }
            LazyVerticalGrid(
                columns = mangaGridCells(columns),
                state = gridState,
                modifier = modifier,
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
            ) {
                item(key = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) { header() }
                items(state.items, key = Manga::id) { manga ->
                    val mark = inLibraryMark(state.inLibrary(manga))
                    val coverAlpha = mark.coverAlpha
                    val badges: @Composable (RowScope.() -> Unit)? =
                        if (mark.badge) ({ InLibraryBadge() }) else null
                    if (layout is BrowseLayout.CompactGrid) {
                        MangaCompactGridItem(
                            cover = manga,
                            title = manga.title.takeIf { layout.showTitles },
                            coverDescription = manga.title,
                            isSelected = manga.id in state.selection,
                            onClick = { onClick(manga) },
                            onLongClick = { onLongClick(manga) },
                            coverAlpha = coverAlpha,
                            badges = badges,
                        )
                    } else {
                        MangaComfortableGridItem(
                            cover = manga,
                            title = manga.title,
                            isSelected = manga.id in state.selection,
                            onClick = { onClick(manga) },
                            onLongClick = { onLongClick(manga) },
                            coverAlpha = coverAlpha,
                            badges = badges,
                        )
                    }
                }
                if (state.loadingMore) {
                    item(key = LOADING_MORE_KEY, span = { GridItemSpan(maxLineSpan) }) { LoadingMore() }
                }
            }
        }
    }
}

/** Everything above the entries, drawn as one full-width item in either layout. */
@Composable
private fun ListingHeader(
    state: SourceBrowseViewModel.State,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMode: (SourceBrowseMode) -> Unit,
    onClearSearch: () -> Unit,
    onRetry: () -> Unit,
    errorText: (Throwable) -> String,
    empty: (@Composable () -> Unit)? = null,
) {
    Column {
        SearchField(
            value = state.query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.source_search_hint),
            onSubmit = onSubmit,
        )
        ModeRow(state = state, onMode = onMode, onClearSearch = onClearSearch)
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp)) { LoadingScreen() }
            state.error != null -> ErrorRow(message = errorText(state.error), onRetry = onRetry)
            state.items.isEmpty() && empty != null -> empty()
            state.items.isEmpty() -> InlineNotice(stringResource(R.string.source_no_results))
        }
    }
}

/**
 * The Local source's empty listing (F5, unit storage-location): the folder it reads, by the name the
 * owner can find it under, and Choose folder, the Storage location picker. A folder chosen there
 * lists again, since the source now reads its `local` folder.
 */
@Composable
private fun LocalEmptyListing(onChosen: () -> Unit) {
    val storage: StorageLocationViewModel = metroViewModel()
    val storageState by storage.state.collectAsStateWithLifecycle()
    val choose = rememberStorageLocationPicker(storage)
    var listedFor by remember { mutableStateOf(storageState.name) }
    LaunchedEffect(storageState.name) {
        if (storageState.name != listedFor) {
            listedFor = storageState.name
            onChosen()
        }
    }
    Column(Modifier.testTag(LOCAL_EMPTY_TAG)) {
        InlineNotice(stringResource(R.string.local_source_empty_folder_format, storageState.localFolder.orEmpty()))
        TextButton(onClick = choose, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.action_choose_folder))
        }
    }
}

/** The tag on the Local source's empty listing, which a device test reads. */
internal const val LOCAL_EMPTY_TAG = "local_source_empty"

@Composable
private fun LoadingMore() {
    Box(Modifier.fillMaxWidth().padding(24.dp)) { LoadingScreen() }
}

/**
 * Upstream's display mode action: a list glyph while the list is on, a grid glyph otherwise, opening
 * its three choices in upstream's order. A stored cover-only grid checks none of them, as in upstream.
 */
@Composable
private fun DisplayModeMenu(
    displayMode: LibraryDisplayMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDisplayMode: (LibraryDisplayMode) -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                if (displayMode == LibraryDisplayMode.List) {
                    Icons.AutoMirrored.Filled.ViewList
                } else {
                    Icons.Filled.ViewModule
                },
                contentDescription = stringResource(R.string.action_display_mode),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DISPLAY_MODE_CHOICES.forEach { (mode, label) ->
                RadioMenuItem(
                    text = { Text(stringResource(label)) },
                    isChecked = displayMode == mode,
                ) {
                    onExpandedChange(false)
                    onDisplayMode(mode)
                }
            }
        }
    }
}

/** Upstream's menu order, with the library sheet's words for the same three modes. */
internal val DISPLAY_MODE_CHOICES = listOf(
    LibraryDisplayMode.ComfortableGrid to R.string.library_display_comfortable_grid,
    LibraryDisplayMode.CompactGrid to R.string.library_display_compact_grid,
    LibraryDisplayMode.List to R.string.library_display_list,
)

/**
 * The app bar overflow. Both items lead out of the listing rather than changing it, which is why
 * neither is a bar button: a source's settings are visited once, and the web page is the escape
 * hatch for a source that wants a login or an interstitial solved by hand.
 */
@Composable
private fun SourceOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    homeUrl: String?,
    onSettings: () -> Unit,
    onOpenInWebView: (String) -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_menu_overflow_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.source_settings_title)) },
                onClick = {
                    onExpandedChange(false)
                    onSettings()
                },
            )
            if (homeUrl != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open_in_web_view)) },
                    onClick = {
                        onExpandedChange(false)
                        onOpenInWebView(homeUrl)
                    },
                )
            }
        }
    }
}

/**
 * The bar the top bar becomes while entries are selected, on the entry screen's selection colours:
 * the count, Add to library, then Select all and Invert as on the entry screen. Back and the arrow
 * leave the selection. Select all takes the entries loaded so far.
 */
@Composable
private fun SelectionBar(
    count: Int,
    scrollBehavior: TopAppBarScrollBehavior?,
    onClearSelection: () -> Unit,
    onAddToLibrary: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
) {
    ScreenTopBar(
        title = stringResource(R.string.selection_count_format, count),
        onBack = onClearSelection,
        scrollBehavior = scrollBehavior,
        colors = selectionTopBarColors(),
        actions = {
            IconButton(onClick = onAddToLibrary) {
                Icon(Icons.Outlined.LibraryAdd, contentDescription = stringResource(R.string.action_add_to_library))
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            IconButton(onClick = onInvertSelection) {
                Icon(Icons.Outlined.FlipToBack, contentDescription = stringResource(R.string.action_invert_selection))
            }
        },
    )
}

@Composable
private fun ModeRow(
    state: SourceBrowseViewModel.State,
    onMode: (SourceBrowseMode) -> Unit,
    onClearSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.mode == SourceBrowseMode.POPULAR,
            onClick = { onMode(SourceBrowseMode.POPULAR) },
            label = { Text(stringResource(R.string.source_mode_popular)) },
        )
        if (state.supportsLatest) {
            FilterChip(
                selected = state.mode == SourceBrowseMode.LATEST,
                onClick = { onMode(SourceBrowseMode.LATEST) },
                label = { Text(stringResource(R.string.source_mode_latest)) },
            )
        }
        if (state.mode == SourceBrowseMode.SEARCH) {
            FilterChip(
                selected = true,
                onClick = onClearSearch,
                label = { Text(stringResource(R.string.action_close)) },
            )
        }
    }
}

/**
 * Opens the source's page in the app's WebView, for [ErrorRow]; null when the source has no page.
 * A listing that fails is most often a challenge or a login, and the page is where a person answers
 * either, so the way there sits beside Retry as upstream's does and not only in the overflow. The
 * screen provides it around the header rather than threading it through every level of it.
 */
private val LocalErrorOpenInWebView = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    val openInWebView = LocalErrorOpenInWebView.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            if (openInWebView != null) {
                OutlinedButton(onClick = openInWebView) { Text(stringResource(R.string.action_open_in_web_view)) }
            }
        }
    }
}

private const val HEADER_KEY = "header"
private const val LOADING_MORE_KEY = "loading_more"
