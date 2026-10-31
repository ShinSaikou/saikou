package com.shinsaikou.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Upstream's six download choices (phase 3 unit AD), from `DownloadDropdownMenu.kt` and `DownloadAction`
 * in its presentation code, in its order. Which chapters each one takes is the screen's: the manga
 * screen's view model and the library's each copy their upstream counterpart.
 */
enum class DownloadAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,
    BOOKMARKED_CHAPTERS,
}

/**
 * The menu of [DownloadAction]s, with upstream's labels. It closes itself on a choice. Place it beside
 * the control that opens it, as any [DropdownMenu].
 */
@Composable
fun DownloadDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onDownload: (DownloadAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        DownloadAction.entries.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label()) },
                onClick = {
                    onDismissRequest()
                    onDownload(action)
                },
            )
        }
    }
}

@Composable
private fun DownloadAction.label(): String = when (this) {
    DownloadAction.NEXT_1_CHAPTER -> pluralStringResource(R.plurals.download_amount, 1, 1)
    DownloadAction.NEXT_5_CHAPTERS -> pluralStringResource(R.plurals.download_amount, 5, 5)
    DownloadAction.NEXT_10_CHAPTERS -> pluralStringResource(R.plurals.download_amount, 10, 10)
    DownloadAction.NEXT_25_CHAPTERS -> pluralStringResource(R.plurals.download_amount, 25, 25)
    DownloadAction.UNREAD_CHAPTERS -> stringResource(R.string.download_unread)
    DownloadAction.BOOKMARKED_CHAPTERS -> stringResource(R.string.download_bookmarked)
}
