package com.shinsaikou.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The owner's own title and description for an entry (phase 5 unit ED, R138 item 2): one dialog
 * with the two fields, prefilled with what the screen shows, on Material's text buttons and outlined
 * fields, as the category name dialog is.
 *
 * Save hands both texts to the view model, whose interactor stores an override only where a text
 * differs from the source's, so saving the source's own title clears that edit. A blank title
 * cannot be saved: the field says so and Save is disabled. Reset clears both edits at once and is
 * enabled only while the entry has one ([edited]).
 *
 * The two texts are the dialog's own `rememberSaveable` state, so a rotation keeps what was typed
 * and nothing the owner types waits on a view model round trip.
 */
@Composable
internal fun EditDetailsDialog(
    title: String,
    description: String,
    edited: Boolean,
    onDismissRequest: () -> Unit,
    onSave: (title: String, description: String) -> Unit,
    onReset: () -> Unit,
) {
    var titleText by rememberSaveable { mutableStateOf(title) }
    var descriptionText by rememberSaveable { mutableStateOf(description) }
    val titleEmpty = titleText.isBlank()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.manga_edit_details)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manga_edit_title)) },
                    supportingText = if (titleEmpty) {
                        { Text(stringResource(R.string.manga_edit_title_empty)) }
                    } else {
                        null
                    },
                    isError = titleEmpty,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manga_edit_description)) },
                    minLines = DESCRIPTION_MIN_LINES,
                    maxLines = DESCRIPTION_MAX_LINES,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(titleText, descriptionText) }, enabled = !titleEmpty) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset, enabled = edited) { Text(stringResource(R.string.manga_edit_reset)) }
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

private const val DESCRIPTION_MIN_LINES = 3
private const val DESCRIPTION_MAX_LINES = 8
