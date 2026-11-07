package com.shinsaikou.ui.browse.global

import android.content.res.Resources
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.MigrationPick
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SourceBrowseMode
import com.shinsaikou.ui.browse.source.InLibraryBadge
import com.shinsaikou.ui.browse.source.inLibraryMark
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SearchField
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.migration.MigrateMangaDialog
import com.shinsaikou.ui.util.ErrorText
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * One query, every enabled source, one section per source.
 *
 * Sections are laid out as horizontally scrolling rows rather than as a grid per source. A grid
 * would make the page's height depend on how many results the slowest source eventually returns,
 * so every late reply would shove the sections the owner was already reading off the screen.
 *
 * With [migratingMangaId] it is manual search for a migration (phase 4 unit MB, upstream's
 * `MigrateSearchScreen`): a tap on a cover picks that result as the entry's target, a long press
 * opens it, a group's "more" opens that source's listing in the same mode, and the entry's own
 * source is headed with "▶". A pick goes back to the migration list below; with none, MA's dialog
 * migrates the one entry and a failure is said in a snackbar.
 *
 * With [mergingMangaId] it is Merge with... (R163): a tap on a cover asks whether to merge that
 * result into the entry, a long press opens it, and a confirmed merge goes back to the entry. A group
 * has no "more", because a source's listing has no merge pick.
 */
@Composable
fun GlobalSearchScreen(initialQuery: String?, migratingMangaId: Long? = null, mergingMangaId: Long? = null) {
    val viewModel: GlobalSearchViewModel =
        assistedMetroViewModel<GlobalSearchViewModel, GlobalSearchViewModel.Factory> {
            create(initialQuery, migratingMangaId, mergingMangaId)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onManga: (Manga) -> Unit = if (mergingMangaId != null) {
        viewModel::showMergeDialog
    } else if (migratingMangaId == null) {
        { manga -> navigator.open(Route.MangaDetail(manga.id, fromSource = true)) }
    } else {
        { manga ->
            when (navigator.pickMigrationTarget(migratingMangaId, manga.id)) {
                MigrationPick.RETURNED -> Unit
                // The search stays on screen, so it says so itself rather than in a toast.
                MigrationPick.IN_BATCH -> scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.migration_pick_in_batch))
                }
                MigrationPick.NO_LIST -> viewModel.showMigrateDialog(manga)
            }
        }
    }
    val onMangaLongClick: ((Manga) -> Unit)? = if (migratingMangaId == null && mergingMangaId == null) {
        null
    } else {
        { manga -> navigator.open(Route.MangaDetail(manga.id, fromSource = true)) }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.global_search_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        // The field sits above the list, as History's does, so a search that found nothing can be
        // an empty screen under it rather than an item inside a list with nothing else in it.
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            GlobalSearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                onSubmit = viewModel::submitSearch,
            )
            GlobalSearchChips(
                // A migration searches its own target sources, so it has no Pinned / All, as upstream.
                showSourceFilter = migratingMangaId == null,
                pinnedOnly = state.pinnedOnly,
                onlyShowHasResults = state.onlyShowHasResults,
                onPinnedOnly = viewModel::setPinnedOnly,
                onToggleHasResults = viewModel::toggleHasResults,
            )
            // Every source answered and none had anything: upstream's empty screen. A source that
            // failed keeps the sections on screen instead, because its Retry is in its section.
            if (state.noPinnedSources) {
                EmptyScreen(stringRes = R.string.global_search_no_pinned_sources, modifier = Modifier.weight(1f))
            } else if (state.noResults && state.groups.none { it.status is GlobalSearchViewModel.Status.Error }) {
                EmptyScreen(stringRes = R.string.no_results_found, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    when {
                        state.submittedQuery == null ->
                            item { InlineNotice(stringResource(R.string.global_search_empty)) }
                        state.noResults ->
                            item { InlineNotice(stringResource(R.string.global_search_no_results)) }
                    }
                    items(state.visibleGroups, key = { it.sourceId }) { group ->
                        SourceSection(
                            group = group,
                            // Upstream's "▶" before the entry's own source.
                            title = if (group.sourceId == state.migratingFrom?.source) {
                                "▶ ${group.sourceName}"
                            } else {
                                group.sourceName
                            },
                            resources = resources,
                            onMore = {
                                navigator.open(
                                    Route.SourceBrowse(
                                        sourceId = group.sourceId,
                                        query = state.submittedQuery,
                                        mode = SourceBrowseMode.SEARCH,
                                        migratingMangaId = migratingMangaId,
                                    ),
                                )
                            }.takeIf { mergingMangaId == null },
                            inLibrary = state::inLibrary,
                            onManga = onManga,
                            onMangaLongClick = onMangaLongClick,
                            onRetry = viewModel::retry,
                        )
                    }
                }
            }
        }
    }

    state.mergeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMergeDialog,
            title = { Text(stringResource(R.string.merged_confirm_title)) },
            text = {
                Text(stringResource(R.string.merged_confirm_message_format, target.title, state.mergeTargetSource))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmMerge(
                            onMerged = navigator::back,
                            onFailed = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(resources.getString(R.string.merged_failed))
                                }
                            },
                        )
                    },
                ) {
                    Text(stringResource(R.string.action_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMergeDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val current = state.migratingFrom
    val target = state.migrateTarget
    if (current != null && target != null) {
        MigrateMangaDialog(
            current = current,
            target = target,
            onClickTitle = { navigator.open(Route.MangaDetail(target.id, fromSource = true)) },
            onDismissRequest = viewModel::dismissMigrateDialog,
            onFailed = {
                viewModel.dismissMigrateDialog()
                scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.migration_single_failed)) }
            },
            onComplete = { navigator.completeSingleMigration(target.id) },
        )
    }
}

/**
 * The field submits on the keyboard's search action as well as on the trailing button, because the
 * soft keyboard is covering that button on every phone this runs on. A submit puts the keyboard
 * away, as upstream's search bar does, so the results it asked for are not under it.
 */
@Composable
private fun GlobalSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submit = {
        onSubmit()
        keyboard?.hide()
        focusManager.clearFocus()
    }
    SearchField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.global_search_hint),
        onSubmit = submit,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
    )
}

/**
 * Upstream's chips under the field: Pinned and All choose the sources the next search asks (and
 * re-run the one on screen), Has results hides the groups with nothing to show.
 */
@Composable
private fun GlobalSearchChips(
    showSourceFilter: Boolean,
    pinnedOnly: Boolean,
    onlyShowHasResults: Boolean,
    onPinnedOnly: (Boolean) -> Unit,
    onToggleHasResults: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSourceFilter) {
            FilterChip(
                selected = pinnedOnly,
                onClick = { onPinnedOnly(true) },
                leadingIcon = { ChipIcon(Icons.Outlined.PushPin) },
                label = { Text(stringResource(R.string.pinned_sources)) },
            )
            FilterChip(
                selected = !pinnedOnly,
                onClick = { onPinnedOnly(false) },
                leadingIcon = { ChipIcon(Icons.Outlined.DoneAll) },
                label = { Text(stringResource(R.string.global_search_all_sources)) },
            )
            VerticalDivider(modifier = Modifier.height(FilterChipDefaults.Height))
        }
        FilterChip(
            selected = onlyShowHasResults,
            onClick = onToggleHasResults,
            leadingIcon = { ChipIcon(Icons.Outlined.FilterList) },
            label = { Text(stringResource(R.string.global_search_has_results)) },
        )
    }
}

@Composable
private fun ChipIcon(imageVector: ImageVector) {
    Icon(imageVector, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
}

@Composable
private fun SourceSection(
    group: GlobalSearchViewModel.SourceGroup,
    title: String,
    resources: Resources,
    inLibrary: (Manga) -> Boolean,
    onMore: (() -> Unit)?,
    onManga: (Manga) -> Unit,
    onMangaLongClick: ((Manga) -> Unit)?,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            if (onMore != null && group.status is GlobalSearchViewModel.Status.Results) {
                TextButton(onClick = onMore) {
                    Text(stringResource(R.string.global_search_source_more))
                }
            }
        }
        when (val status = group.status) {
            GlobalSearchViewModel.Status.Loading ->
                Box(Modifier.fillMaxWidth().padding(24.dp)) { LoadingScreen() }
            GlobalSearchViewModel.Status.Empty ->
                InlineNotice(stringResource(R.string.global_search_source_empty))
            is GlobalSearchViewModel.Status.Error -> SourceError(
                message = ErrorText.of(resources, status.error),
                onRetry = onRetry,
            )
            is GlobalSearchViewModel.Status.Results -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(status.mangas, key = Manga::id) { manga ->
                    CoverCell(
                        manga = manga,
                        inLibrary = inLibrary(manga),
                        onClick = { onManga(manga) },
                        onLongClick = onMangaLongClick?.let { { it(manga) } },
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SourceError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/** A result, marked as Browse marks one already in the library: upstream's badge, the cover faded. */
@Composable
private fun CoverCell(manga: Manga, inLibrary: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val mark = inLibraryMark(inLibrary)
    Column(
        modifier = Modifier.width(COVER_WIDTH).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            MangaCover(manga, modifier = Modifier.fillMaxWidth(), contentDescription = manga.title, alpha = mark.coverAlpha)
            if (mark.badge) BadgeGroup(modifier = Modifier.padding(4.dp)) { InLibraryBadge() }
        }
        Text(
            manga.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}

private val COVER_WIDTH = 110.dp
