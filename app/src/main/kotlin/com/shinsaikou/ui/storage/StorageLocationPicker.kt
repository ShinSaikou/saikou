package com.shinsaikou.ui.storage

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Resources
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.i18n.R
import com.shinsaikou.storage.StorageMove
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Opens the folder picker for the storage location and, when the app folders or the location it
 * replaces hold something to move, the Move or Cancel dialog. Every screen that offers Choose folder calls this once and gets
 * back the action that opens the picker; [then] says what to do once a folder is set.
 */
@Composable
fun rememberStorageLocationPicker(
    viewModel: StorageLocationViewModel,
    then: StorageLocationViewModel.Then = StorageLocationViewModel.Then.NOTHING,
): () -> Unit {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Upstream's flags and its fallback: some providers refuse a persisted grant but serve the
            // folder for as long as the process lives, so a refusal is said and the folder still taken.
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                Toast.makeText(context, R.string.file_picker_uri_permission_unsupported, Toast.LENGTH_LONG).show()
            }
            viewModel.onPicked(uri)
        }
    }

    state.choice?.let { choice ->
        val dialog = moveDialog(LocalResources.current, choice) { Formatter.formatFileSize(context, it) }
        AlertDialog(
            onDismissRequest = viewModel::cancelChoice,
            title = { Text(stringResource(R.string.pref_storage_location)) },
            text = { Text(dialog.text) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMove, enabled = dialog.canMove) {
                    Text(stringResource(R.string.action_move))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelChoice) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    return {
        viewModel.pickingFor(then)
        try {
            launcher.launch(null)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.file_picker_error, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * "4 backups and 12 Local series will be moved to Saikou", the counts that are not zero joined; or,
 * when some of them are in the previous location [from], "4 backups, 12 Local series and 3,100
 * exported chapters will be moved from Saikou to SD card (12.4 GB)", the size from [size].
 */
internal fun moveSentence(
    resources: Resources,
    pending: StorageMove.Pending,
    folder: String,
    from: String? = null,
    size: (Long) -> String = { "" },
): String {
    val counts = buildList {
        if (pending.backups > 0) add(resources.getQuantityString(R.plurals.storage_move_backups, pending.backups, pending.backups))
        if (pending.localSeries > 0) {
            add(resources.getQuantityString(R.plurals.storage_move_local_series, pending.localSeries, pending.localSeries))
        }
        if (pending.exportedChapters > 0) {
            add(resources.getQuantityString(R.plurals.storage_move_exported_chapters, pending.exportedChapters, pending.exportedChapters))
        }
    }
    val joined = joinCounts(resources, counts)
    return if (from != null) {
        resources.getString(R.string.storage_move_from_format, joined, from, folder, size(pending.bytes))
    } else {
        resources.getString(R.string.storage_move_one_format, joined, folder)
    }
}

/** "a", "a and b", "a, b and c". */
private fun joinCounts(resources: Resources, counts: List<String>): String {
    if (counts.size < 2) return counts.firstOrNull().orEmpty()
    val last = resources.getString(R.string.storage_move_join_pair_format, counts[counts.size - 2], counts.last())
    return counts.dropLast(2).foldRight(last) { count, rest -> resources.getString(R.string.storage_move_join_comma_format, count, rest) }
}

/** The confirm dialog's text and whether Move is offered: not when the new folder lacks the room the copy needs. */
internal class MoveDialog(val text: String, val canMove: Boolean)

internal fun moveDialog(resources: Resources, choice: StorageLocationViewModel.Choice, size: (Long) -> String): MoveDialog {
    val sentence = moveSentence(resources, choice.pending, choice.name, choice.from, size)
    val free = choice.free
    if (free == null || free >= choice.needed) return MoveDialog(sentence, canMove = true)
    val noSpace = resources.getString(R.string.storage_move_no_space_format, choice.name, size(choice.needed))
    return MoveDialog("$sentence\n\n$noSpace", canMove = false)
}

/** The Storage location row's summary for each state of the location. */
internal fun storageLocationSummary(resources: Resources, status: StorageLocation.Status?, name: String?): String =
    when (status) {
        StorageLocation.Status.REACHABLE -> name.orEmpty()
        StorageLocation.Status.UNREACHABLE ->
            resources.getString(R.string.pref_storage_location_unreachable_format, name.orEmpty())
        StorageLocation.Status.NOT_SET, null -> resources.getString(R.string.pref_storage_location_not_set)
    }

/**
 * Upstream's Storage location row with its info line, then, while the app folders or the previous
 * location still hold files with a reachable location set (a move that left some behind, or backups
 * written while the folder could not be reached), the row that moves them.
 */
@Composable
fun StorageLocationRows(viewModel: StorageLocationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val context = LocalContext.current
    val pick = rememberStorageLocationPicker(viewModel)
    ListRow(
        title = stringResource(R.string.pref_storage_location),
        detail = storageLocationSummary(resources, state.status, state.name),
        modifier = Modifier.testTag(STORAGE_LOCATION_ROW_TAG),
        onClick = if (state.move.running) null else pick,
    )
    val moving = state.move
    val left = state.left
    when {
        moving.running -> ListRow(
            title = stringResource(R.string.pref_storage_move_left),
            detail = if (moving.total == 0) {
                stringResource(R.string.pref_storage_move_counting)
            } else {
                stringResource(R.string.pref_storage_move_progress_format, moving.done, moving.total)
            },
        )
        left != null -> ListRow(
            title = stringResource(R.string.pref_storage_move_left),
            detail = moveSentence(resources, left, state.name.orEmpty(), state.previousName) {
                Formatter.formatFileSize(context, it)
            },
            onClick = viewModel::moveLeft,
        )
    }
    InlineNotice(stringResource(R.string.pref_storage_location_info))
}

/** The tag on the Storage location row, which a device test reads. */
const val STORAGE_LOCATION_ROW_TAG = "settings_storage_location"
