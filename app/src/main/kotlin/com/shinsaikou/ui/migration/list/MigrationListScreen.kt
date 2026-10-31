package com.shinsaikou.ui.migration.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.migration.leftBehindText
import com.shinsaikou.ui.migration.list.MigrationListViewModel.Dialog
import com.shinsaikou.ui.migration.list.MigrationListViewModel.Event
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/**
 * Upstream's migration list (phase 4 unit MB): each entry with its match, the bar's Copy and Migrate,
 * a row's menu, and the confirm, progress and exit dialogs.
 *
 * Saikou: no back arrow (system back asks "Stop migrating?", as in upstream); the bar's actions wait for
 * any write in flight (M7); a row being written shows a small spinner in place of its menu; a match
 * already in the library carries the browse listing's mark (M5); the screen stays on while the list
 * works (M9); snackbars count the entries that failed or were held (M3, M6), and the downloaded
 * chapters a Migrate could not move, with Delete them (unit migrate-downloads); and the outcomes are
 * saved with the screen, so a list restored after a process death rebuilds them (M1).
 *
 * [onSearchManually] opens manual search for one entry. [pick] is a target picked there by hand,
 * `[entryId, targetId]`, from this back stack entry's saved state: it is handed to the view model
 * once the list is built, and [onPickConsumed] removes it.
 */
@Composable
fun MigrationListScreen(
    mangaIds: List<Long>,
    extraSearchQuery: String?,
    onSearchManually: (Long) -> Unit,
    pick: LongArray?,
    onPickConsumed: () -> Unit,
) {
    // M1: what the last view model of this back stack entry had resolved, handed to the new one
    // after a process death. The view model is built once per entry, so only a restore reads it.
    var saved by rememberSaveable(stateSaver = RestoredOutcomes.Saver) { mutableStateOf(null) }
    val viewModel: MigrationListViewModel =
        assistedMetroViewModel<MigrationListViewModel, MigrationListViewModel.Factory> {
            create(mangaIds, extraSearchQuery, saved)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Nothing is saved until the list is built, so a restore in progress keeps what it restores.
    LaunchedEffect(state.items.isNotEmpty(), state.outcomes, state.removedIds, state.runFlags) {
        if (state.items.isNotEmpty()) saved = state.toRestoredOutcomes()
    }

    // A pick that arrives before a restored list is built waits for it, so it is not dropped.
    val built = state.items.isNotEmpty()
    LaunchedEffect(pick, built) {
        if (pick != null && built) {
            viewModel.useMangaForMigration(current = pick[0], target = pick[1])
            onPickConsumed()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                Event.Close -> navigator.back()
                // Upstream toasts this; the list is still on screen, so it is the list's snackbar, as
                // long as the toast was.
                Event.MatchWithoutChapters -> scope.launch {
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.migrationListScreen_matchWithoutChapterToast),
                        duration = SnackbarDuration.Long,
                    )
                }
                is Event.Failed -> scope.launch {
                    snackbarHostState.showSnackbar(
                        resources.getQuantityString(R.plurals.migration_failed_count, event.count, event.count),
                    )
                }
                is Event.Held -> scope.launch {
                    snackbarHostState.showSnackbar(
                        resources.getQuantityString(R.plurals.migration_held_count, event.count, event.count),
                    )
                }
                // The list waits for this answer before it closes, so the snackbar is seen.
                is Event.DownloadsLeftBehind -> scope.launch {
                    val count = event.chapterIds.size
                    val result = snackbarHostState.showSnackbar(
                        message = leftBehindText(resources, count, carried = event.carried, batch = true),
                        actionLabel = resources.getString(R.string.migration_downloads_delete),
                        duration = SnackbarDuration.Long,
                    )
                    viewModel.resolveLeftBehind(event.chapterIds, delete = result == SnackbarResult.ActionPerformed)
                }
            }
        }
    }

    // M9: the reader sets the same window flag from its own setting.
    val view = LocalView.current
    DisposableEffect(view, state.keepAwake) {
        view.keepScreenOn = state.keepAwake
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(true) {
        viewModel.showExitDialog()
    }

    val items = state.items
    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = if (items.isNotEmpty()) {
                    stringResource(R.string.migrationListScreenTitleWithProgress, state.finishedCount, items.size)
                } else {
                    stringResource(R.string.migrationListScreenTitle)
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { viewModel.showMigrateDialog(copy = true) }, enabled = state.canMigrate) {
                        Icon(
                            imageVector = if (items.size == 1) Icons.Outlined.ContentCopy else Icons.Outlined.CopyAll,
                            contentDescription = stringResource(R.string.copy),
                        )
                    }
                    IconButton(onClick = { viewModel.showMigrateDialog(copy = false) }, enabled = state.canMigrate) {
                        Icon(
                            imageVector = if (items.size == 1) Icons.Outlined.Done else Icons.Outlined.DoneAll,
                            contentDescription = stringResource(R.string.migrate),
                        )
                    }
                },
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        FastScrollLazyColumn(contentPadding = contentPadding + topSmallPaddingValues) {
            items(items, key = { it.manga.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .padding(
                            start = MaterialTheme.padding.medium,
                            end = MaterialTheme.padding.small,
                        )
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MigrationListItem(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.Top)
                            .fillMaxHeight(),
                        manga = item.manga,
                        source = item.source,
                        chapterCount = item.chapterCount,
                        latestChapter = item.latestChapter,
                        inLibrary = false,
                        onClick = { navigator.open(Route.MangaDetail(item.manga.id, fromSource = true)) },
                    )

                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.weight(0.2f),
                    )

                    val result by item.searchResult.collectAsState()
                    MigrationListItemResult(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.Top)
                            .fillMaxHeight(),
                        result = result,
                        favoriteIds = state.favoriteIds,
                        onItemClick = { navigator.open(Route.MangaDetail(it.id, fromSource = true)) },
                    )

                    MigrationListItemAction(
                        modifier = Modifier.weight(0.2f),
                        result = result,
                        writing = item.manga.id in state.inFlight,
                        onSearchManually = { onSearchManually(item.manga.id) },
                        onSkip = { viewModel.removeManga(item.manga.id) },
                        onMigrate = { viewModel.migrateNow(item.manga.id, replace = true) },
                        onCopy = { viewModel.migrateNow(item.manga.id, replace = false) },
                    )
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        is Dialog.Migrate -> MigrationMangaDialog(
            onDismissRequest = viewModel::dismissDialog,
            copy = dialog.copy,
            totalCount = dialog.totalCount,
            skippedCount = dialog.skippedCount,
            onMigrate = {
                if (dialog.copy) viewModel.copyMangas() else viewModel.migrateMangas()
            },
        )
        is Dialog.Progress -> MigrationProgressDialog(
            progress = dialog.progress,
            exitMigration = viewModel::cancelMigrate,
        )
        Dialog.Exit -> MigrationExitDialog(
            onDismissRequest = viewModel::dismissDialog,
            exitMigration = navigator::back,
        )
        null -> Unit
    }
}
