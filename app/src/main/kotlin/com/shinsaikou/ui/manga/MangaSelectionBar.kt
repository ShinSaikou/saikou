package com.shinsaikou.ui.manga

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.chapter.ChapterDownload
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Which of the selection bar's actions apply to the selected chapters (plan 2.6 part F, unit ID3),
 * by the rules of upstream's `SharedMangaBottomActionMenu` as its code has them: Bookmark while any is
 * not bookmarked and Remove bookmark only while every one is; Mark as read while any is unread and
 * Mark as unread while any is read or partly read; Mark previous as read with exactly one selected;
 * Download while any is not downloaded and Delete while any is downloaded, both only where
 * [downloadsOffered] (not on a Local entry, whose chapters are files the owner put there).
 */
data class SelectionActions(
    val bookmark: Boolean,
    val removeBookmark: Boolean,
    val markRead: Boolean,
    val markUnread: Boolean,
    val markPreviousRead: Boolean,
    val download: Boolean,
    val delete: Boolean,
)

fun selectionActionsOf(
    selected: List<Chapter>,
    downloadOf: (Long) -> ChapterDownload,
    downloadsOffered: Boolean,
): SelectionActions = SelectionActions(
    bookmark = selected.any { !it.bookmark },
    removeBookmark = selected.isNotEmpty() && selected.all { it.bookmark },
    markRead = selected.any { !it.read },
    markUnread = selected.any { it.read || it.lastPageRead > 0L },
    markPreviousRead = selected.size == 1,
    download = downloadsOffered && selected.any { downloadOf(it.id) != ChapterDownload.Downloaded },
    delete = downloadsOffered && selected.any { downloadOf(it.id) == ChapterDownload.Downloaded },
)

/**
 * The bottom action bar the chapter list raises while rows are selected, upstream's
 * `MangaBottomActionMenu`: only the actions [actions] says apply are drawn, in upstream's order.
 *
 * It lives in its own file because the detail screen is long enough already.
 */
@Composable
fun MangaSelectionBar(
    actions: SelectionActions,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMarkPreviousRead: () -> Unit,
    onBookmark: () -> Unit,
    onRemoveBookmark: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actions.bookmark) SelectionAction(Icons.Outlined.BookmarkAdd, R.string.action_bookmark, onBookmark)
            if (actions.removeBookmark) {
                SelectionAction(Icons.Outlined.BookmarkRemove, R.string.action_remove_bookmark, onRemoveBookmark)
            }
            if (actions.markRead) SelectionAction(Icons.Outlined.Done, R.string.action_mark_as_read, onMarkRead)
            if (actions.markUnread) {
                SelectionAction(Icons.Outlined.RemoveDone, R.string.action_mark_as_unread, onMarkUnread)
            }
            if (actions.markPreviousRead) {
                SelectionAction(
                    Icons.AutoMirrored.Outlined.PlaylistAddCheck,
                    R.string.action_mark_previous_as_read,
                    onMarkPreviousRead,
                )
            }
            if (actions.download) SelectionAction(Icons.Outlined.Download, R.string.action_download, onDownload)
            if (actions.delete) SelectionAction(Icons.Outlined.Delete, R.string.action_delete_downloads, onDelete)
        }
    }
}

/** An icon only button whose label is its content description, since the bar has no room for text. */
@Composable
private fun SelectionAction(icon: ImageVector, @StringRes labelRes: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = stringResource(labelRes))
    }
}
