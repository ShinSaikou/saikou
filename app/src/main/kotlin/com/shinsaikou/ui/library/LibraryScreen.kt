package com.shinsaikou.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isLandscape
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.ui.components.SearchTopBar
import com.shinsaikou.ui.components.TopBarAction
import com.shinsaikou.ui.components.TopBarMenuItem
import com.shinsaikou.ui.components.TopBarOverflow
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.components.UpdateErrorAction
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.time.Duration.Companion.seconds

/**
 * The tag on the two pane layout's root.
 *
 * Production API for the instrumentation suite: both layouts draw the same grid from the same
 * rows, so no assertion on what is visible can tell them apart. Settings carries the same kind of
 * tag for the same reason.
 */
const val LIBRARY_TWO_PANE_TAG = "library_two_pane"

/**
 * [searchRequest] is a search another screen asked for (a genre on the entry screen), which opens the
 * search with that text and is then handed back through [onSearchRequestConsumed].
 */
@Composable
fun LibraryScreen(searchRequest: String? = null, onSearchRequestConsumed: () -> Unit = {}) {
    val viewModel: LibraryViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tabletUi = isTabletUi()

    // The search field is the screen's own state, not the view model's, and both halves of it are
    // saved. A view model survives a rotation but not the process being reclaimed behind a photo
    // picker, and coming back to a library that has silently forgotten what was typed into it is
    // indistinguishable from the search having found nothing.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Which entry the detail pane is showing. Saved for the same reason the search is, and kept
    // while the layout is a single column rather than cleared: a phone in the Landscape tablet
    // mode swaps layouts on every rotation, and dropping the entry on the way out would mean
    // rotating back to an empty pane instead of to what the owner was reading about.
    var paneEntry by rememberSaveable { mutableStateOf<Long?>(null) }

    // The pane shows a library row, so it closes when the row leaves the library. Membership is
    // read from the unfiltered feed, which is what makes a search or a filter that merely hides
    // the row leave the pane alone, and that map is empty until the first projection lands, so the
    // check waits for one rather than closing the pane on the frame the screen was composed.
    val openEntry = paneEntry?.takeIf { state.loading || it in state.categoriesByManga }

    // The saved text is the source of truth and the view model's query flow follows it, which is
    // also what restores a search after the process came back: there is nothing else to replay it.
    LaunchedEffect(searchQuery) { viewModel.search(searchQuery) }

    // The request becomes the saved text, so it outlives the request as a typed search would.
    LaunchedEffect(searchRequest) {
        if (searchRequest != null) {
            searchOpen = true
            searchQuery = searchRequest
            onSearchRequestConsumed()
        }
    }

    // The column count is per orientation and the view model cannot see the configuration, so the
    // screen reports it. Reported from here rather than read in the grid because the slider in the
    // settings sheet has to write the same one it is showing.
    val landscape = isLandscape()
    LaunchedEffect(landscape) { viewModel.setLandscape(landscape) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryViewModel.Event.Snackbar -> {
                    val message = if (event.formatArgs.isEmpty()) {
                        resources.getString(event.messageRes)
                    } else {
                        resources.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    }
                    snackbarHostState.showSnackbar(message)
                }
                is LibraryViewModel.Event.OpenReader ->
                    navigator.open(Route.Reader(event.mangaId, event.chapterId))
            }
        }
    }

    // Back unwinds in the reverse of the order the state was built: the selection, then the pane,
    // then the search, and only then the tab. A long press is easy to trigger by accident and the
    // gesture that already means "undo that" has to be the way out; a pane opened by tapping a
    // cover is the same gesture one level in; and the search is where a pane opened from a result
    // came from, so closing the pane leaves the results and a second back closes the search, as
    // History already does. The detail screen inside the pane registers its own handler for its
    // chapter selection after this one, and a later handler is the one the dispatcher calls first,
    // so a selection there is cleared before any of these fires.
    val paneOpen = tabletUi && openEntry != null
    BackHandler(enabled = state.selectionMode || paneOpen || searchOpen) {
        when {
            state.selectionMode -> viewModel.clearSelection()
            paneOpen -> paneEntry = null
            else -> {
                searchOpen = false
                searchQuery = ""
            }
        }
    }

    // A tap means "open" until something is selected, and "add or drop this one" afterwards.
    // Opening an entry mid selection would lose the selection behind a screen the owner did not
    // ask for. With two panes up there is no screen to lose it behind, and the pane is left where
    // it is: rebuilding the detail view model on every long press would restart its chapter fetch.
    val openManga: (Long) -> Unit = { mangaId ->
        when {
            state.selectionMode -> viewModel.toggleSelection(mangaId)
            tabletUi -> paneEntry = mangaId
            else -> navigator.open(Route.MangaDetail(mangaId))
        }
    }

    val globalSearch: (String) -> Unit = { query -> navigator.open(Route.GlobalSearch(query = query)) }

    Scaffold(
        topBar = { scrollBehavior ->
            if (state.selectionMode) {
                LibrarySelectionBar(
                    count = state.selection.size,
                    scrollBehavior = scrollBehavior,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onInvertSelection = viewModel::invertSelection,
                    onMarkRead = { viewModel.markSelectedRead(true) },
                    onMarkUnread = { viewModel.markSelectedRead(false) },
                    onMoveToCategory = viewModel::askMoveToCategories,
                    onRemove = viewModel::askRemoveSelected,
                    onDownload = viewModel::downloadSelected.takeUnless { state.selectionHasLocal },
                    // Upstream's library Migrate: the selection in its order, cleared, then the config
                    // screen (phase 4 unit MB).
                    onMigrate = {
                        val ids = state.selection.toList()
                        viewModel.clearSelection()
                        navigator.open(Route.MigrationConfig(ids))
                    },
                    onUpdate = viewModel::updateSelected,
                )
            } else {
                // Upstream's library bar: the search opens in the bar itself, in place of the title, and
                // the actions after it stay put. The update errors come first, as they come and go.
                SearchTopBar(
                    title = toolbarTitle(state),
                    searchQuery = searchQuery.takeIf { searchOpen },
                    onSearchQueryChange = { query ->
                        searchOpen = query != null
                        searchQuery = query.orEmpty()
                    },
                    placeholder = stringResource(R.string.library_search_hint),
                    scrollBehavior = scrollBehavior,
                    statusAction = {
                        UpdateErrorAction(
                            count = state.updateErrorCount,
                            onClick = viewModel::showUpdateErrors,
                        )
                    },
                    actions = {
                        // A filter that is on and invisible is how a library comes to look half
                        // missing, so the icon itself says one is applied.
                        TopBarAction(
                            label = stringResource(R.string.action_filter),
                            icon = Icons.Outlined.FilterList,
                            onClick = viewModel::showSettingsSheet,
                            highlighted = state.hasActiveFilters,
                        )
                        LibraryOverflowMenu(
                            updating = state.updating,
                            onUpdateLibrary = viewModel::updateNow,
                            onUpdateCategory = viewModel::updateCategory,
                            onStopUpdate = viewModel::stopUpdate,
                            // From what the page shows, after filter and search, as upstream picks it.
                            onOpenRandom = state.visibleItems
                                .takeIf { it.isNotEmpty() }
                                ?.let { items -> { openManga(items.random().id) } },
                        )
                    },
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            // A search of an empty library still offers the sources, as upstream's does; the pager
            // that offers it otherwise is not drawn here.
            state.total == 0 -> Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                globalSearchQuery(state.query)?.let { query ->
                    GlobalSearchButton(query = query, onClick = { globalSearch(query) })
                }
                EmptyScreen(
                    stringRes = R.string.library_empty,
                    modifier = Modifier.weight(1f),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = R.string.library_empty_action_browse,
                            icon = Icons.Outlined.Explore,
                            onClick = { navigator.open(Route.Browse()) },
                        ),
                    ),
                )
            }
            else -> {
                // The pager is remembered once the first projection has arrived, so its initial
                // page is the restored category rather than zero.
                val pagerState = rememberPagerState(
                    initialPage = state.activeCategoryIndex.coerceIn(0, maxOf(state.categories.size - 1, 0)),
                    pageCount = { state.categories.size },
                )
                LaunchedEffect(pagerState.currentPage) { viewModel.setActiveCategory(pagerState.currentPage) }

                if (tabletUi) {
                    TwoPanelBox(
                        // The insets are consumed here as well as applied, because the pane hosts
                        // a whole screen and its scaffold would otherwise add the status and
                        // navigation bars a second time inside a window that already paid for
                        // them. The vendored scaffold reads exactly this signal.
                        modifier = Modifier
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .testTag(LIBRARY_TWO_PANE_TAG),
                        startContent = {
                            LibraryGrid(
                                state = state,
                                pagerState = pagerState,
                                padding = PaddingValues(),
                                onRefresh = viewModel::updateNow,
                                onTab = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                                onClick = openManga,
                                onLongClick = viewModel::longPressEntry,
                                onContinueReading = viewModel::continueReading,
                                onReorder = viewModel::reorder,
                                onGlobalSearch = globalSearch,
                            )
                        },
                        endContent = {
                            LibraryDetailPane(mangaId = openEntry, onClose = { paneEntry = null })
                        },
                    )
                } else {
                    LibraryGrid(
                        state = state,
                        pagerState = pagerState,
                        padding = padding,
                        onRefresh = viewModel::updateNow,
                        onTab = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                        onClick = openManga,
                        onLongClick = viewModel::longPressEntry,
                        onContinueReading = viewModel::continueReading,
                        onReorder = viewModel::reorder,
                        onGlobalSearch = globalSearch,
                    )
                }
            }
        }
    }

    LibraryDialogs(
        state = state,
        preferences = viewModel.libraryPreferences,
        onDismissRequest = viewModel::dismissDialog,
        onConfirmCategories = viewModel::moveSelectedToCategories,
        onConfirmRemove = viewModel::removeSelectedFromLibrary,
        onFilter = viewModel::setFilter,
        onTrackerFilter = viewModel::setTrackerFilter,
        onSort = viewModel::setSort,
        onDisplayMode = viewModel::setDisplayMode,
        onColumns = viewModel::setColumns,
        onOpenManga = { mangaId ->
            viewModel.dismissDialog()
            // The same route the grid takes, so a row of the update error sheet lands in the pane
            // on a tablet and on its own screen on a phone.
            openManga(mangaId)
        },
        onOpenLibrarySettings = {
            viewModel.dismissDialog()
            navigator.open(Route.Settings(SettingsSection.LIBRARY))
        },
        onRetryUpdateErrors = viewModel::retryUpdateErrors,
        onMigrateUpdateErrors = { ids ->
            viewModel.dismissDialog()
            navigator.open(Route.MigrationConfig(ids))
        },
    )
}

/**
 * The library itself: the category tabs and the pages of covers under them.
 *
 * Extracted from the screen because it is the start pane of the tablet layout and the whole of the
 * phone one, and the only difference between the two is the padding it is handed. On a tablet the
 * scaffold's padding is applied to the two pane box instead, so this gets none.
 */
@Composable
private fun LibraryGrid(
    state: LibraryViewModel.State,
    pagerState: PagerState,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onTab: (Int) -> Unit,
    onClick: (Long) -> Unit,
    onLongClick: (categoryId: Long, mangaId: Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    onReorder: (categoryId: Long, mangaId: Long, order: List<Long>) -> Boolean,
    onGlobalSearch: (String) -> Unit,
) {
    // The spinner acknowledges the pull for a second and then goes, as upstream's does. A run takes
    // minutes and its progress is in the notification; a spinner held over the covers for all of
    // it looked like the library itself was still loading.
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullRefresh(
        refreshing = refreshing,
        // A pull during a selection would start a library run the owner did not ask for while
        // their finger was on a row, so the gesture steps aside until it is over.
        enabled = !state.selectionMode,
        onRefresh = {
            onRefresh()
            scope.launch {
                refreshing = true
                delay(1.seconds)
                refreshing = false
            }
        },
        indicatorPadding = padding,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.showCategoryTabs && state.categories.size > 1) {
                val itemCount: ((Category) -> Int)? = if (state.showItemCount) {
                    ({ category -> state.items(category.id).size })
                } else {
                    null
                }
                LibraryTabs(
                    categories = state.categories,
                    parents = state.categoryParents,
                    selectedIndex = pagerState.currentPage.coerceAtMost(state.categories.lastIndex),
                    itemCount = itemCount,
                    onTab = onTab,
                )
            }
            LibraryPager(
                state = state,
                pagerState = pagerState,
                contentPadding = PaddingValues(8.dp),
                onClick = onClick,
                onLongClick = onLongClick,
                onContinueReading = onContinueReading,
                onReorder = onReorder,
                onGlobalSearch = onGlobalSearch,
            )
        }
    }
}

/**
 * The bar says which category is showing only when the tabs do not, and carries the entry count
 * only when the owner asked for it. With tabs up the count belongs on each tab, so the title counts
 * the whole library instead.
 *
 * The count is part of the title string rather than the pill the contract draws: [SearchTopBar] has
 * one title slot, as every other screen's bar does, so widening it for one count is the change
 * carry-over 24 warns against.
 */
@Composable
private fun toolbarTitle(state: LibraryViewModel.State): String {
    val name = if (state.showCategoryTabs) {
        stringResource(R.string.library_title)
    } else {
        state.activeCategory?.visualName ?: stringResource(R.string.library_title)
    }
    if (!state.showItemCount) return name
    // With tabs up every tab carries its own count, so the title counts the library instead. Not a
    // sum over the categories: an entry in two of them would be counted twice.
    val count = if (state.showCategoryTabs) state.shown else state.visibleItems.size
    return stringResource(R.string.library_title_count_format, name, count)
}

/**
 * The bar's overflow, laid out as upstream's library overflow is: Update library, Update category,
 * then Open random entry. A text button in the bar changed width when it turned into Stop, which
 * moved every icon beside it mid run.
 *
 * While a run is going the menu offers Stop update in place of the two updates, as starting a second
 * run would only answer that one is already going. The notification's Cancel stops it too.
 * [onOpenRandom] is null while the page shows nothing to pick from, and the item is left out.
 */
@Composable
private fun LibraryOverflowMenu(
    updating: Boolean,
    onUpdateLibrary: () -> Unit,
    onUpdateCategory: () -> Unit,
    onStopUpdate: () -> Unit,
    onOpenRandom: (() -> Unit)?,
) {
    TopBarOverflow(
        items = buildList {
            if (updating) {
                add(TopBarMenuItem(stringResource(R.string.action_stop_update), onStopUpdate))
            } else {
                add(TopBarMenuItem(stringResource(R.string.action_update_library), onUpdateLibrary))
                add(TopBarMenuItem(stringResource(R.string.action_update_category), onUpdateCategory))
            }
            if (onOpenRandom != null) {
                add(TopBarMenuItem(stringResource(R.string.action_open_random_manga), onOpenRandom))
            }
        },
    )
}
