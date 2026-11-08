package com.shinsaikou.ui.upcoming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.library.visualName
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TabbedDialog
import tachiyomi.presentation.core.components.TabbedDialogPaddings
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Upstream's `UpcomingFilterDialog` (mihon#3607): one Categories tab, its note, then a tri-state row
 * per category. Like the Updates filter, it reads [filter] and sends each tap to the view model
 * rather than keeping upstream's local copy of the rows, so a rotation has nothing to lose.
 */
@Composable
internal fun UpcomingFilterDialog(
    filter: UpcomingViewModel.Filter,
    categories: List<Category>,
    onDismissRequest: () -> Unit,
    onCycleCategory: (Long) -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(stringResource(R.string.categories)),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.pref_filter_upcoming_categories_details),
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
            } else {
                categories.forEach { category ->
                    TriStateItem(
                        label = category.visualName,
                        state = filter.categoryState(category.id),
                        onClick = { onCycleCategory(category.id) },
                    )
                }
            }
        }
    }
}
