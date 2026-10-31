package com.shinsaikou.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.util.ErrorText
import com.shinsaikou.ui.util.UpdateErrorGroup
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The pieces the Library tab and the Updates tab both show for the last library update run's
 * failures. One entry point, one notice, one list; the two screens differ only in what they host the list in.
 */

/** The top bar entry point to the last run's failures. Absent, not disabled, when there are none. */
@Composable
fun UpdateErrorAction(count: Int, onClick: () -> Unit) {
    if (count <= 0) return
    val description = stringResource(R.string.update_errors_count_format, count)
    BadgedBox(badge = { Badge { Text(count.toString()) } }) {
        IconButton(onClick = onClick) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = description)
        }
    }
}

/**
 * The rows both error surfaces show: a [ListGroupHeader] per source, then that source's entries,
 * each naming its failure, with the source's own message under it for one no key names.
 * [onMigrate] puts a Migrate action on each header, for moving a source's failed entries elsewhere
 * in one go (the usual way out when the source is gone or broken).
 */
@Composable
fun UpdateErrorList(
    groups: List<UpdateErrorGroup>,
    onRow: (Long) -> Unit,
    onMigrate: (UpdateErrorGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    LazyColumn(modifier = modifier) {
        groups.forEach { group ->
            // Keyed on the id, never the name, which two sources can share. The prefix keeps a
            // header key from ever equalling a row's, which is a bare manga id.
            item(key = "source-${group.sourceId}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ListGroupHeader(text = group.sourceName, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onMigrate(group) }) { Text(stringResource(R.string.migrate)) }
                }
            }
            items(group.errors, key = { it.mangaId }) { error ->
                ListRowLayout(onClick = { onRow(error.mangaId) }) {
                    ListRowTitle(error.title)
                    ListRowDetail(ErrorText.of(resources, error))
                    // It runs to 200 characters, more than two lines hold.
                    ErrorText.messageOf(error)?.let { ListRowDetail(it, maxLines = 4) }
                }
            }
        }
    }
}

/**
 * Above the list when the run stopped asking a site: how many entries went unchecked, why, and the
 * way to send fewer entries from one site at once. [onOpenLibrarySettings] opens More > Settings >
 * Library, where the Categories row after Automatic library update chooses what updates (F7).
 * Absent when [count] is zero. Inset like the list's headers, so the text lines up with the rows.
 */
@Composable
fun UpdateErrorSiteStoppedNotice(count: Int, onOpenLibrarySettings: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Column(modifier = modifier.padding(horizontal = MaterialTheme.padding.medium)) {
        Text(
            text = pluralStringResource(R.plurals.update_errors_site_stopped, count, count),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onOpenLibrarySettings, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.update_errors_open_library_settings))
        }
    }
}
