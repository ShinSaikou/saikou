package com.shinsaikou.ui.chapter

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.shinsaikou.i18n.R
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.tachiyomi.util.lang.toLocalDate
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import com.shinsaikou.R as AppR

/*
 * The pieces a chapter row is drawn from, shared by the entry screen's rows and the Updates rows
 * (`design/plans/phase5-start.md` 2.6, unit ID3): upstream's row (`MangaChapterListItem`,
 * `UpdatesUiItem`, `ChapterDownloadIndicator`) for what is shown and what a tap does, and for the
 * colours and alphas it draws in. The rows themselves sit on `ListRowBody`, with no divider.
 */

/** The alpha a chapter row draws its text at: upstream's `DISABLED_ALPHA` once the chapter is read. */
fun chapterTextAlpha(read: Boolean): Float = if (read) DISABLED_ALPHA else 1f

/**
 * The alpha [ChapterMarkedText] drew its text at, on its semantics node, so a device test asserts
 * the value a read row is faded by rather than reading pixels. Nothing in the app reads it.
 */
val ChapterTextAlphaKey = SemanticsPropertyKey<Float>("ChapterTextAlpha")
var SemanticsPropertyReceiver.chapterTextAlpha by ChapterTextAlphaKey

/**
 * One line of a chapter row with the chapter's marks before it: a `primary` dot while unread
 * (described "Unread", upstream's description) and the filled bookmark in `primary` when bookmarked
 * (described "Bookmarked"), sized to the line. The text is one line, ellipsised, in [color], faded
 * by [chapterTextAlpha] once read. The entry screen puts the marks on its title line, Updates on its
 * chapter line, as upstream's two rows do.
 */
@Composable
fun ChapterMarkedText(
    text: String,
    style: TextStyle,
    color: Color,
    read: Boolean,
    bookmark: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = chapterTextAlpha(read)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MARK_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!read) {
            Icon(
                Icons.Filled.Circle,
                contentDescription = stringResource(R.string.chapter_unread),
                modifier = Modifier.size(UNREAD_DOT_SIZE),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (bookmark) {
            // Upstream sizes the icon to the measured text less 2 dp; the style's line height is the
            // same number without a second layout pass.
            val line = with(LocalDensity.current) {
                (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize).toDp()
            }
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = stringResource(R.string.action_filter_bookmarked),
                modifier = Modifier.size(line - BOOKMARK_INSET),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false).semantics { chapterTextAlpha = alpha },
            style = style,
            color = color.copy(alpha = color.alpha * alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The line under a chapter row's title: `bodySmall` in the content colour at upstream's
 * `SECONDARY_ALPHA`, or its `DISABLED_ALPHA` once read, one line.
 */
@Composable
fun ChapterDetailText(text: String, read: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current.copy(alpha = if (read) DISABLED_ALPHA else SECONDARY_ALPHA),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Upstream's upload date for a chapter row: [relativeDateText] with the two date settings, and "N/A"
 * for a chapter the source gave no date (upstream's `relativeDateText(0L)`).
 */
@Composable
fun chapterUploadDate(dateUpload: Long, relativeTime: Boolean, dateFormat: String): String =
    if (dateUpload > 0L) {
        relativeDateText(dateUpload.toLocalDate(), relativeTime, dateFormat)
    } else {
        stringResource(R.string.not_applicable)
    }

/** Upstream's "Page: N" for a chapter read part of the way, else null. */
@Composable
fun chapterProgress(read: Boolean, lastPageRead: Long): String? =
    if (!read && lastPageRead > 0L) stringResource(R.string.chapter_progress, lastPageRead + 1) else null

/**
 * The row's download control, upstream's `ChapterDownloadIndicator`: its taps ([tap]) and its drawing.
 * Not downloaded is upstream's `ic_download_chapter_24dp` in `onSurfaceVariant` at its secondary
 * alpha; queued, or downloading before the first page is written, an indeterminate ring; downloading
 * a pie filling with the pages written, the arrow over it turning to the background colour past
 * half; downloaded upstream's filled check circle in `onSurfaceVariant`; failed the error icon in
 * `error`. Each is 26 dp, the rings inset 2 dp with a 2 dp stroke, in upstream's 40 dp target,
 * described by the state, so a screen reader and a test read what the control draws.
 */
@Composable
fun ChapterDownloadControl(
    download: ChapterDownload,
    onAction: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf<List<ChapterDownloadAction>>(emptyList()) }
    val haptics = LocalHapticFeedback.current
    val description = chapterDownloadDescription(download)
    fun handle(longPress: Boolean) {
        when (val tap = download.tap(longPress)) {
            is ChapterDownloadTap.Run -> onAction(tap.action)
            is ChapterDownloadTap.Menu -> menu = tap.actions
        }
    }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = IconButtonTokens.StateLayerSize / 2),
                role = Role.Button,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    handle(longPress = true)
                },
                onClick = { handle(longPress = false) },
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ChapterDownloadDrawing(download)
        DropdownMenu(expanded = menu.isNotEmpty(), onDismissRequest = { menu = emptyList() }) {
            menu.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(menuLabel(action))) },
                    onClick = {
                        menu = emptyList()
                        onAction(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChapterDownloadDrawing(download: ChapterDownload) {
    val stroke = MaterialTheme.colorScheme.onSurfaceVariant
    when (download) {
        ChapterDownload.None -> Icon(
            painter = painterResource(AppR.drawable.ic_download_chapter_24dp),
            contentDescription = null,
            modifier = Modifier.size(INDICATOR_SIZE).secondaryItemAlpha(),
            tint = stroke,
        )
        ChapterDownload.Queued, is ChapterDownload.Downloading -> {
            val progress = (download as? ChapterDownload.Downloading)
                ?.takeIf { it.total > 0 && it.pages > 0 }
                ?.let { it.pages.toFloat() / it.total }
            val arrow = if (progress == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(INDICATOR_SIZE).padding(INDICATOR_PADDING),
                    color = stroke,
                    strokeWidth = INDICATOR_PADDING,
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Butt,
                )
                stroke
            } else {
                val animated by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    label = "progress",
                )
                CircularProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.size(INDICATOR_SIZE).padding(INDICATOR_PADDING),
                    color = stroke,
                    strokeWidth = INDICATOR_SIZE / 2,
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                )
                if (animated < 0.5f) stroke else MaterialTheme.colorScheme.background
            }
            Icon(
                Icons.Outlined.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(INDICATOR_SIZE - ARROW_INSET),
                tint = arrow,
            )
        }
        ChapterDownload.Downloaded -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(INDICATOR_SIZE),
            tint = stroke,
        )
        ChapterDownload.Failed -> Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(INDICATOR_SIZE),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/** What the control says it is: the action for a chapter not downloaded, else its state. */
@Composable
private fun chapterDownloadDescription(download: ChapterDownload): String = when (download) {
    ChapterDownload.None -> stringResource(R.string.action_download)
    ChapterDownload.Queued -> stringResource(R.string.chapter_download_queued)
    is ChapterDownload.Downloading -> if (download.total > 0) {
        stringResource(R.string.chapter_download_progress_format, download.pages, download.total)
    } else {
        stringResource(R.string.chapter_download_starting)
    }
    ChapterDownload.Downloaded -> stringResource(R.string.chapter_downloaded)
    ChapterDownload.Failed -> stringResource(R.string.chapter_download_failed)
}

private fun menuLabel(action: ChapterDownloadAction): Int = when (action) {
    ChapterDownloadAction.START -> R.string.action_download
    ChapterDownloadAction.START_NOW -> R.string.action_start_downloading_now
    ChapterDownloadAction.CANCEL -> R.string.action_cancel
    ChapterDownloadAction.DELETE -> R.string.action_delete
}

private val UNREAD_DOT_SIZE = 8.dp
private val MARK_GAP = 4.dp

/** Upstream's bookmark is the text's height less this. */
private val BOOKMARK_INSET = 2.dp

/** Upstream's indicator: 26 dp, its rings inset and stroked 2 dp, its arrow 7 dp smaller. */
private val INDICATOR_SIZE = 26.dp
private val INDICATOR_PADDING = 2.dp
private val ARROW_INSET = 7.dp
