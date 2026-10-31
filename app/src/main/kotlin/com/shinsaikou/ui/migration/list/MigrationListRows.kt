package com.shinsaikou.ui.migration.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.browse.source.InLibraryBadge
import com.shinsaikou.ui.components.BOOK_RATIO
import com.shinsaikou.ui.components.CoverPlaceholderColor
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.migration.list.MigratingManga.SearchResult
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DecimalFormat

/**
 * Upstream's migration list rows (phase 4 unit MB), from `MigrationListScreenContent.kt`: an entry's
 * cover with its title over it and its chapter count, its source and its highest chapter, and the
 * same for the match.
 *
 * Saikou: [inLibrary] adds the browse listing's in-library mark to the cover's badges (M5). Saikou has
 * no error cover drawable, so an entry without a match draws the broken-image glyph on the cover
 * placeholder's colour and corner, as upstream's `cover_error` draws it on its placeholder.
 */
@Composable
internal fun MigrationListItem(
    modifier: Modifier,
    manga: Manga,
    source: String,
    chapterCount: Int,
    latestChapter: Double?,
    inLibrary: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .widthIn(max = 150.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BOOK_RATIO),
        ) {
            MangaCover(
                data = manga,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    )
                    .fillMaxHeight(0.4f)
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
            Text(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.BottomStart),
                text = manga.displayTitle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                style = MaterialTheme.typography.labelMedium,
            )
            BadgeGroup(modifier = Modifier.padding(4.dp)) {
                if (inLibrary) InLibraryBadge()
                Badge(text = "$chapterCount")
            }
        }

        Column(
            modifier = Modifier.padding(MaterialTheme.padding.extraSmall),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = source,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
            )
            val formattedLatestChapter = remember(latestChapter) {
                latestChapter?.let { DecimalFormat("#.###").format(it) }
            }
            Text(
                text = stringResource(
                    R.string.migrationListScreen_latestChapterLabel,
                    formattedLatestChapter ?: stringResource(R.string.migrationListScreen_unknownLatestChapter),
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun MigrationListItemResult(
    modifier: Modifier,
    result: SearchResult,
    favoriteIds: Set<Long>,
    onItemClick: (Manga) -> Unit,
) {
    Box(modifier.height(IntrinsicSize.Min)) {
        when (result) {
            SearchResult.Searching -> {
                Box(
                    modifier = Modifier
                        .widthIn(max = 150.dp)
                        .fillMaxSize()
                        .aspectRatio(BOOK_RATIO),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            SearchResult.NotFound -> {
                Column(
                    Modifier
                        .widthIn(max = 150.dp)
                        .fillMaxSize()
                        .padding(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(BOOK_RATIO)
                            // `MangaCover`'s own placeholder: its corner and its colour.
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(CoverPlaceholderColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.migrationListScreen_noMatchFoundText),
                        modifier = Modifier.padding(MaterialTheme.padding.extraSmall),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            is SearchResult.Success -> {
                MigrationListItem(
                    modifier = Modifier.fillMaxSize(),
                    manga = result.manga,
                    source = result.source,
                    chapterCount = result.chapterCount,
                    latestChapter = result.latestChapter,
                    inLibrary = result.manga.id in favoriteIds,
                    onClick = { onItemClick(result.manga) },
                )
            }
        }
    }
}

/**
 * The row's last slot: while searching, a close icon that removes the entry and cancels its search;
 * once resolved, upstream's menu. Saikou: while the row's own write runs ([writing]), a small spinner and
 * no menu (M7).
 */
@Composable
internal fun MigrationListItemAction(
    modifier: Modifier,
    result: SearchResult,
    writing: Boolean,
    onSearchManually: () -> Unit,
    onSkip: () -> Unit,
    onMigrate: () -> Unit,
    onCopy: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val closeMenu = { menuExpanded = false }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            writing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
            result == SearchResult.Searching -> {
                IconButton(onClick = onSkip) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.migrationListScreen_skipActionLabel),
                    )
                }
            }
            else -> {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_menu),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = closeMenu,
                    offset = DpOffset(8.dp, (-56).dp),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.migrationListScreen_searchManuallyActionLabel)) },
                        onClick = {
                            closeMenu()
                            onSearchManually()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.migrationListScreen_skipActionLabel)) },
                        onClick = {
                            closeMenu()
                            onSkip()
                        },
                    )
                    if (result is SearchResult.Success) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.migrationListScreen_migrateNowActionLabel)) },
                            onClick = {
                                closeMenu()
                                onMigrate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.migrationListScreen_copyNowActionLabel)) },
                            onClick = {
                                closeMenu()
                                onCopy()
                            },
                        )
                    }
                }
            }
        }
    }
}
