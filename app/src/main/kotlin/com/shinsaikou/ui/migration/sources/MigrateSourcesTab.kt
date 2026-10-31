package com.shinsaikou.ui.migration.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.browse.SourceIcon
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.util.copyToClipboard
import com.shinsaikou.ui.util.languageName
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.source.interactor.SetMigrateSorting
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.Scroller.STICKY_HEADER_KEY_PREFIX
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * Saikou's migration guide, which the Migrate page's help action opens, as upstream's `MigrateSourceTab`
 * opens upstream's. It was upstream's page until Saikou's docs were published (2026-10-24): upstream's warned
 * about limits Saikou does not have and missed the options it does.
 */
const val MIGRATION_GUIDE_URL = "https://shinsaikou.com/docs/migration"

/**
 * Browse's Migrate page (phase 4 unit MB), upstream's `MigrateSourceScreen`: a sticky header with the
 * prompt and the two sort buttons, then every source holding library entries with its language, "Not
 * installed" for a source whose extension is gone, and the count. A tap opens the source's entries
 * to pick from; a long press copies the source's id, as upstream's does.
 *
 * Each row shows its extension's icon, as upstream's `SourceIcon`, and the config screen does the same.
 */
@Composable
fun MigrateSourcesBody(padding: PaddingValues) {
    val viewModel: MigrateSourcesViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current

    when {
        state.isLoading -> LoadingScreen(Modifier.padding(padding))
        state.isEmpty -> EmptyScreen(
            stringRes = R.string.information_empty_library,
            modifier = Modifier.padding(padding),
        )
        else -> FastScrollLazyColumn(contentPadding = padding + topSmallPaddingValues) {
            stickyHeader(key = STICKY_HEADER_KEY_PREFIX) {
                SortHeader(
                    sortingMode = state.sortingMode,
                    sortingDirection = state.sortingDirection,
                    onToggleSortingMode = viewModel::toggleSortingMode,
                    onToggleSortingDirection = viewModel::toggleSortingDirection,
                )
            }
            items(state.items, key = { "migrate-${it.id}" }) { source ->
                MigrateSourceItem(
                    modifier = Modifier.animateItem(),
                    source = source,
                    onClick = { navigator.open(Route.MigratePicker(source.id)) },
                    onLongClick = {
                        context.copyToClipboard(source.id.toString(), resources.getString(R.string.copied_to_clipboard))
                    },
                )
            }
        }
    }
}

@Composable
private fun SortHeader(
    sortingMode: SetMigrateSorting.Mode,
    sortingDirection: SetMigrateSorting.Direction,
    onToggleSortingMode: () -> Unit,
    onToggleSortingDirection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(start = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.migration_selection_prompt),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.header,
        )

        IconButton(onClick = onToggleSortingMode) {
            when (sortingMode) {
                SetMigrateSorting.Mode.ALPHABETICAL -> Icon(
                    Icons.Outlined.SortByAlpha,
                    contentDescription = stringResource(R.string.action_sort_alpha),
                )
                SetMigrateSorting.Mode.TOTAL -> Icon(
                    Icons.Outlined.Numbers,
                    contentDescription = stringResource(R.string.action_sort_count),
                )
            }
        }
        IconButton(onClick = onToggleSortingDirection) {
            when (sortingDirection) {
                SetMigrateSorting.Direction.ASCENDING -> Icon(
                    Icons.Outlined.ArrowUpward,
                    contentDescription = stringResource(R.string.action_asc),
                )
                SetMigrateSorting.Direction.DESCENDING -> Icon(
                    Icons.Outlined.ArrowDownward,
                    contentDescription = stringResource(R.string.action_desc),
                )
            }
        }
    }
}

/**
 * A source's row: upstream's `MigrateSourceItem` text (the name in `bodyMedium`, the language and "Not
 * installed" in `bodySmall` at `secondaryItemAlpha`, "Not installed" in `error`) and the count, on
 * `ListRowLayout`, whose frame and divider the row has kept since Phase 5.
 */
@Composable
private fun MigrateSourceItem(
    source: MigrateSourcesViewModel.SourceRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListRowLayout(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = { SourceIcon(source.id, 40.dp) },
        trailing = {
            BadgeGroup {
                Badge(text = "${source.count}")
            }
        },
    ) {
        Text(
            text = source.name.ifBlank { source.id.toString() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (source.lang.isNotBlank()) {
                Text(
                    modifier = Modifier.secondaryItemAlpha(),
                    text = languageName(source.lang),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (source.isStub) {
                Text(
                    modifier = Modifier.secondaryItemAlpha(),
                    text = stringResource(R.string.not_installed),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
