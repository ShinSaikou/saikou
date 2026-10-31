package com.shinsaikou.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.components.ListRow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The Data section's Export library list, upstream v0.20.4's library export: the row asks which
 * columns to write, the system's save dialog makes the file (the storage access framework, so no
 * storage permission), and [showMessage] says how it went.
 */
@Composable
internal fun LibraryExportRow(showMessage: (String, SnackbarAction?) -> Unit) {
    val viewModel: LibraryExportViewModel = metroViewModel()
    val resources = LocalResources.current
    var choosing by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf(true) }
    var author by rememberSaveable { mutableStateOf(true) }
    var artist by rememberSaveable { mutableStateOf(true) }
    val columns = LibraryCsvColumns(title = title, author = author, artist = artist)
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME_TYPE)) { uri ->
        if (uri != null) {
            viewModel.export(uri, columns) { written ->
                showMessage(
                    resources.getString(if (written) R.string.library_export_done else R.string.library_export_failed),
                    null,
                )
            }
        }
    }
    ListRow(
        title = stringResource(R.string.library_export),
        detail = stringResource(R.string.library_export_detail),
        onClick = { choosing = true },
    )
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.migration_dialog_what_to_include)) },
            text = {
                Column {
                    CheckboxItem(
                        label = stringResource(R.string.library_export_column_title),
                        checked = title,
                        onClick = { title = !title },
                    )
                    CheckboxItem(
                        label = stringResource(R.string.library_export_column_author),
                        checked = author,
                        onClick = { author = !author },
                    )
                    CheckboxItem(
                        label = stringResource(R.string.library_export_column_artist),
                        checked = artist,
                        onClick = { artist = !artist },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        choosing = false
                        createFile.launch(CSV_FILE_NAME)
                    },
                    enabled = columns.any,
                ) { Text(stringResource(R.string.library_export_action)) }
            },
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private const val CSV_MIME_TYPE = "text/csv"
private const val CSV_FILE_NAME = "saikou_library.csv"
