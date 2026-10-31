package com.shinsaikou.ui.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.library.visualName
import com.shinsaikou.ui.updates.UpdatesViewModel.UpdatesFilter
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TabbedDialog
import tachiyomi.presentation.core.components.TabbedDialogPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Upstream's `UpdatesFilterDialog`: a Filter tab with the four tri-states and the excluded scanlators
 * switch, and a Categories tab with a tri-state row per category, in upstream's order and with upstream's
 * strings (phase 4 unit UF).
 *
 * Upstream's dialog reads the preferences itself and keeps a local copy of the category rows; this one
 * reads [UpdatesViewModel.State.filters] and sends every tap to the view model, the way the chapter
 * settings sheet does, so a rotation has nothing to lose. The pager keeps which tab is open.
 */
@Composable
internal fun UpdatesFilterDialog(
    filters: UpdatesViewModel.Filters,
    categories: List<Category>,
    onDismissRequest: () -> Unit,
    onFilter: (UpdatesFilter, TriState) -> Unit,
    onToggleExcludedScanlators: () -> Unit,
    onCycleCategory: (Long) -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(
            stringResource(R.string.action_filter),
            stringResource(R.string.categories),
        ),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage(filters, onFilter, onToggleExcludedScanlators)
                1 -> CategoryPage(filters, categories, onCycleCategory)
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    filters: UpdatesViewModel.Filters,
    onFilter: (UpdatesFilter, TriState) -> Unit,
    onToggleExcludedScanlators: () -> Unit,
) {
    TriStateItem(
        label = stringResource(R.string.label_downloaded),
        state = filters.downloaded,
        onClick = { onFilter(UpdatesFilter.DOWNLOADED, it) },
    )
    TriStateItem(
        label = stringResource(R.string.action_filter_unread),
        state = filters.unread,
        onClick = { onFilter(UpdatesFilter.UNREAD, it) },
    )
    TriStateItem(
        label = stringResource(R.string.label_started),
        state = filters.started,
        onClick = { onFilter(UpdatesFilter.STARTED, it) },
    )
    TriStateItem(
        label = stringResource(R.string.action_filter_bookmarked),
        state = filters.bookmarked,
        onClick = { onFilter(UpdatesFilter.BOOKMARKED, it) },
    )

    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.small))

    // Upstream's row is a label and a switch rather than a checkbox item; kept as upstream draws it.
    Row(
        modifier = Modifier
            .clickable(onClick = onToggleExcludedScanlators)
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.action_filter_excluded_scanlators),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = filters.excludedScanlators,
            onCheckedChange = { onToggleExcludedScanlators() },
        )
    }
}

@Composable
private fun ColumnScope.CategoryPage(
    filters: UpdatesViewModel.Filters,
    categories: List<Category>,
    onCycleCategory: (Long) -> Unit,
) {
    Text(
        stringResource(R.string.pref_filter_update_categories_details),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )

    HorizontalDivider(modifier = Modifier.padding(MaterialTheme.padding.extraSmall))

    // The list holds the default category, so it is empty only until the first read lands.
    if (categories.isEmpty()) {
        LoadingScreen(modifier = Modifier.padding(16.dp))
        return
    }

    categories.forEach { category ->
        TriStateItem(
            label = category.visualName,
            state = filters.categoryState(category.id),
            onClick = { onCycleCategory(category.id) },
        )
    }
}
