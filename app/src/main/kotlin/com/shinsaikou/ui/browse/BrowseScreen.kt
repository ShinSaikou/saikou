package com.shinsaikou.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.BrowsePage
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SourceBrowseMode
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SearchField
import com.shinsaikou.ui.components.SectionLabel
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.migration.sources.MIGRATION_GUIDE_URL
import com.shinsaikou.ui.migration.sources.MigrateSourcesBody
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The Browse tab: the installed sources, the extension lists and the sources to migrate from (phase
 * 4 unit MB) under one top bar. The route's page only seeds the selection, which then belongs to the
 * screen, so a rotation or a trip to a source and back comes back to the page the owner was on.
 *
 * [extensionsQuery] (`Route.Browse.extensionsQuery`, unit restore-report) seeds the Extensions
 * page's search the same way, once: a source the restore report found missing opens here with its
 * name searched. The seed is dropped once the view model holds it, so the page shown again after
 * Sources or Migrate keeps whatever the owner typed since.
 */
@Composable
fun BrowseScreen(initialPage: BrowsePage, extensionsQuery: String? = null) {
    val extensionsViewModel: ExtensionsViewModel = metroViewModel()
    val extensionsState by extensionsViewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var seedQuery by rememberSaveable { mutableStateOf(extensionsQuery) }
    LaunchedEffect(extensionsState.searchQuery) {
        if (seedQuery != null && extensionsState.searchQuery == seedQuery) seedQuery = null
    }

    LaunchedEffect(extensionsViewModel) {
        extensionsViewModel.events.collect { event ->
            val message = when (event) {
                is ExtensionsViewModel.Event.Snackbar -> resources.getString(event.messageRes)
                is ExtensionsViewModel.Event.SnackbarError ->
                    resources.getString(event.messageRes, RepoErrorText.of(resources, event.error))
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            Column {
                ScreenTopBar(
                    title = stringResource(R.string.browse_title),
                    scrollBehavior = scrollBehavior,
                    // Global search hangs off the tab's own bar rather than either page, because
                    // this is the only way into it and the owner should not have to guess which of
                    // the two pages is holding it.
                    actions = {
                        IconButton(onClick = { navigator.open(Route.GlobalSearch()) }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.action_global_search),
                            )
                        }
                        // Upstream's Filter action on the Sources page only, after global search as
                        // upstream's Sources tab orders its two (phase 5 unit BF).
                        if (page == BrowsePage.SOURCES) {
                            IconButton(onClick = { navigator.open(Route.SourcesFilter) }) {
                                Icon(
                                    Icons.Outlined.FilterList,
                                    contentDescription = stringResource(R.string.action_filter),
                                )
                            }
                        }
                        // Upstream's Filter action on the Extensions page, opening the extension
                        // filter (phase 5 unit BX). Upstream keeps it in the tab's overflow before
                        // "Extension stores"; this bar shows icons, and the stores stay in the
                        // page's own header.
                        if (page == BrowsePage.EXTENSIONS) {
                            IconButton(onClick = { navigator.open(Route.ExtensionFilter) }) {
                                Icon(
                                    Icons.Outlined.FilterList,
                                    contentDescription = stringResource(R.string.action_filter),
                                )
                            }
                        }
                        // Upstream's help action on the Migrate page only, after global search.
                        if (page == BrowsePage.MIGRATE) {
                            IconButton(onClick = { navigator.openUrl(MIGRATION_GUIDE_URL) }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.HelpOutline,
                                    contentDescription = stringResource(R.string.migration_help_guide),
                                )
                            }
                        }
                    },
                )
                BrowseTabRow(
                    page = page,
                    updateCount = extensionsState.updatesCount,
                    onPage = { page = it },
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when (page) {
            BrowsePage.SOURCES -> SourcesBody(
                padding = padding,
                onExtensions = { page = BrowsePage.EXTENSIONS },
            )
            BrowsePage.EXTENSIONS -> ExtensionsTab(
                viewModel = extensionsViewModel,
                state = extensionsState,
                padding = padding,
                initialQuery = seedQuery,
            )
            BrowsePage.MIGRATE -> MigrateSourcesBody(padding = padding)
        }
    }
}

@Composable
private fun BrowseTabRow(page: BrowsePage, updateCount: Int, onPage: (BrowsePage) -> Unit) {
    PrimaryTabRow(selectedTabIndex = page.ordinal) {
        Tab(
            selected = page == BrowsePage.SOURCES,
            onClick = { onPage(BrowsePage.SOURCES) },
            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            text = { TabText(stringResource(R.string.browse_tab_sources)) },
        )
        Tab(
            selected = page == BrowsePage.EXTENSIONS,
            onClick = { onPage(BrowsePage.EXTENSIONS) },
            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            text = {
                TabText(
                    text = stringResource(R.string.browse_tab_extensions),
                    badgeCount = updateCount.takeIf { it > 0 },
                )
            },
        )
        Tab(
            selected = page == BrowsePage.MIGRATE,
            onClick = { onPage(BrowsePage.MIGRATE) },
            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            text = { TabText(stringResource(R.string.label_migration)) },
        )
    }
}

@Composable
private fun SourcesBody(padding: PaddingValues, onExtensions: () -> Unit) {
    val viewModel: SourcesViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    // The field's text is the page's own, for the Extensions page's reason (see ExtensionsTab): the
    // view model's copy comes back through a flow on the IO dispatcher, and a field recomposed with
    // it a frame late turned "fixture search" typed a key at a time into "ixture searchf". It
    // starts from the view model's query, which outlives this page while Browse shows another one.
    var query by rememberSaveable { mutableStateOf(state.query) }
    LaunchedEffect(query) { viewModel.search(query) }

    when {
        state.loading -> LoadingScreen(Modifier.padding(padding))
        state.sources.isEmpty() && state.query.isBlank() -> EmptyScreen(
            stringRes = R.string.source_empty,
            modifier = Modifier.padding(padding),
            actions = listOf(
                EmptyScreenAction(
                    stringRes = R.string.label_extensions,
                    icon = Icons.Outlined.Extension,
                    onClick = onExtensions,
                ),
            ),
        )
        else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.action_search),
                )
            }
            if (state.sources.isEmpty()) {
                item { InlineNotice(stringResource(R.string.empty_search_results)) }
            }
            items(
                state.items,
                key = { item ->
                    when (item) {
                        is SourceListItem.Label -> "label-${item.section}"
                        // The last used source is listed twice, so its key is upstream's `key()`.
                        is SourceListItem.Source -> "source-${item.row.key}"
                    }
                },
            ) { item ->
                when (item) {
                    is SourceListItem.Label -> SectionLabel(
                        stringResource(
                            when (item.section) {
                                SourceSection.LAST_USED -> R.string.last_used_source
                                SourceSection.PINNED -> R.string.pinned_sources
                                SourceSection.OTHER -> R.string.other_source
                            },
                        ),
                    )
                    is SourceListItem.Source -> ListRow(
                        title = item.row.name,
                        detail = item.row.lang,
                        leading = { SourceIcon(item.row.id, 40.dp) },
                        onClick = { navigator.open(Route.SourceBrowse(item.row.id)) },
                        onLongClick = { viewModel.showSourceDialog(item.row) },
                        // Upstream's Latest button, before the pin, on a source with a latest listing.
                        trailing = stringResource(R.string.source_mode_latest).takeIf { item.row.supportsLatest },
                        onTrailing = {
                            navigator.open(Route.SourceBrowse(item.row.id, mode = SourceBrowseMode.LATEST))
                        },
                        trailingIcon = {
                            SourcePinButton(
                                pinned = item.row.pinned,
                                onClick = { viewModel.togglePin(item.row.id) },
                            )
                        },
                    )
                }
            }
        }
    }

    state.dialog?.let { row ->
        SourceOptionsDialog(
            source = row,
            onClickPin = {
                viewModel.togglePin(row.id)
                viewModel.closeDialog()
            },
            onClickDisable = {
                viewModel.toggleSource(row.id)
                viewModel.closeDialog()
            },
            onDismiss = viewModel::closeDialog,
        )
    }
}

/**
 * Upstream's pin button, always on the row as well as in the long press dialog: filled in the primary
 * colour while pinned, outlined and muted while not, and named for what a tap does.
 */
@Composable
private fun SourcePinButton(pinned: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = stringResource(if (pinned) R.string.action_unpin else R.string.action_pin),
            tint = if (pinned) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA)
            },
        )
    }
}

/**
 * Upstream's `SourceOptionsDialog` (`presentation/browse/SourcesScreen.kt:164-199`), written to match it
 * line for line: the source's name with its language as the title, then Pin or Unpin, and Disable
 * for any source but the Local one, each a full-width tappable line; no buttons.
 */
@Composable
private fun SourceOptionsDialog(
    source: SourcesViewModel.SourceRow,
    onClickPin: () -> Unit,
    onClickDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = source.visualName)
        },
        text = {
            Column {
                val textId = if (source.pinned) R.string.action_unpin else R.string.action_pin
                Text(
                    text = stringResource(textId),
                    modifier = Modifier
                        .clickable(onClick = onClickPin)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                if (!source.isLocal) {
                    Text(
                        text = stringResource(R.string.action_disable),
                        modifier = Modifier
                            .clickable(onClick = onClickDisable)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}
