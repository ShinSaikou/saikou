package com.shinsaikou.ui.library

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.components.Badge

/**
 * The corner of a library row that says what is in the entry: unread count, downloaded count,
 * whether it is a local entry, and which language its source speaks.
 *
 * Each badge is drawn only when the owner switched it on and it has something to say, which the
 * view model has already decided: a null count here means "do not draw", never "zero". The colours
 * and the Local mark are upstream's `LibraryBadges`: unread on `secondary`, downloaded on `tertiary`,
 * the Local entry as upstream's folder icon on `tertiary`, the language on `tertiary`.
 *
 * Every badge carries its own content description because the text on it is a bare number. A screen
 * reader announcing "7" beside a title says nothing; "7 unread" is the badge.
 */
@Composable
internal fun RowScope.LibraryBadges(item: LibraryViewModel.LibraryItemUi) {
    val resources = LocalResources.current
    item.unreadBadge?.let { count ->
        Badge(
            text = "$count",
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = resources.getString(R.string.library_badge_unread, count.toInt())
            },
            color = MaterialTheme.colorScheme.secondary,
            textColor = MaterialTheme.colorScheme.onSecondary,
        )
    }
    item.downloadBadge?.let { count ->
        Badge(
            text = "$count",
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = resources.getString(R.string.library_badge_downloaded, count.toInt())
            },
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
    if (item.localBadge) {
        // Upstream's icon draws with no description; the badge names itself, as the counts do.
        Badge(
            imageVector = Icons.Outlined.Folder,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = resources.getString(R.string.library_badge_local)
            },
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
    item.languageBadge?.let { language ->
        Badge(
            text = language.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}
