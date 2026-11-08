package com.shinsaikou.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.components.CategoryCheck
import com.shinsaikou.ui.components.ChangeCategoryDialog
import com.shinsaikou.ui.components.DownloadAction
import com.shinsaikou.ui.components.DownloadDropdownMenu
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.selectionTopBarColors
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The library's selection chrome: the bar the top bar becomes while entries are selected, and the
 * two dialogs those actions open.
 *
 * It lives beside [LibraryScreen] rather than inside it because the screen already carries the
 * list, the search field and the pull to refresh, and a bar with six actions on it would bury them.
 */

/**
 * The bar shown while [count] entries are selected. It is [ScreenTopBar] rather than a second
 * `TopAppBar` so the height and the scroll behaviour match the bar it replaces and the swap does
 * not shift the list underneath; the back arrow leaves the selection, which is what the back
 * gesture does too.
 *
 * Only the two read actions get an icon. A phone bar fits three actions and a title, and the count
 * is the part that has to stay readable, so the destructive and the category action sit in the
 * overflow where they also cannot be hit by a thumb aiming for the one beside them.
 *
 * [onDownload] is upstream's library download menu (phase 3 unit AD): its Download item opens the six
 * choices, and it is null, so the item is not offered, while the selection holds a Local entry.
 *
 * [onMigrate] is upstream's library Migrate (phase 4 unit MB), after Download and before Remove as in
 * upstream's bottom menu, offered whenever the bar is, Local entries included.
 *
 * [onUpdate] runs a library update over the selected entries only, whatever their categories.
 *
 * [onMerge] opens the merge dialog, and is null unless Merged entries is on and two or more entries
 * are selected.
 */
@Composable
internal fun LibrarySelectionBar(
    count: Int,
    scrollBehavior: TopAppBarScrollBehavior?,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMoveToCategory: () -> Unit,
    onRemove: () -> Unit,
    onDownload: ((DownloadAction) -> Unit)?,
    onMigrate: () -> Unit,
    onUpdate: () -> Unit,
    onMerge: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var downloadMenuOpen by remember { mutableStateOf(false) }
    ScreenTopBar(
        title = stringResource(R.string.selection_count_format, count),
        onBack = onClearSelection,
        scrollBehavior = scrollBehavior,
        colors = selectionTopBarColors(),
        actions = {
            IconButton(onClick = onMarkRead) {
                Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.action_mark_as_read))
            }
            IconButton(onClick = onMarkUnread) {
                Icon(Icons.Outlined.RemoveDone, contentDescription = stringResource(R.string.action_mark_as_unread))
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.action_menu_overflow_description),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_to_category)) },
                    onClick = {
                        menuOpen = false
                        onMoveToCategory()
                    },
                )
                if (onDownload != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_download)) },
                        onClick = {
                            menuOpen = false
                            downloadMenuOpen = true
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_update)) },
                    onClick = {
                        menuOpen = false
                        onUpdate()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.migrate)) },
                    onClick = {
                        menuOpen = false
                        onMigrate()
                    },
                )
                if (onMerge != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_merge)) },
                        onClick = {
                            menuOpen = false
                            onMerge()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_from_library)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_select_all)) },
                    onClick = {
                        menuOpen = false
                        onSelectAll()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_invert_selection)) },
                    onClick = {
                        menuOpen = false
                        onInvertSelection()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_deselect_all)) },
                    onClick = {
                        menuOpen = false
                        onClearSelection()
                    },
                )
            }
            if (onDownload != null) {
                DownloadDropdownMenu(
                    expanded = downloadMenuOpen,
                    onDismissRequest = { downloadMenuOpen = false },
                    onDownload = onDownload,
                )
            }
        },
    )
}

/**
 * Renders whichever dialog the view model is asking for, or nothing.
 *
 * Removing entries asks first. Everything else a selection can do is recoverable by doing it
 * again, and dropping a shelf of entries out of the library is not.
 *
 * The settings sheet is one of them because it is the same thing: a modal the state asks for, so
 * whether it is open survives a rotation with everything else in [LibraryViewModel.State].
 */
@Composable
internal fun LibraryDialogs(
    state: LibraryViewModel.State,
    preferences: LibraryPreferences,
    onDismissRequest: () -> Unit,
    onConfirmCategories: (List<CategoryCheck>) -> Unit,
    onConfirmRemove: () -> Unit,
    onConfirmMerge: (Long) -> Unit,
    onFilter: (LibraryViewModel.LibraryFilter, TriState) -> Unit,
    onTrackerFilter: (Long, TriState) -> Unit,
    onSort: (LibrarySort.Type, LibrarySort.Direction) -> Unit,
    onDisplayMode: (LibraryDisplayMode) -> Unit,
    onColumns: (Int) -> Unit,
) {
    when (val dialog = state.dialog) {
        LibraryViewModel.Dialog.SettingsSheet -> LibrarySettingsSheet(
            state = state,
            preferences = preferences,
            onDismissRequest = onDismissRequest,
            onFilter = onFilter,
            onTrackerFilter = onTrackerFilter,
            onSort = onSort,
            onDisplayMode = onDisplayMode,
            onColumns = onColumns,
        )
        is LibraryViewModel.Dialog.ChangeCategory -> ChangeCategoryDialog(
            rows = dialog.rows,
            onDismissRequest = onDismissRequest,
            onConfirm = onConfirmCategories,
        )
        is LibraryViewModel.Dialog.RemoveSelected -> AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.library_remove_selected_title)) },
            text = {
                val message = stringResource(R.string.library_remove_selected_message_format, dialog.count)
                if (dialog.mergedTitles > 0) {
                    Text("$message ${stringResource(R.string.merged_remove_selected_note)}")
                } else {
                    Text(message)
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmRemove) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        is LibraryViewModel.Dialog.MergeSelected -> MergeEntriesDialog(
            entries = dialog.entries,
            onDismissRequest = onDismissRequest,
            onConfirm = onConfirmMerge,
        )
        null -> Unit
    }
}

/**
 * The library's Merge: the selected entries in the order they were selected, which becomes the
 * order of their chapters, and a pick of the main entry, the one that stands for the title. The
 * first selected is the main entry until another is picked.
 */
@Composable
private fun MergeEntriesDialog(
    entries: List<LibraryViewModel.MergeChoice>,
    onDismissRequest: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var main by rememberSaveable(entries) { mutableLongStateOf(entries.first().mangaId) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.merged_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.merged_dialog_message))
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = entry.mangaId == main,
                                    onClick = { main = entry.mangaId },
                                    role = Role.RadioButton,
                                )
                                .defaultMinSize(minHeight = 48.dp)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = entry.mangaId == main, onClick = null)
                            Column {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                                Text(
                                    entry.sourceName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(main) }) { Text(stringResource(R.string.action_merge)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
