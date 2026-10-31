// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/reader/ReaderPageActionsDialog.kt (modified: yes)
package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Modified (S), and the copy-to-clipboard button is deleted with its callback: `PageImageActions`
 * declares no clipboard method, so `onShare` takes no argument (decision D22). The sheet still has
 * four buttons, with a different fourth: set as cover, share and save go through
 * `PageImageActions`, and Saikou's own Note opens the page's note ([onNote], phase 4 unit BN, which
 * amends D22). A note needs only `:domain`, so it is a reader event, not a `PageImageActions` method.
 * Note does not call [onDismissRequest]: the reducer closes the sheet on the note event itself. The
 * note dialog opens after a database read, and on the API 26 AVD that read's answer was reduced
 * before the dismiss sent right behind the note event, which then closed the note dialog.
 */
@Composable
fun ReaderPageActionsDialog(
    onDismissRequest: () -> Unit,
    onSetAsCover: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onNote: () -> Unit,
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.set_as_cover),
                icon = Icons.Outlined.Photo,
                onClick = { showSetCoverDialog = true },
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.action_share),
                icon = Icons.Outlined.Share,
                onClick = {
                    onShare()
                    onDismissRequest()
                },
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.action_save),
                icon = Icons.Outlined.Save,
                onClick = {
                    onSave()
                    onDismissRequest()
                },
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.action_page_note),
                icon = Icons.Outlined.EditNote,
                onClick = onNote,
            )
        }
    }

    if (showSetCoverDialog) {
        SetCoverDialog(
            onConfirm = {
                onSetAsCover()
                showSetCoverDialog = false
            },
            onDismiss = { showSetCoverDialog = false },
        )
    }
}

@Composable
private fun SetCoverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        text = {
            Text(stringResource(R.string.confirm_set_image_as_cover))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}
