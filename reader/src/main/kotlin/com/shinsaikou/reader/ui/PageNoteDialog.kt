package com.shinsaikou.reader.ui

import androidx.compose.foundation.layout.fillMaxWidth
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
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The one-field note dialog of a page (phase 4 unit BN), opened from the page sheet's Note button
 * and from the bottom bar's marker. [note] is the page's stored note, or "" when it has none.
 *
 * Save hands back the text as typed; the reducer removes the note when it is blank. The field is
 * `rememberSaveable`, so a rotation with the dialog open keeps what was typed, as the category
 * dialog's does. Every change also goes to [onTextChange], which the view model keeps in its saved
 * state handle: after a process death the dialog is reopened from there with [note] holding that
 * text, since nothing would compose this dialog to restore its field.
 */
@Composable
internal fun PageNoteDialog(
    note: String,
    onTextChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var text by rememberSaveable(note) { mutableStateOf(note) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.page_note_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onTextChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.page_note_empty_removes)) },
                maxLines = MAX_LINES,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Past this the field scrolls, so a long note cannot push the buttons off a landscape phone. */
private const val MAX_LINES = 6
