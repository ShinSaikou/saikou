package com.shinsaikou.ui.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.downloads.OrphanTitle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Downloads of titles that are no longer in the library (phase 4 unit ST, upstream issue #565), reached
 * from the Data section's row. Each row is one title with its chapter count and size; ticked titles
 * are deleted from this device after a confirmation that names every one of them.
 */
@Composable
fun StorageDownloadsScreen() {
    val navigator = LocalNavigator.current
    val viewModel: StorageDownloadsViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ChecklistScreen(
        title = stringResource(R.string.storage_downloads_title),
        items = state.titles?.map { title ->
            ChecklistItem(
                id = title.mangaId,
                title = title.title,
                detail = pluralStringResource(
                    R.plurals.storage_downloads_item_format,
                    title.chapterCount,
                    title.chapterCount,
                    Formatter.formatFileSize(context, title.sizeBytes),
                ),
            )
        },
        selection = state.selection,
        emptyMessage = stringResource(R.string.storage_downloads_empty),
        actionLabel = stringResource(R.string.action_delete),
        onBack = navigator::back,
        onToggle = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onInvertSelection = viewModel::invertSelection,
        onAction = viewModel::showConfirmation,
    )

    state.confirming?.let { confirming ->
        DeleteDownloadsDialog(
            titles = confirming,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::hideConfirmation,
        )
    }
}

/** Names every title the delete acts on, so nothing goes that the owner did not read. */
@Composable
private fun DeleteDownloadsDialog(
    titles: List<OrphanTitle>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_downloads_for_manga)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(pluralStringResource(R.plurals.storage_downloads_delete_message_format, titles.size, titles.size))
                titles.forEach { title ->
                    Text(text = title.title, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
