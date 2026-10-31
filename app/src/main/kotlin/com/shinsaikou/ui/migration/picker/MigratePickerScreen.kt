package com.shinsaikou.ui.migration.picker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.LabelledExtendedFloatingActionButton
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.shouldExpandFAB

/**
 * One source's library entries, to tick for a migration (phase 4 unit MB), upstream's
 * `MigrateMangaScreen`: the bar titled with the source's name, a row tap ticking the entry and a
 * cover tap opening it, back clearing a selection before it leaves, and Continue, shown while
 * anything is ticked, taking the ticked entries in tap order to the config screen. No Select all, as
 * in upstream; the library's search, Select all and Migrate move a whole source at once.
 *
 * The selection is saved with the screen and handed back to the view model after a process death.
 */
@Composable
fun MigratePickerScreen(sourceId: Long) {
    var saved by rememberSaveable { mutableStateOf(LongArray(0)) }
    val viewModel: MigratePickerViewModel =
        assistedMetroViewModel<MigratePickerViewModel, MigratePickerViewModel.Factory> {
            create(sourceId, saved.toList())
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.selection) { saved = state.selection.toLongArray() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // Upstream toasts this; the screen stays (empty), so it is the screen's snackbar. Sent
                // while loading, it waits for the host the empty screen brings.
                MigratePickerViewModel.Event.FailedFetchingFavorites -> scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.internal_error))
                }
            }
        }
    }

    if (state.isLoading) {
        LoadingScreen()
        return
    }

    BackHandler(enabled = state.selectionMode) {
        viewModel.clearSelection()
    }

    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = state.sourceName.orEmpty(),
                onBack = {
                    if (state.selectionMode) viewModel.clearSelection() else navigator.back()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = state.selectionMode) {
                LabelledExtendedFloatingActionButton(
                    text = stringResource(R.string.migrationConfigScreen_continueButtonText),
                    icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                    onClick = {
                        val selection = state.selection
                        viewModel.clearSelection()
                        navigator.open(Route.MigrationConfig(selection))
                    },
                    expanded = lazyListState.shouldExpandFAB(),
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        if (state.isEmpty) {
            EmptyScreen(
                stringRes = R.string.empty_screen,
                modifier = Modifier.padding(contentPadding),
            )
            return@Scaffold
        }

        FastScrollLazyColumn(
            state = lazyListState,
            contentPadding = contentPadding,
        ) {
            items(state.titles, key = { it.id }) { manga ->
                ListRow(
                    title = manga.displayTitle,
                    detail = "",
                    modifier = Modifier.selectedBackground(manga.id in state.selection),
                    onClick = { viewModel.toggleSelection(manga) },
                    leading = {
                        MangaCover(
                            manga,
                            modifier = Modifier
                                .width(48.dp)
                                .clickable { navigator.open(Route.MangaDetail(manga.id)) },
                            contentDescription = manga.displayTitle,
                        )
                    },
                )
            }
        }
    }
}
