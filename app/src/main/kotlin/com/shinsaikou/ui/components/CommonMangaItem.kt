package com.shinsaikou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

/**
 * The three shapes a manga can take in a list: a cover with the title over it, a cover with the
 * title under it, and a row. Every library display mode is one of these, and the browse grids can
 * take them as they grow, which is why they live beside [MangaCover] rather than in one screen.
 *
 * Badges are slots rather than parameters. The library has four of them and browse has its own,
 * so the item lays out where a badge goes and the caller says what is in it. [coverAlpha] is how
 * browse dims an entry already in the library, as upstream's browse grids do; the library leaves it at 1.
 * A null long click leaves the gesture unassigned, which is where browse stands until it adds to the
 * library from a long press as upstream's does. A non-null `dragHandle` draws the library's drag handle
 * with that modifier (phase 4 unit MS): the modifier carries the drag, so the handle alone starts one
 * and the cell's tap and long press stay what they were.
 *
 * The geometry, text and selection follow upstream's `CommonMangaItem` so a library restored from it
 * looks the same: a selected grid cell sits on upstream's `secondary` outline with its cover faded and
 * its title in `onSecondary`, a selected row on the shared [selectedBackground]. The pieces are
 * this app's: [MangaCover] does the loading and the placeholder.
 */

/**
 * The cover with the title drawn over its bottom edge. [title] null gives the cover-only grid.
 * [coverDescription] is what a screen reader reads for the cover, the entry's title whether or not
 * [title] draws it, so a cover-only cell still names its entry (phase 4 unit BF, R118 carry-over 2).
 */
@Composable
fun MangaCompactGridItem(
    cover: Any?,
    title: String?,
    coverDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onContinueReading: (() -> Unit)? = null,
    coverAlpha: Float = 1f,
    badges: @Composable (RowScope.() -> Unit)? = null,
    dragHandle: Modifier? = null,
) {
    GridItemSelectable(isSelected = isSelected, onClick = onClick, onLongClick = onLongClick) {
        MangaGridCover(
            cover = cover,
            contentDescription = coverDescription,
            coverAlpha = if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha,
            badges = badges,
            dragHandle = dragHandle,
        ) {
            if (title != null) {
                // A gradient rather than a solid bar: a title over a light cover has to stay
                // readable without hiding the third of the art it sits on.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(COVER_TEXT_OVERLAY_FRACTION)
                        .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to SCRIM)),
                )
                Row(
                    modifier = Modifier.align(Alignment.BottomStart),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    GridItemTitle(
                        title = title,
                        modifier = Modifier.weight(1f).padding(8.dp),
                        color = Color.White,
                        shadow = Shadow(color = Color.Black, blurRadius = TITLE_SHADOW_BLUR),
                    )
                    if (onContinueReading != null) {
                        ContinueReadingButton(
                            size = CONTINUE_BUTTON_SMALL,
                            iconSize = CONTINUE_ICON_SMALL,
                            onClick = onContinueReading,
                            modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                        )
                    }
                }
            } else if (onContinueReading != null) {
                ContinueReadingButton(
                    size = CONTINUE_BUTTON_LARGE,
                    iconSize = CONTINUE_ICON_LARGE,
                    onClick = onContinueReading,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
        }
    }
}

/** The cover with the title under it, which is what makes a long title readable in full. */
@Composable
fun MangaComfortableGridItem(
    cover: Any?,
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onContinueReading: (() -> Unit)? = null,
    coverAlpha: Float = 1f,
    badges: @Composable (RowScope.() -> Unit)? = null,
    dragHandle: Modifier? = null,
) {
    GridItemSelectable(isSelected = isSelected, onClick = onClick, onLongClick = onLongClick) {
        Column {
            MangaGridCover(
                cover = cover,
                contentDescription = title,
                coverAlpha = if (isSelected) GRID_SELECTED_COVER_ALPHA else coverAlpha,
                badges = badges,
                dragHandle = dragHandle,
            ) {
                if (onContinueReading != null) {
                    ContinueReadingButton(
                        size = CONTINUE_BUTTON_LARGE,
                        iconSize = CONTINUE_ICON_LARGE,
                        onClick = onContinueReading,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    )
                }
            }
            GridItemTitle(title = title, modifier = Modifier.padding(4.dp), minLines = 2)
        }
    }
}

/** One row: a square cover, the title and its drag handle, then whatever badges the caller drew. */
@Composable
fun MangaRowItem(
    cover: Any?,
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onContinueReading: (() -> Unit)? = null,
    badges: @Composable (RowScope.() -> Unit)? = null,
    dragHandle: Modifier? = null,
) {
    Row(
        modifier = Modifier
            .selectedBackground(isSelected)
            .height(ROW_HEIGHT)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover(
            cover,
            modifier = Modifier.fillMaxHeight(),
            contentDescription = title,
            ratio = 1f,
        )
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp).weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (dragHandle != null) {
            // Upstream's Categories row idiom: the outlined handle icon, the only part of the row that
            // drags. Unlike upstream's, it tells a screen reader what it does.
            Box(modifier = dragHandle.size(DRAG_HANDLE_SIZE), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DragHandle, contentDescription = stringResource(R.string.library_reorder_handle))
            }
        }
        if (badges != null) BadgeGroup(content = badges)
        if (onContinueReading != null) {
            ContinueReadingButton(
                size = CONTINUE_BUTTON_SMALL,
                iconSize = CONTINUE_ICON_SMALL,
                onClick = onContinueReading,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The cover of a grid cell, with the badges pinned to its top corners and [content] free to draw
 * anywhere over it. Both grids share it so a badge sits in the same place in either. The drag
 * handle takes the top end corner, the one no badge or button uses.
 */
@Composable
private fun MangaGridCover(
    cover: Any?,
    contentDescription: String?,
    coverAlpha: Float,
    badges: @Composable (RowScope.() -> Unit)?,
    dragHandle: Modifier?,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(BOOK_RATIO)) {
        MangaCover(
            cover,
            modifier = Modifier.fillMaxWidth(),
            contentDescription = contentDescription,
            alpha = coverAlpha,
        )
        content()
        if (badges != null) {
            BadgeGroup(modifier = Modifier.align(Alignment.TopStart).padding(4.dp), content = badges)
        }
        if (dragHandle != null) {
            // On the scrim the title uses, so the icon reads over a light cover as over a dark one.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(SCRIM)
                    .then(dragHandle)
                    .size(DRAG_HANDLE_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = stringResource(R.string.library_reorder_handle),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun GridItemTitle(
    title: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    color: Color = Color.Unspecified,
    shadow: Shadow? = null,
) {
    Text(
        title,
        modifier = modifier,
        color = color,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleSmall.copy(shadow = shadow),
    )
}

/** The click surface and the selection outline, shared by both grids. */
@Composable
private fun GridItemSelectable(
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .selectedOutline(isSelected = isSelected, color = MaterialTheme.colorScheme.secondary)
            .padding(4.dp),
    ) {
        val contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondary else LocalContentColor.current
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/** Upstream's grid selection: the cell's padding filled in [color] behind the cover. */
private fun Modifier.selectedOutline(isSelected: Boolean, color: Color) =
    drawBehind { if (isSelected) drawRect(color = color) }

/**
 * The one control on a row that is not the row: it opens a chapter rather than the entry, so it is
 * a filled button with its own label instead of a bare icon on the cover.
 */
@Composable
private fun ContinueReadingButton(
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = MaterialTheme.shapes.small,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = CONTINUE_BUTTON_ALPHA),
            contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
        ),
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.action_continue_reading),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** The spacing both library grids use, so a cell is the same size in either. */
val GRID_SPACING = 4.dp

/**
 * A grid's cells from a stored column count: 0 is "fit as many as the width allows" at upstream's
 * adaptive cell width, which is about three columns on a phone held upright, and any other number
 * is that many columns exactly. The library and the browse grids read the same two keys through it.
 */
fun mangaGridCells(columns: Int): GridCells =
    if (columns == 0) GridCells.Adaptive(ADAPTIVE_COLUMN_WIDTH) else GridCells.Fixed(columns)

private val ADAPTIVE_COLUMN_WIDTH = 128.dp

private val ROW_HEIGHT = 56.dp
private val DRAG_HANDLE_SIZE = 36.dp
private val CONTINUE_BUTTON_SMALL = 28.dp
private val CONTINUE_BUTTON_LARGE = 32.dp
private val CONTINUE_ICON_SMALL = 16.dp
private val CONTINUE_ICON_LARGE = 20.dp
private const val CONTINUE_BUTTON_ALPHA = 0.9f
private const val GRID_SELECTED_COVER_ALPHA = 0.76f
private const val COVER_TEXT_OVERLAY_FRACTION = 0.33f
private const val TITLE_SHADOW_BLUR = 4f
private val SCRIM = Color(0xAA000000)
