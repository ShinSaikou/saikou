// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/more/settings/screen/advanced/ClearDatabaseScreen.kt (modified: yes) the screen's scaffold and row, shared by clear database and the downloads not in the library
package com.shinsaikou.ui.storage

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ScreenTopBar
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/** One row of a [ChecklistScreen]: what it is, and a line saying how much of it there is. */
data class ChecklistItem(val id: Long, val title: String, val detail: String)

/**
 * Upstream's clear database screen without its data: a list of rows to tick, upstream's select all and
 * select inverse in the top bar while there is a row, and one action button under the list,
 * enabled while something is ticked. [items] is null while loading; an empty list shows
 * [emptyMessage]. The confirmation is the caller's, drawn over this, and so is [snackbarHost], where
 * the caller reports what its action did.
 *
 * Modified: the rows carry no source icon (Saikou's lists draw none), the top bar is Saikou's
 * [ScreenTopBar], and the screen takes its rows and callbacks instead of a view model, so the
 * storage screen's list of downloads not in the library draws the same checklist.
 */
@Composable
internal fun ChecklistScreen(
    title: String,
    items: List<ChecklistItem>?,
    selection: Set<Long>,
    emptyMessage: String,
    actionLabel: String,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onAction: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = title,
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (!items.isNullOrEmpty()) {
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                        }
                        IconButton(onClick = onInvertSelection) {
                            Icon(Icons.Outlined.FlipToBack, contentDescription = stringResource(R.string.action_select_inverse))
                        }
                    }
                },
            )
        },
        snackbarHost = snackbarHost,
    ) { contentPadding ->
        when {
            items == null -> LoadingScreen(Modifier.padding(contentPadding))
            items.isEmpty() -> EmptyScreen(message = emptyMessage, modifier = Modifier.padding(contentPadding))
            else -> LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = actionLabel,
                actionEnabled = selection.isNotEmpty(),
                onClickAction = onAction,
            ) {
                items(items, key = { it.id }) { item ->
                    ChecklistRow(
                        item = item,
                        isSelected = item.id in selection,
                        onClickSelect = { onToggle(item.id) },
                    )
                }
            }
        }
    }
}

/** The tag on a checklist row, by its id, for a device test to tap it. */
internal fun checklistRowTag(id: Long): String = "checklist_row_$id"

/**
 * A row in upstream's Clear database item's text (the title in `bodyMedium`, the detail in the body
 * style) on `ListRowLayout`, ticked by its checkbox or a tap anywhere on it.
 */
@Composable
private fun ChecklistRow(
    item: ChecklistItem,
    isSelected: Boolean,
    onClickSelect: () -> Unit,
) {
    ListRowLayout(
        modifier = Modifier.testTag(checklistRowTag(item.id)),
        onClick = onClickSelect,
        selected = isSelected,
        trailing = {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
        },
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(text = item.detail)
    }
}
