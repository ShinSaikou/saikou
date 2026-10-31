// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/more/settings/screen/advanced/ClearDatabaseScreen.kt (modified: yes) a composable destination on Route.ClearDatabase over the shared ChecklistScreen
package com.shinsaikou.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Upstream's clear database: every source with entries outside the library, each with its count, and
 * a delete of the ticked sources' entries behind upstream's confirmation, whose keep read switch starts
 * on.
 *
 * Modified: a composable destination on `Route.ClearDatabase` instead of a Voyager screen, drawn
 * through [ChecklistScreen], with `metroViewModel()` and Saikou's string resources.
 */
@Composable
fun ClearDatabaseScreen() {
    val navigator = LocalNavigator.current
    val viewModel: ClearDatabaseViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    when (val s = state) {
        is ClearDatabaseViewModel.State.Loading -> LoadingScreen()
        is ClearDatabaseViewModel.State.Ready -> {
            if (s.confirming != null) {
                var keepReadManga by remember { mutableStateOf(true) }
                val completed = stringResource(R.string.clear_database_completed)
                AlertDialog(
                    title = {
                        Text(text = stringResource(R.string.are_you_sure))
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            Text(text = stringResource(R.string.clear_database_text))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.clear_db_exclude_read),
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = keepReadManga,
                                    onCheckedChange = { keepReadManga = it },
                                )
                            }
                            if (!keepReadManga) {
                                Text(
                                    text = stringResource(R.string.clear_database_history_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    onDismissRequest = viewModel::hideConfirmation,
                    confirmButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    viewModel.removeConfirmedManga(keepReadManga)
                                    viewModel.clearSelection()
                                    viewModel.hideConfirmation()
                                    // Upstream toasts this; the list stays, so it is its snackbar.
                                    snackbarHostState.showSnackbar(completed)
                                }
                            },
                        ) {
                            Text(text = stringResource(R.string.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::hideConfirmation) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    },
                )
            }

            ChecklistScreen(
                title = stringResource(R.string.pref_clear_database),
                items = s.items.map { source ->
                    ChecklistItem(
                        id = source.id,
                        title = source.name,
                        detail = stringResource(R.string.clear_database_source_item_count, source.count.toInt()),
                    )
                },
                selection = s.selection,
                emptyMessage = stringResource(R.string.database_clean),
                actionLabel = stringResource(R.string.action_delete),
                onBack = navigator::back,
                onToggle = viewModel::toggleSelection,
                onSelectAll = viewModel::selectAll,
                onInvertSelection = viewModel::invertSelection,
                onAction = viewModel::showConfirmation,
                snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
            )
        }
    }
}
