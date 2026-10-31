package com.shinsaikou.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A settings string set edited as a list of checkboxes, upstream's `MultiSelectListPreferenceWidget`
 * dialog: [entries] are each stored value with its label, in the order shown, and a ticked box means
 * the value is in the set. The working copy is local and keyed on [selected], so nothing is written
 * until the owner confirms. A value in [selected] with no entry is handed back untouched.
 *
 * The enabled languages (ruling R57) and the categories excluded from delete after reading (phase 3
 * unit DR) open it.
 */
@Composable
internal fun MultiSelectDialog(
    title: String,
    entries: List<Pair<String, String>>,
    selected: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var working by remember(selected) { mutableStateOf(selected) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = MAX_CONTENT_HEIGHT).verticalScroll(rememberScrollState())) {
                entries.forEach { (value, label) ->
                    CheckboxItem(
                        label = label,
                        checked = value in working,
                        onClick = {
                            working = if (value in working) working - value else working + value
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val MAX_CONTENT_HEIGHT = 400.dp
