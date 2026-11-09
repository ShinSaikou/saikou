package com.shinsaikou.ui.manga

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.domain.library.search.genreSearchQuery
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.ui.chapter.ChapterDetailText
import com.shinsaikou.ui.chapter.ChapterDownload
import com.shinsaikou.ui.chapter.ChapterDownloadAction
import com.shinsaikou.ui.chapter.ChapterDownloadControl
import com.shinsaikou.ui.chapter.ChapterMarkedText
import com.shinsaikou.ui.chapter.chapterProgress
import com.shinsaikou.ui.chapter.chapterUploadDate
import com.shinsaikou.ui.components.ChangeCategoryDialog
import com.shinsaikou.ui.components.DownloadAction
import com.shinsaikou.ui.components.DownloadDropdownMenu
import com.shinsaikou.ui.components.DuplicateMangaDialog
import com.shinsaikou.ui.components.LabelledExtendedFloatingActionButton
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ListRowBody
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.components.selectionTopBarColors
import com.shinsaikou.ui.components.categoryChecks
import com.shinsaikou.ui.migration.MigrateMangaDialog
import com.shinsaikou.ui.util.ErrorText
import com.shinsaikou.ui.util.copyToClipboard
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import me.saket.swipe.SwipeAction
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.Surface as LongPressSurface
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.DecimalFormat

/**
 * The tag on the app bar's overflow button, and the tag on its Refresh item.
 *
 * These two are production API for the instrumentation suite and not decoration. Compose's pull to
 * refresh cannot be driven from outside the process: neither `adb input swipe` nor a synthetic
 * motion event reaches it, so before the overflow existed no device test could make this screen
 * fetch a chapter list at all. Nothing inside this module reads either tag, which is precisely what
 * makes them look unused; removing them removes the suite's only handle on a refresh.
 */
const val MANGA_OVERFLOW_TAG = "manga_overflow"

/** See [MANGA_OVERFLOW_TAG]. */
const val MANGA_REFRESH_TAG = "manga_refresh"

/** The header's tracking button (phase 3 unit T3), for the track sheet's device check. */
const val MANGA_TRACKING_TAG = "manga_tracking"

/**
 * The header's cover, which opens the cover viewer (phase 5 unit ED). Its description is the
 * entry's title, which a library cell's cover shares on a tablet's two panes.
 */
const val MANGA_COVER_TAG = "manga_cover"

/**
 * The tag on the two pane layout's root, for the same reason Settings and the library carry one:
 * both layouts draw the same header and the same chapter rows, so nothing visible tells them apart.
 */
const val MANGA_TWO_PANE_TAG = "manga_two_pane"

/**
 * The chapter list of one entry, as a route and as the library's tablet pane. The two parameters
 * with defaults are what the pane needs and the route does not.
 *
 * [onBack] is what the app bar's back arrow does. Null is the route case, where back means pop; the
 * pane passes its own, because popping there would take the library with it.
 *
 * [twoPane] is the layout, and the pane passes false whatever the window is. The default reads the
 * one tablet boolean, which is a fact about the window rather than about the space this screen was
 * handed, and inside a pane roughly 420 dp wide those are not the same thing: the screen split
 * again, the header column came out one character wide and the chapter list took the rest.
 */
@Composable
fun MangaDetailScreen(
    mangaId: Long,
    fromSource: Boolean,
    onBack: (() -> Unit)? = null,
    twoPane: Boolean = isTabletUi(),
) {
    val viewModel: MangaDetailViewModel =
        assistedMetroViewModel<MangaDetailViewModel, MangaDetailViewModel.Factory> {
            create(mangaId, fromSource)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Every piece of local state here survives a rotation. The two scroll positions carry their own
    // savers already; the menu flag needs rememberSaveable spelled out, or a rotation with the menu
    // open leaves the owner looking at an app bar that swallowed their tap.
    val listState = rememberLazyListState()
    val headerScrollState = rememberScrollState()
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MangaDetailViewModel.Event.Snackbar -> {
                    // A refresh failure carries the throwable itself, so the one place that knows
                    // how to turn one into text is the only place that does it.
                    val args = event.args.map { if (it is Throwable) ErrorText.of(resources, it) else it }
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes, *args.toTypedArray()))
                }
                is MangaDetailViewModel.Event.OpenReader ->
                    navigator.open(Route.Reader(event.mangaId, event.chapterId))
                is MangaDetailViewModel.Event.OpenWebView ->
                    navigator.open(Route.WebView(event.url, event.sourceId, event.title))
                is MangaDetailViewModel.Event.ConfirmTrackerUpdate -> {
                    // Upstream's snackbar: short, with OK and a dismiss action; only OK pushes.
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.confirm_tracker_update, event.chapterNumber.toInt()),
                        actionLabel = resources.getString(R.string.action_ok),
                        withDismissAction = true,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.confirmTrackerUpdate(event.chapterNumber)
                }
                MangaDetailViewModel.Event.OpenTrackingSettings ->
                    navigator.open(Route.Settings(SettingsSection.TRACKING))
                // Upstream's prompt after a download: Add and a dismiss action, so it stays until
                // answered, as upstream's does. In a coroutine of its own, so the events behind it
                // (a chapter tap's reader, the add's own snackbar) are not held until it goes.
                MangaDetailViewModel.Event.PromptAddToLibrary -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.snack_add_to_library),
                        actionLabel = resources.getString(R.string.action_add),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.addToLibraryFromPrompt()
                }
                // The removal with Undo, in a coroutine of its own for the reason the prompt above
                // gives.
                is MangaDetailViewModel.Event.RemovedFromLibrary -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.manga_removed_from_library),
                        actionLabel = resources.getString(R.string.action_undo),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoRemoveFromLibrary(event.removal)
                }
            }
        }
    }

    val success = state as? MangaDetailViewModel.State.Success
    val selectionMode = success?.selectionMode == true
    val onGenreSearch: (String) -> Unit = { genre -> navigator.searchLibrary(genreSearchQuery(genre)) }
    val onGlobalSearch: (String) -> Unit = { query -> navigator.open(Route.GlobalSearch(query = query)) }
    // Back leaves the selection before it leaves the screen, which is the only way out of selection
    // mode that does not need the owner to find a specific control first.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    Scaffold(
        topBar = { scrollBehavior ->
            if (success != null && selectionMode) {
                ScreenTopBar(
                    title = stringResource(R.string.selection_count_format, success.selection.size),
                    onBack = viewModel::clearSelection,
                    scrollBehavior = scrollBehavior,
                    colors = selectionTopBarColors(),
                    actions = {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Outlined.SelectAll, stringResource(R.string.action_select_all))
                        }
                        IconButton(onClick = viewModel::invertSelection) {
                            Icon(Icons.Outlined.FlipToBack, stringResource(R.string.action_invert_selection))
                        }
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Outlined.Deselect, stringResource(R.string.action_deselect_all))
                        }
                    },
                )
            } else {
                ScreenTopBar(
                    title = success?.manga?.displayTitle ?: stringResource(R.string.manga_title_fallback),
                    onBack = onBack ?: navigator::back,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (success != null) {
                            // Upstream's toolbar download menu, first on the bar and hidden for a Local
                            // entry or a source that is not installed (phase 3 unit AD).
                            if (!success.isLocal && success.sourceInstalled) {
                                MangaDownloadMenu(onDownload = viewModel::download)
                            }
                            IconButton(onClick = viewModel::showChapterSettings) {
                                Icon(
                                    Icons.Outlined.FilterList,
                                    contentDescription = stringResource(R.string.action_filter),
                                    tint = if (success.filterActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            MangaOverflowMenu(
                                expanded = menuExpanded,
                                onExpandedChange = { menuExpanded = it },
                                showWebView = success.webViewUrl != null,
                                onRefresh = { viewModel.refresh() },
                                onReadingMode = viewModel::showReadingMode,
                                onEditDetails = viewModel::showEditDetails,
                                onNotes = viewModel::showNotes,
                                // Upstream's Migrate, only while the entry is in the library; one entry
                                // skips the config screen's sheet (phase 4 unit MB).
                                onMigrate = {
                                    navigator.open(Route.MigrationConfig(listOf(success.manga.id)))
                                }.takeIf { success.manga.favorite },
                                onMergeWith = {
                                    navigator.open(Route.MergeSearch(success.manga.id))
                                }.takeIf { success.mergedEntries },
                                onOpenInWebView = viewModel::openInWebView,
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectionMode) {
                MangaSelectionBar(
                    actions = success.selectionActions,
                    onMarkRead = { viewModel.markSelectedRead(read = true) },
                    onMarkUnread = { viewModel.markSelectedRead(read = false) },
                    onMarkPreviousRead = viewModel::markSelectedPreviousRead,
                    onBookmark = { viewModel.bookmarkSelected(bookmark = true) },
                    onRemoveBookmark = { viewModel.bookmarkSelected(bookmark = false) },
                    onDownload = viewModel::downloadSelected,
                    onDelete = viewModel::deleteSelected,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = success?.nextUnread != null && !selectionMode) {
                LabelledExtendedFloatingActionButton(
                    text = stringResource(R.string.action_continue_reading),
                    onClick = viewModel::continueReading,
                )
            }
        },
    ) { padding ->
        when (success) {
            null -> if (state is MangaDetailViewModel.State.NotFound) {
                // The id has no row (phase 4 unit missing-entry): said in place, under the app
                // bar's back arrow, which the tablet pane points at its own close.
                EmptyScreen(stringRes = R.string.manga_not_found, modifier = Modifier.padding(padding))
            } else {
                LoadingScreen(Modifier.padding(padding))
            }
            else -> PullRefresh(
                refreshing = success.refreshing,
                enabled = success.sourceInstalled,
                onRefresh = { viewModel.refresh() },
                indicatorPadding = padding,
            ) {
                if (twoPane) {
                    TwoPanelBox(
                        modifier = Modifier.padding(padding).testTag(MANGA_TWO_PANE_TAG),
                        startContent = {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(headerScrollState)) {
                                MangaHeader(
                                    success,
                                    viewModel::toggleFavorite,
                                    viewModel::openTracking,
                                    viewModel::showUpdateInterval,
                                    viewModel::toggleDescription,
                                    viewModel::showCover,
                                    viewModel::showNotes,
                                    viewModel::showMergedSources,
                                    onGenreSearch,
                                    onGlobalSearch,
                                )
                            }
                        },
                        endContent = {
                            ChapterList(
                                state = success,
                                padding = PaddingValues(),
                                listState = listState,
                                viewModel = viewModel,
                                header = null,
                            )
                        },
                    )
                } else {
                    ChapterList(
                        state = success,
                        padding = padding,
                        listState = listState,
                        viewModel = viewModel,
                        header = {
                            MangaHeader(
                                success,
                                viewModel::toggleFavorite,
                                viewModel::openTracking,
                                viewModel::showUpdateInterval,
                                viewModel::toggleDescription,
                                viewModel::showCover,
                                viewModel::showNotes,
                                viewModel::showMergedSources,
                                onGenreSearch,
                                onGlobalSearch,
                            )
                        },
                    )
                }
            }
        }
    }

    MangaDialogs(
        state = success,
        onDismissRequest = viewModel::dismissDialog,
        onAddAnyway = viewModel::addAnyway,
        onConfirmCategories = viewModel::setCategories,
        onOpenDuplicate = { duplicateId ->
            viewModel.dismissDialog()
            navigator.open(Route.MangaDetail(duplicateId))
        },
        onMigrateDuplicate = viewModel::showMigrateDialog,
        onMergeDuplicate = viewModel::mergeWithDuplicate,
        onConfirmRemoveMerged = viewModel::confirmRemoveMerged,
        onReorderSources = viewModel::setMergedSourceOrder,
        onUseAsMain = viewModel::useAsMain,
        onSourceCheckUpdates = viewModel::setSourceCheckUpdates,
        onRemoveSource = viewModel::removeMergedSource,
        onShowEveryCopy = viewModel::setShowEveryCopy,
        onUnmerge = viewModel::unmerge,
        onMigrationFailed = { viewModel.migrationFailed() },
        onFilter = viewModel::setChapterFilter,
        onSort = viewModel::setChapterSort,
        onDisplay = viewModel::setChapterDisplay,
        onGroupByVolume = viewModel::setChapterGroupByVolume,
        onSetAsDefault = viewModel::setAsDefaultChapterSettings,
        onResetToDefault = viewModel::resetToDefaultChapterSettings,
        onOpenScanlatorFilter = viewModel::showScanlatorFilter,
        onConfirmScanlators = viewModel::confirmScanlators,
        // Two writes rather than one: each viewer flag is its own round trip through the entry, and
        // the sheet applies both together.
        onApplyReaderModes = { mode, orientation ->
            viewModel.setReadingMode(mode)
            viewModel.setOrientation(orientation)
        },
        onUseReaderDefaults = {
            viewModel.setReadingMode(ReadingMode.DEFAULT)
            viewModel.setOrientation(ReaderOrientation.DEFAULT)
        },
        onConfirmDelete = viewModel::confirmDeleteChapters,
        onOpenTrackingSettings = {
            viewModel.dismissDialog()
            navigator.open(Route.Settings(SettingsSection.TRACKING))
        },
        onOpenInBrowser = navigator::openUrl,
        onSaveDetails = viewModel::saveDetails,
        onResetDetails = viewModel::resetDetails,
        onSaveNotes = viewModel::saveNotes,
        onSetUpdateInterval = viewModel::setUpdateInterval,
        onShareCover = viewModel::shareCover,
        onSaveCover = viewModel::saveCover,
        onSetCustomCover = viewModel::setCustomCover,
        onDeleteCustomCover = viewModel::deleteCustomCover,
    )
}

/** The app bar's download button and upstream's menu of what to queue (phase 3 unit AD). */
@Composable
private fun MangaDownloadMenu(onDownload: (DownloadAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.action_download))
        }
        DownloadDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, onDownload = onDownload)
    }
}

/**
 * The app bar overflow. Refresh repeats what the pull gesture does, because the gesture was the
 * only way to reach a chapter fetch and a gesture is unreachable both to a script and to an owner
 * who does not know it is there. Reading mode opens this entry's reader overrides, which are
 * per entry and so have nowhere else to live. Edit details (phase 5 unit ED) opens the owner's
 * title and description for the entry, and Notes the owner's notes on it. The WebView item appears
 * only for a source with a web presence. Migrate, after Reading mode as in upstream, is null and
 * absent while the entry is not in the library. Merge with... is null and absent while Merged
 * entries is off in Experimental.
 */
@Composable
private fun MangaOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showWebView: Boolean,
    onRefresh: () -> Unit,
    onReadingMode: () -> Unit,
    onEditDetails: () -> Unit,
    onNotes: () -> Unit,
    onMigrate: (() -> Unit)?,
    onMergeWith: (() -> Unit)?,
    onOpenInWebView: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }, modifier = Modifier.testTag(MANGA_OVERFLOW_TAG)) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_menu_overflow_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_refresh)) },
                onClick = {
                    onExpandedChange(false)
                    onRefresh()
                },
                modifier = Modifier.testTag(MANGA_REFRESH_TAG),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pref_category_reading_mode)) },
                onClick = {
                    onExpandedChange(false)
                    onReadingMode()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manga_edit_details)) },
                onClick = {
                    onExpandedChange(false)
                    onEditDetails()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_notes)) },
                onClick = {
                    onExpandedChange(false)
                    onNotes()
                },
            )
            if (onMigrate != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.migrate)) },
                    onClick = {
                        onExpandedChange(false)
                        onMigrate()
                    },
                )
            }
            if (onMergeWith != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_merge_with)) },
                    onClick = {
                        onExpandedChange(false)
                        onMergeWith()
                    },
                )
            }
            if (showWebView) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open_in_web_view)) },
                    onClick = {
                        onExpandedChange(false)
                        onOpenInWebView()
                    },
                )
            }
        }
    }
}

/**
 * Every modal this screen can raise, off the one `dialog` field, so one is open at a time.
 *
 * It takes the whole state rather than just the dialog case because the three chapter surfaces are
 * projections of the entry itself: the settings sheet reads the flag bits off `manga`, the
 * scanlator dialog reads both scanlator sets and the ranking, and the reading mode sheet reads the
 * per-entry overrides beside the two global defaults. None of that fits in the dialog case, and
 * putting it there would be a second copy of state the projection already carries.
 *
 * The category rows are remembered against the dialog instance because [ChangeCategoryDialog] keys
 * its own working copy on the list it was given; rebuilding that list on every recomposition would
 * throw away every box the owner had ticked. Both rows of a single entry are either on or off,
 * never partly applied, so [categoryChecks] is handed a total of one.
 */
@Composable
private fun MangaDialogs(
    state: MangaDetailViewModel.State.Success?,
    onDismissRequest: () -> Unit,
    onAddAnyway: () -> Unit,
    onConfirmCategories: (List<Long>) -> Unit,
    onOpenDuplicate: (Long) -> Unit,
    onMigrateDuplicate: (Long) -> Unit,
    onMergeDuplicate: (Long) -> Unit,
    onConfirmRemoveMerged: () -> Unit,
    onReorderSources: (List<Long>) -> Unit,
    onUseAsMain: (Long) -> Unit,
    onSourceCheckUpdates: (Long, Boolean) -> Unit,
    onRemoveSource: (Long) -> Unit,
    onShowEveryCopy: (Boolean) -> Unit,
    onUnmerge: () -> Unit,
    onMigrationFailed: (Throwable) -> Unit,
    onFilter: (MangaDetailViewModel.ChapterFilter, TriState) -> Unit,
    onSort: (Long) -> Unit,
    onDisplay: (Long) -> Unit,
    onGroupByVolume: (Boolean) -> Unit,
    onSetAsDefault: (applyToLibrary: Boolean) -> Unit,
    onResetToDefault: () -> Unit,
    onOpenScanlatorFilter: () -> Unit,
    onConfirmScanlators: (Set<String>, MangaDetailViewModel.RankingChange) -> Unit,
    onApplyReaderModes: (ReadingMode, ReaderOrientation) -> Unit,
    onUseReaderDefaults: () -> Unit,
    onConfirmDelete: () -> Unit,
    onOpenTrackingSettings: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onSaveDetails: (SetMangaDetailsOverride.Edit) -> Unit,
    onResetDetails: () -> Unit,
    onSaveNotes: (String) -> Unit,
    onSetUpdateInterval: (Int) -> Unit,
    onShareCover: () -> Unit,
    onSaveCover: () -> Unit,
    onSetCustomCover: (Uri) -> Unit,
    onDeleteCustomCover: () -> Unit,
) {
    if (state == null) return
    when (val dialog = state.dialog) {
        null -> Unit
        is MangaDetailViewModel.Dialog.DuplicateManga -> DuplicateMangaDialog(
            duplicates = dialog.duplicates,
            onDismissRequest = onDismissRequest,
            onAddAnyway = onAddAnyway,
            onOpenExisting = onOpenDuplicate,
            onMigrate = onMigrateDuplicate,
            onMerge = onMergeDuplicate.takeIf { state.mergedEntries },
        )
        is MangaDetailViewModel.Dialog.Migrate -> MigrateMangaDialog(
            current = dialog.current,
            target = dialog.target,
            onClickTitle = { onOpenDuplicate(dialog.current.id) },
            onDismissRequest = onDismissRequest,
            onFailed = onMigrationFailed,
        )
        is MangaDetailViewModel.Dialog.ChangeCategory -> {
            val rows = remember(dialog) {
                categoryChecks(dialog.categories, dialog.selected.associateWith { 1 }, total = 1)
            }
            ChangeCategoryDialog(
                rows = rows,
                onDismissRequest = onDismissRequest,
                onConfirm = { chosen ->
                    onConfirmCategories(chosen.filter { it.state == ToggleableState.On }.map { it.id })
                },
            )
        }
        MangaDetailViewModel.Dialog.ChapterSettings -> ChapterSettingsSheet(
            state = state,
            onDismissRequest = onDismissRequest,
            onFilter = onFilter,
            onSort = onSort,
            onDisplay = onDisplay,
            onGroupByVolume = onGroupByVolume,
            onSetAsDefault = onSetAsDefault,
            onResetToDefault = onResetToDefault,
            onOpenScanlatorFilter = onOpenScanlatorFilter,
        )
        MangaDetailViewModel.Dialog.ScanlatorFilter -> ScanlatorFilterDialog(
            available = state.availableScanlators,
            excluded = state.excludedScanlators,
            ranking = state.scanlatorRanking,
            onDismissRequest = onDismissRequest,
            onConfirm = onConfirmScanlators,
        )
        MangaDetailViewModel.Dialog.ReadingMode -> MangaReadingModeSheet(
            readingMode = state.readingMode,
            orientation = state.orientation,
            defaultReadingMode = state.defaultReadingMode,
            defaultOrientation = state.defaultOrientation,
            onDismissRequest = onDismissRequest,
            onApply = onApplyReaderModes,
            onUseDefault = onUseReaderDefaults,
        )
        is MangaDetailViewModel.Dialog.DeleteChapters -> AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.chapter_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.chapter_delete_message_format,
                        dialog.chapterIds.size,
                        dialog.chapterIds.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        is MangaDetailViewModel.Dialog.RemoveMerged -> AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.library_remove_selected_title)) },
            text = { Text(stringResource(R.string.merged_remove_message_format, dialog.sources)) },
            confirmButton = {
                TextButton(onClick = onConfirmRemoveMerged) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        MangaDetailViewModel.Dialog.MergedSources -> state.merged?.let { merged ->
            MergedSourcesSheet(
                title = merged,
                onDismissRequest = onDismissRequest,
                onReorder = onReorderSources,
                onUseAsMain = onUseAsMain,
                onCheckUpdates = onSourceCheckUpdates,
                onRemove = onRemoveSource,
                onShowEveryCopy = onShowEveryCopy,
                onUnmerge = onUnmerge,
            )
        }
        MangaDetailViewModel.Dialog.TrackSheet -> TrackSheet(
            mangaId = state.manga.id,
            mangaTitle = state.manga.title,
            onDismissRequest = onDismissRequest,
            onOpenTrackingSettings = onOpenTrackingSettings,
            onOpenInBrowser = onOpenInBrowser,
        )
        MangaDetailViewModel.Dialog.EditDetails -> EditDetailsDialog(
            manga = state.manga,
            onDismissRequest = onDismissRequest,
            onSave = onSaveDetails,
            onReset = onResetDetails,
        )
        MangaDetailViewModel.Dialog.Notes -> MangaNotesDialog(
            notes = state.manga.notes,
            onDismissRequest = onDismissRequest,
            onSave = onSaveNotes,
        )
        MangaDetailViewModel.Dialog.UpdateInterval -> UpdateIntervalDialog(
            cadence = state.updateCadence,
            chosenDays = state.manga.fetchInterval.takeIf { it < 0 }?.let { -it },
            offerChoice = state.offersIntervalChoice,
            relativeTime = state.relativeTime,
            dateFormat = state.dateFormat,
            onChoose = onSetUpdateInterval,
            onDismissRequest = onDismissRequest,
        )
        is MangaDetailViewModel.Dialog.Cover -> {
            // Upstream's picker: any image the system offers, handed back as a content URI.
            val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) onSetCustomCover(uri)
            }
            MangaCoverDialog(
                manga = state.manga,
                editable = dialog.editable,
                customCover = dialog.customCover,
                onDismissRequest = onDismissRequest,
                onShare = onShareCover,
                onSave = onSaveCover,
                onChooseImage = { pickImage.launch(COVER_IMAGE_TYPE) },
                onDeleteCustomCover = onDeleteCustomCover,
            )
        }
    }
}

/** What the cover picker offers: any image. */
private const val COVER_IMAGE_TYPE = "image/*"

@Composable
private fun ChapterList(
    state: MangaDetailViewModel.State.Success,
    padding: PaddingValues,
    listState: LazyListState,
    viewModel: MangaDetailViewModel,
    header: (@Composable () -> Unit)?,
) {
    // Upstream's chapter row answers a long press with this feedback before it selects.
    val haptic = LocalHapticFeedback.current
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), state = listState) {
        if (header != null) item { header() }
        if (state.chapters.isNotEmpty()) {
            // Upstream's `ChapterHeader`: the count of the chapters the list shows, in its style.
            item(key = CHAPTER_COUNT_KEY, contentType = CHAPTER_COUNT_KEY) {
                val count = state.chapters.size
                Text(
                    pluralStringResource(R.plurals.manga_num_chapters, count, count),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (state.chapters.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.manga_no_chapters),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Volume headers (phase 4 unit VG) while the entry's toggle is on and its sort keeps volumes
        // together; otherwise one item per chapter, as before.
        val rows = chapterListUiModels(state.chapters, state.manga.groupByVolume, state.manga.sorting)
        items(
            items = rows,
            key = { it.key },
            contentType = {
                when (it) {
                    is ChapterListUiModel.Header -> "header"
                    is ChapterListUiModel.Item -> "item"
                }
            },
        ) { row ->
            when (row) {
                is ChapterListUiModel.Header -> ListGroupHeader(
                    text = stringResource(R.string.chapter_volume_header, chapterNumber(row.volume)),
                )
                is ChapterListUiModel.Item -> {
                    val chapter = row.chapter
                    ChapterRow(
                        chapter = chapter,
                        sourceName = state.sourceNameOf(chapter),
                        hiddenDownload = chapter.id in state.hiddenDownloads,
                        download = state.downloadOf(chapter.id).takeIf { state.showsDownloadControl },
                        displayMode = state.manga.displayMode,
                        relativeTime = state.relativeTime,
                        dateFormat = state.dateFormat,
                        selected = chapter.id in state.selection,
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.toggleSelection(chapter.id)
                            } else {
                                viewModel.openChapter(chapter)
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.longPressChapter(chapter.id)
                        },
                        onDownloadAction = { action -> viewModel.runChapterDownloadAction(chapter, action) },
                        swipeStart = state.swipeOf(viewModel.chapterSwipeStartAction, chapter),
                        swipeEnd = state.swipeOf(viewModel.chapterSwipeEndAction, chapter),
                        onSwipeStart = { viewModel.swipeChapter(chapter, viewModel.chapterSwipeStartAction) },
                        onSwipeEnd = { viewModel.swipeChapter(chapter, viewModel.chapterSwipeEndAction) },
                    )
                }
            }
        }
    }
}

/**
 * The row's swipes, upstream's `MangaChapterListItem` (phase 4 unit CS): the start actions are the
 * swipe to the right, the end actions the swipe to the left, each drawn as upstream draws it, and a
 * side with no action ([swipeStart] or [swipeEnd] null) does not move, so in selection mode, where
 * [MangaDetailViewModel.State.Success.swipeOf] gives null, the row does not swipe at all. The swipe
 * is the row's one gesture for read and bookmark; the row carries no buttons for them (plan 2.6).
 */
@Composable
private fun ChapterSwipeBox(
    swipeStart: MangaDetailViewModel.ChapterSwipe?,
    swipeEnd: MangaDetailViewModel.ChapterSwipe?,
    onSwipeStart: () -> Unit,
    onSwipeEnd: () -> Unit,
    content: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.primaryContainer
    SwipeableActionsBox(
        modifier = Modifier.clipToBounds(),
        startActions = listOfNotNull(swipeStart?.let { chapterSwipeAction(it, background, onSwipeStart) }),
        endActions = listOfNotNull(swipeEnd?.let { chapterSwipeAction(it, background, onSwipeEnd) }),
        swipeThreshold = CHAPTER_SWIPE_THRESHOLD,
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        content()
    }
}

/** Upstream's `swipeAction`, with the icon its `getSwipeAction` picks for each effect. */
private fun chapterSwipeAction(
    swipe: MangaDetailViewModel.ChapterSwipe,
    background: Color,
    onSwipe: () -> Unit,
): SwipeAction {
    val icon = when (swipe) {
        MangaDetailViewModel.ChapterSwipe.MarkRead -> Icons.Outlined.Done
        MangaDetailViewModel.ChapterSwipe.MarkUnread -> Icons.Outlined.RemoveDone
        MangaDetailViewModel.ChapterSwipe.Bookmark -> Icons.Outlined.BookmarkAdd
        MangaDetailViewModel.ChapterSwipe.RemoveBookmark -> Icons.Outlined.BookmarkRemove
        MangaDetailViewModel.ChapterSwipe.StartDownload -> Icons.Outlined.Download
        MangaDetailViewModel.ChapterSwipe.CancelDownload -> Icons.Outlined.FileDownloadOff
        MangaDetailViewModel.ChapterSwipe.DeleteDownload -> Icons.Outlined.Delete
    }
    return SwipeAction(
        icon = {
            Icon(
                modifier = Modifier.padding(16.dp),
                imageVector = icon,
                tint = contentColorFor(background),
                contentDescription = null,
            )
        },
        background = background,
        onSwipe = onSwipe,
        isUndo = swipe == MangaDetailViewModel.ChapterSwipe.MarkUnread ||
            swipe == MangaDetailViewModel.ChapterSwipe.RemoveBookmark,
    )
}

/**
 * One chapter, plan 2.6's row (unit ID3): upstream's `MangaChapterListItem` for what it shows and
 * does, and for its colours and alphas. The title line carries the unread dot and the bookmark before a one-line
 * title; the line under it is the upload date, "Page: N" while partly read, and the scanlator; a
 * read row is faded. The download control sits at the end, and [download] is null where the row
 * draws none (a Local entry, or chapters selected). In a merged title [sourceName] opens the line
 * under the title, and [hiddenDownload] badges a row whose hidden copy is on disk. A tap opens the
 * chapter or toggles it in a selection, a long press selects, and the swipes mark read and
 * bookmark: the row has no buttons.
 * It is [ListRowBody] with no divider, on upstream's padding (16 dp start, 12 dp top and bottom, 8 dp
 * end), its 6 dp between the two lines, its slots at the top, and its 4 dp before the control.
 */
@Composable
private fun ChapterRow(
    chapter: Chapter,
    sourceName: String?,
    hiddenDownload: Boolean,
    download: ChapterDownload?,
    displayMode: Long,
    relativeTime: Boolean,
    dateFormat: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadAction: (ChapterDownloadAction) -> Unit,
    swipeStart: MangaDetailViewModel.ChapterSwipe?,
    swipeEnd: MangaDetailViewModel.ChapterSwipe?,
    onSwipeStart: () -> Unit,
    onSwipeEnd: () -> Unit,
) = ChapterSwipeBox(swipeStart, swipeEnd, onSwipeStart, onSwipeEnd) {
    ListRowBody(
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected,
        trailing = {
            if (download != null) {
                ChapterDownloadControl(download, onDownloadAction, modifier = Modifier.padding(start = 4.dp))
            }
        },
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        contentSpacing = 6.dp,
        verticalAlignment = Alignment.Top,
    ) {
        // A chapter whose number could not be recognised keeps its name whatever the display mode
        // says, because "Chapter -1" is worse than the row the source shipped.
        val title = if (displayMode == Manga.CHAPTER_DISPLAY_NUMBER && chapter.isRecognizedNumber) {
            stringResource(R.string.chapter_number_format, chapterNumber(chapter.chapterNumber))
        } else {
            chapter.name
        }
        ChapterMarkedText(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            read = chapter.read,
            bookmark = chapter.bookmark,
        )
        val detail = listOfNotNull(
            sourceName,
            chapterUploadDate(chapter.dateUpload, relativeTime, dateFormat),
            chapterProgress(chapter.read, chapter.lastPageRead),
            chapter.scanlator?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hiddenDownload) {
                Icon(
                    Icons.Outlined.DownloadDone,
                    contentDescription = stringResource(R.string.merged_hidden_copy_downloaded),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            ChapterDetailText(detail, read = chapter.read)
        }
    }
}

/**
 * The entry's header: the cover, the title, the author, the status and the source, then add to
 * library and tracking. The description folds to three lines with an expand control while it runs
 * longer, and every genre follows as a chip. A tap on a genre searches the library for it
 * (`genre:"X"`), a tap on the title or the author searches every source for it, as upstream's
 * header does, and a long press on a genre, the title or the author copies it.
 *
 * The title and the description are the owner's edit over the source's (phase 5 unit ED), whose
 * dialog the overflow's Edit details opens; a tap on the cover opens upstream's cover viewer. The
 * owner's notes, when there are any, show their first lines under the description, and a tap there
 * opens them as the overflow's Notes does.
 */
@Composable
private fun MangaHeader(
    state: MangaDetailViewModel.State.Success,
    onToggleFavorite: () -> Unit,
    onTracking: () -> Unit,
    onUpdateInterval: () -> Unit,
    onToggleDescription: () -> Unit,
    onCoverClick: () -> Unit,
    onNotesClick: () -> Unit,
    onMergedSources: () -> Unit,
    onGenreSearch: (String) -> Unit,
    onGlobalSearch: (String) -> Unit,
) {
    val manga = state.manga
    val context = LocalContext.current
    val copied = stringResource(R.string.copied_to_clipboard)
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MangaHeaderTop(
            state = state,
            onCoverClick = onCoverClick,
            onMergedSources = onMergedSources,
            onGlobalSearch = onGlobalSearch,
        )
        // Flowing, so a narrow pane (the tablet library's) puts the tracking button on its own line
        // rather than breaking its label.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onToggleFavorite) {
                Text(
                    stringResource(
                        if (manga.favorite) R.string.action_remove_from_library else R.string.action_add_to_library,
                    ),
                )
            }
            if (state.tracking.offered) TrackingButton(count = state.tracking.count, onClick = onTracking)
            if (manga.favorite) UpdateIntervalButton(cadence = state.updateCadence, onClick = onUpdateInterval)
        }
        MangaDescription(
            description = manga.displayDescription?.takeIf { it.isNotBlank() },
            expanded = state.descriptionExpanded,
            onToggle = onToggleDescription,
        )
        manga.notes.takeIf { it.isNotBlank() }?.let { notes -> MangaNotesSnippet(notes, onNotesClick) }
        val genres = shownGenres(manga)
        if (genres.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                genres.forEach { genre ->
                    GenreChip(
                        genre = genre,
                        onClick = { onGenreSearch(genre) },
                        onLongClick = { context.copyToClipboard(genre, copied) },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * The cover and the lines beside it: side by side while the lines keep [HEADER_TEXT_MIN_WIDTH]
 * next to the cover, else upstream's tablet header, the cover centred above them at 65% of the
 * width. The width is the space the header was handed, not the window's: two panes forced on a
 * phone give it half the screen, about 200 dp, where a 200 dp cover left the title one character
 * wide. A tablet's pane and the library's entry pane keep the row.
 */
@Composable
private fun MangaHeaderTop(
    state: MangaDetailViewModel.State.Success,
    onCoverClick: () -> Unit,
    onMergedSources: () -> Unit,
    onGlobalSearch: (String) -> Unit,
) {
    val coverWidth = if (isTabletUi()) 200.dp else 100.dp
    val cover = @Composable { modifier: Modifier ->
        MangaCover(
            data = state.manga,
            modifier = modifier
                .testTag(MANGA_COVER_TAG)
                .clickable(onClick = onCoverClick),
            contentDescription = state.manga.displayTitle,
        )
    }
    BoxWithConstraints {
        val width = maxWidth
        if (width >= coverWidth + HEADER_COVER_GAP + HEADER_TEXT_MIN_WIDTH) {
            Row(verticalAlignment = Alignment.Top) {
                cover(Modifier.width(coverWidth))
                Spacer(Modifier.width(HEADER_COVER_GAP))
                MangaHeaderLines(state, onMergedSources, onGlobalSearch, Alignment.Start, TextAlign.Start)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                cover(Modifier.width(min(width * STACKED_COVER_FRACTION, coverWidth)))
                Spacer(Modifier.height(HEADER_COVER_GAP))
                MangaHeaderLines(state, onMergedSources, onGlobalSearch, Alignment.CenterHorizontally, TextAlign.Center)
            }
        }
    }
}

/**
 * The title, the author, the artist where it differs from the author, the status and the source,
 * aligned as [MangaHeaderTop] lays them out; each detail the owner edited shows the edit (R165 item
 * 4). A merged title's source line names the main entry's source and how many more, and opens the
 * Merged sources sheet.
 */
@Composable
private fun MangaHeaderLines(
    state: MangaDetailViewModel.State.Success,
    onMergedSources: () -> Unit,
    onGlobalSearch: (String) -> Unit,
    alignment: Alignment.Horizontal,
    textAlign: TextAlign,
) {
    val manga = state.manga
    val context = LocalContext.current
    val copied = stringResource(R.string.copied_to_clipboard)
    val copyLabel = stringResource(R.string.action_copy_to_clipboard)
    val searchLabel = stringResource(R.string.action_global_search)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = alignment) {
        Text(
            manga.displayTitle,
            modifier = Modifier.searchOrCopy(
                searchLabel = searchLabel,
                onSearch = { onGlobalSearch(manga.displayTitle) },
                copyLabel = copyLabel,
                onCopy = { context.copyToClipboard(manga.displayTitle, copied) },
            ),
            style = MaterialTheme.typography.titleLarge,
            textAlign = textAlign,
        )
        val author = manga.displayAuthor?.takeIf { it.isNotBlank() }
        author?.let {
            Text(
                stringResource(R.string.manga_author_format, author),
                modifier = Modifier.searchOrCopy(
                    searchLabel = searchLabel,
                    onSearch = { onGlobalSearch(author) },
                    copyLabel = copyLabel,
                    onCopy = { context.copyToClipboard(author, copied) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = textAlign,
            )
        }
        manga.displayArtist?.takeIf { it.isNotBlank() && it != author }?.let { artist ->
            Text(
                stringResource(R.string.manga_artist_format, artist),
                modifier = Modifier.searchOrCopy(
                    searchLabel = searchLabel,
                    onSearch = { onGlobalSearch(artist) },
                    copyLabel = copyLabel,
                    onCopy = { context.copyToClipboard(artist, copied) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = textAlign,
            )
        }
        Text(statusLabel(manga.displayStatus), style = MaterialTheme.typography.bodySmall, textAlign = textAlign)
        val merged = state.merged
        if (merged != null) {
            Text(
                stringResource(R.string.merged_source_line_format, state.sourceName.orEmpty(), merged.sources.size - 1),
                modifier = Modifier.clickable(onClick = onMergedSources),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = textAlign,
            )
        } else {
            Text(
                if (state.sourceInstalled && state.sourceName != null) {
                    stringResource(R.string.manga_source_format, state.sourceName)
                } else {
                    stringResource(R.string.manga_source_not_installed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = textAlign,
            )
        }
    }
}

/** The narrowest the header's lines may be beside the cover before the cover moves above them. */
private val HEADER_TEXT_MIN_WIDTH = 160.dp

private val HEADER_COVER_GAP = 16.dp

/** Upstream's tablet header draws its cover at this share of the width. */
private const val STACKED_COVER_FRACTION = 0.65f

/**
 * The description, upstream's expandable summary: three lines while folded, the
 * whole text open, and an expand control ("More", "Less", upstream's words) under it while the folded
 * text runs past its three lines or the text is open. A tap on the text toggles it too, as before.
 */
@Composable
private fun MangaDescription(description: String?, expanded: Boolean, onToggle: () -> Unit) {
    var overflows by remember { mutableStateOf(false) }
    Column {
        Text(
            description ?: stringResource(R.string.manga_no_description),
            modifier = Modifier.clickable(onClick = onToggle),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            IconButton(onClick = onToggle, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.manga_info_collapse else R.string.manga_info_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * Upstream's header text: a tap searches, a long press copies. No ripple, as upstream's has none;
 * [searchLabel] and [copyLabel] are what a screen reader offers for the two.
 */
private fun Modifier.searchOrCopy(
    searchLabel: String,
    onSearch: () -> Unit,
    copyLabel: String,
    onCopy: () -> Unit,
): Modifier = combinedClickable(
    interactionSource = null,
    indication = null,
    onClickLabel = searchLabel,
    onClick = onSearch,
    onLongClickLabel = copyLabel,
    onLongClick = onCopy,
)

/**
 * One genre, drawn as Material's assist chip is (a small outlined shape, the label in labelLarge),
 * on the long-press surface, because Material's chips take a tap only.
 */
@Composable
private fun GenreChip(genre: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    LongPressSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.height(GENRE_CHIP_HEIGHT).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(genre, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The first lines of the owner's notes, under a label so they never read as the source's text. */
@Composable
private fun MangaNotesSnippet(notes: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.action_notes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = NOTES_SNIPPET_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Upstream's tracking button (phase 3 unit T3): "Tracking" with the sync icon while no signed-in
 * tracker has a row for the entry, else the number of those rows with a tick, in the primary colour.
 */
@Composable
private fun TrackingButton(count: Int, onClick: () -> Unit) {
    val tracked = count > 0
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.testTag(MANGA_TRACKING_TAG),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (tracked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = if (tracked) Icons.Filled.Done else Icons.Outlined.Sync,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(
            if (tracked) {
                pluralStringResource(R.plurals.num_trackers, count, count)
            } else {
                stringResource(R.string.manga_tracking)
            },
        )
    }
}

/**
 * The genres the header lists and the edit dialog starts from: the owner's edit over the source's,
 * trimmed, without blanks and repeats.
 */
internal fun shownGenres(manga: Manga): List<String> =
    manga.displayGenre.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** The name of the `MangaStatus` code [status], as the header and the edit dialog show it. */
@Composable
internal fun statusLabel(status: Long): String = stringResource(
    when (MangaStatus.fromCode(status.toInt())) {
        MangaStatus.ONGOING -> R.string.manga_status_ongoing
        MangaStatus.COMPLETED -> R.string.manga_status_completed
        MangaStatus.LICENSED -> R.string.manga_status_licensed
        MangaStatus.PUBLISHING_FINISHED -> R.string.manga_status_publishing_finished
        MangaStatus.CANCELLED -> R.string.manga_status_cancelled
        MangaStatus.ON_HIATUS -> R.string.manga_status_on_hiatus
        MangaStatus.UNKNOWN -> R.string.manga_status_unknown
    },
)

/** Chapter 12, not Chapter 12.0, while a half chapter keeps its 12.5. Volume headers read the same way. */
@Composable
private fun chapterNumber(number: Double): String =
    remember(number) { DecimalFormat("#.###").format(number) }

/** Material's assist chip height. */
private val GENRE_CHIP_HEIGHT = 32.dp

/** Upstream's folded description. */
private const val DESCRIPTION_LINES = 3

/** The notes under the description, shorter than the description so they stay a reminder. */
private const val NOTES_SNIPPET_LINES = 2

/** The chapter count's list item; no chapter or volume key takes this form. */
private const val CHAPTER_COUNT_KEY = "chapter_count"

/** Upstream's `swipeActionThreshold`. */
private val CHAPTER_SWIPE_THRESHOLD = 56.dp
