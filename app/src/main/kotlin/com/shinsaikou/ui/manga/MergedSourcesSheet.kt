package com.shinsaikou.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The sheet behind a merged title's source line (R163): the sources in chapter order, dragged to set
 * which one wins a chapter number, each with its own menu; then Show every copy, Unmerge, and what
 * sync and backups do with a merge.
 *
 * A drag moves a copy held here and writes once, on the drop, as the library's manual sort does. The
 * copy starts over from [title] whenever a write comes back.
 */
@Composable
internal fun MergedSourcesSheet(
    title: MangaDetailViewModel.MergedTitle,
    onDismissRequest: () -> Unit,
    onReorder: (List<Long>) -> Unit,
    onUseAsMain: (Long) -> Unit,
    onCheckUpdates: (Long, Boolean) -> Unit,
    onRemove: (Long) -> Unit,
    onShowEveryCopy: (Boolean) -> Unit,
    onUnmerge: () -> Unit,
) {
    var order by remember(title.sources) { mutableStateOf(title.sources) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply {
            add(indexOfFirst { it.mangaId == to.key }, removeAt(indexOfFirst { it.mangaId == from.key }))
        }
    }
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.merged_sources_title),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), state = listState) {
                items(order, key = { it.mangaId }) { source ->
                    ReorderableItem(reorderState, key = source.mangaId) {
                        MergedSourceRow(
                            source = source,
                            scope = this,
                            onDragStopped = { onReorder(order.map { it.mangaId }) },
                            onUseAsMain = { onUseAsMain(source.mangaId) },
                            onCheckUpdates = { onCheckUpdates(source.mangaId, it) },
                            onRemove = { onRemove(source.mangaId) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = title.showAll, role = Role.Switch, onValueChange = onShowEveryCopy)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.merged_show_every_copy), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.merged_show_every_copy_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = title.showAll, onCheckedChange = null)
            }
            TextButton(onClick = onUnmerge, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.merged_unmerge))
            }
            Text(
                stringResource(R.string.merged_sources_note),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MergedSourceRow(
    source: MangaDetailViewModel.MergedSource,
    scope: ReorderableCollectionItemScope,
    onDragStopped: () -> Unit,
    onUseAsMain: () -> Unit,
    onCheckUpdates: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.DragHandle,
            contentDescription = null,
            modifier = with(scope) { Modifier.draggableHandle(onDragStopped = { onDragStopped() }) },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (source.isMain) {
                    "${source.sourceName} · ${stringResource(R.string.merged_source_main)}"
                } else {
                    source.sourceName
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${source.title} · " +
                    pluralStringResource(R.plurals.manga_num_chapters, source.chapterCount, source.chapterCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MergedSourceMenu(source, onUseAsMain, onCheckUpdates, onRemove)
    }
}

/** The switch stays in the open menu, which reads the row again when the write comes back. */
@Composable
private fun MergedSourceMenu(
    source: MangaDetailViewModel.MergedSource,
    onUseAsMain: () -> Unit,
    onCheckUpdates: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_menu_overflow_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!source.isMain) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.merged_use_as_main)) },
                    onClick = {
                        expanded = false
                        onUseAsMain()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.merged_check_updates)) },
                trailingIcon = { Switch(checked = source.checkUpdates, onCheckedChange = onCheckUpdates) },
                onClick = { onCheckUpdates(!source.checkUpdates) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_remove)) },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}
