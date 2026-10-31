package com.shinsaikou.ui.migration.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.browse.SourceIcon
import com.shinsaikou.ui.components.LabelledExtendedFloatingActionButton
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.migration.config.MigrationConfigViewModel.MigrationSource
import com.shinsaikou.ui.migration.config.MigrationConfigViewModel.SelectionConfig
import dev.zacsweers.metrox.viewmodel.metroViewModel
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.shouldExpandFAB

/**
 * Upstream's migration config screen (phase 4 unit MB): the target sources of a batch migration,
 * selected ones first in the order they are searched, dragged by their handles, and Continue.
 *
 * With one entry, Continue goes straight to picking its target by hand ([onSearchManually]), and
 * the migrate dialog asks for the flags; with more, it opens the sheet, whose Continue starts the
 * migration list ([onStartMigration], with the sheet's keywords). The source order is saved on every
 * change, so a process death loses nothing; Continue saves it once more, as upstream's does.
 */
@Composable
fun MigrationConfigScreen(
    mangaIds: List<Long>,
    onSearchManually: (Long) -> Unit,
    onStartMigration: (extraSearchQuery: String?) -> Unit,
) {
    val navigator = LocalNavigator.current
    val viewModel: MigrationConfigViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var migrationSheetOpen by rememberSaveable { mutableStateOf(false) }

    fun continueMigration(openSheet: Boolean, extraSearchQuery: String?) {
        val mangaId = mangaIds.singleOrNull()
        if (mangaId == null && openSheet) {
            migrationSheetOpen = true
            return
        }
        if (mangaId == null) onStartMigration(extraSearchQuery) else onSearchManually(mangaId)
    }

    if (state.isLoading) {
        LoadingScreen()
        return
    }

    val (selectedSources, availableSources) = state.sources.partition { it.isSelected }
    val showLanguage = remember(state.sources) { state.sources.distinctBy { it.lang }.size > 1 }

    val lazyListState = rememberLazyListState()
    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = "",
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { viewModel.toggleSelection(SelectionConfig.All) }) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                    }
                    IconButton(onClick = { viewModel.toggleSelection(SelectionConfig.None) }) {
                        Icon(
                            Icons.Outlined.Deselect,
                            contentDescription = stringResource(R.string.migrationConfigScreen_selectNoneLabel),
                        )
                    }
                    ConfigOverflow(
                        onSelectEnabled = { viewModel.toggleSelection(SelectionConfig.Enabled) },
                        onSelectPinned = { viewModel.toggleSelection(SelectionConfig.Pinned) },
                    )
                },
            )
        },
        floatingActionButton = {
            LabelledExtendedFloatingActionButton(
                text = stringResource(R.string.migrationConfigScreen_continueButtonText),
                icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                onClick = {
                    viewModel.saveSources()
                    continueMigration(openSheet = true, extraSearchQuery = null)
                },
                expanded = lazyListState.shouldExpandFAB(),
            )
        },
    ) { contentPadding ->
        val reorderableState = rememberReorderableLazyListState(lazyListState, contentPadding) { from, to ->
            viewModel.moveSource(from.key, to.key)
        }

        FastScrollLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = contentPadding,
        ) {
            listOf(selectedSources, availableSources).fastForEachIndexed { listIndex, sources ->
                val selectedSourceList = listIndex == 0
                if (sources.isNotEmpty()) {
                    val headerPrefix = if (selectedSourceList) "selected" else "available"
                    item("$headerPrefix-header") {
                        Text(
                            text = stringResource(
                                if (selectedSourceList) {
                                    R.string.migrationConfigScreen_selectedHeader
                                } else {
                                    R.string.migrationConfigScreen_availableHeader
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(MaterialTheme.padding.medium)
                                .animateItem(),
                        )
                    }
                }
                itemsIndexed(
                    items = sources,
                    key = { _, item -> item.id },
                ) { index, item ->
                    SourceItemContainer(
                        firstItem = index == 0,
                        lastItem = index == (sources.size - 1),
                        source = item,
                        showLanguage = showLanguage,
                        dragEnabled = selectedSourceList && sources.size > 1,
                        state = reorderableState,
                        key = { if (selectedSourceList) it.id else "available-${it.id}" },
                        onClick = { viewModel.toggleSelection(item.id) },
                    )
                }
            }
        }
    }

    if (migrationSheetOpen) {
        MigrationConfigSheet(
            preferences = viewModel.sourcePreferences,
            onDismissRequest = { migrationSheetOpen = false },
            onStartMigration = { extraSearchQuery ->
                migrationSheetOpen = false
                continueMigration(openSheet = false, extraSearchQuery = extraSearchQuery)
            },
        )
    }
}

@Composable
private fun ConfigOverflow(onSelectEnabled: () -> Unit, onSelectPinned: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.migrationConfigScreen_selectEnabledLabel)) },
                onClick = {
                    expanded = false
                    onSelectEnabled()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.migrationConfigScreen_selectPinnedLabel)) },
                onClick = {
                    expanded = false
                    onSelectPinned()
                },
            )
        }
    }
}

@Composable
private fun LazyItemScope.SourceItemContainer(
    firstItem: Boolean,
    lastItem: Boolean,
    source: MigrationSource,
    showLanguage: Boolean,
    dragEnabled: Boolean,
    state: ReorderableLazyListState,
    key: (MigrationSource) -> Any,
    onClick: () -> Unit,
) {
    val shape = remember(firstItem, lastItem) {
        val top = if (firstItem) 12.dp else 0.dp
        val bottom = if (lastItem) 12.dp else 0.dp
        RoundedCornerShape(top, top, bottom, bottom)
    }

    ReorderableItem(
        state = state,
        key = key(source),
        enabled = dragEnabled,
    ) { _ ->
        ElevatedCard(
            shape = shape,
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .animateItem(),
        ) {
            SourceItem(
                source = source,
                showLanguage = showLanguage,
                dragEnabled = dragEnabled,
                scope = this@ReorderableItem,
                onClick = onClick,
            )
        }
    }

    if (!lastItem) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
    }
}

/**
 * The source's own icon, as upstream draws it: its extension's launcher icon, with the generic mark for
 * a source whose extension is not installed.
 */
@Composable
private fun SourceItem(
    source: MigrationSource,
    showLanguage: Boolean,
    dragEnabled: Boolean,
    scope: ReorderableCollectionItemScope,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        trailingContent = if (dragEnabled) {
            {
                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = null,
                    modifier = with(scope) {
                        Modifier.draggableHandle()
                    },
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(source.id, 24.dp)
                Text(
                    text = source.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (showLanguage) {
                    Pill(
                        text = MigrationConfigViewModel.shortDisplayName(source.lang, uppercase = true),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}
