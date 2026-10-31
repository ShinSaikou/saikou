package com.shinsaikou.ui.manga

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
 * The owner's notes on an entry, in the edit details dialog's shape: one outlined field, prefilled
 * with the stored notes. Upstream edits them on a screen of its own with a rich text toolbar and
 * stores markdown; this field is plain text, so a note restored from upstream shows its markdown
 * marks as they were typed.
 *
 * The text is the dialog's own `rememberSaveable` state, so a rotation keeps what was typed.
 */
@Composable
internal fun MangaNotesDialog(
    notes: String,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(notes) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.action_notes)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.manga_notes_hint)) },
                minLines = NOTES_MIN_LINES,
                maxLines = NOTES_MAX_LINES,
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

private const val NOTES_MIN_LINES = 4
private const val NOTES_MAX_LINES = 12
