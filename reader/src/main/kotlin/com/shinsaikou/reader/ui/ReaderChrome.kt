package com.shinsaikou.reader.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.compose.foundation.layout.fillMaxSize
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.reader.viewer.NavigationOverlayRequest
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationOverlayView
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.presentation.core.util.collectAsState

/**
 * The adapter between [ReaderState] and the vendored `eu.kanade.presentation.reader` composables.
 * Contract 07 part 2 section 5.5.
 *
 * It exists because section 2.3 changes those files' parameter lists, and something has to compute
 * the derived values they still take. Six of them come from preferences the state does not carry,
 * which is why [ReaderAppBars] keeps a flat parameter list (decision D20).
 */
@Composable
internal fun ReaderAppBarsHost(
    state: ReaderState,
    preferences: ReaderPreferences,
    chapterUrl: String?,
    onEvent: (ReaderEvent) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenEntry: () -> Unit,
    onOpenInWebView: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShareChapter: () -> Unit,
    onStartAutoScroll: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val cropToast = remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(Unit) { onDispose { cropToast.value?.cancel() } }
    val cropBorders by preferences.cropBorders.collectAsState()
    val cropBordersWebtoon by preferences.cropBordersWebtoon.collectAsState()
    val verticalNavigator by preferences.verticalNavigator.collectAsState()
    val verticalNavigatorOnLeft by preferences.verticalNavigatorOnLeft.collectAsState()
    val verticalNavigatorHeight by preferences.verticalNavigatorHeight.collectAsState()
    val stripAutoScroll by preferences.stripAutoScroll.collectAsState()

    // Upstream asks ReadingMode.isPagerType(pref); :domain's enum has isWebtoon and no ViewerType, so
    // the expression inverts. This is the reader's only use of it.
    val cropEnabled = if (state.readingMode.isWebtoon) cropBordersWebtoon else cropBorders

    val chapterNavigatorType = chapterNavigatorType(state.readingMode, verticalNavigator, verticalNavigatorOnLeft)

    ReaderAppBars(
        visible = state.menuVisible,
        mangaTitle = state.manga?.displayTitle,
        chapterTitle = state.currentChapter?.chapter?.name,
        navigateUp = onNavigateUp,
        // The title opens the entry's screen, as upstream's does; the arrow closes the reader.
        onClickTopAppBar = onOpenEntry,
        bookmarked = state.bookmarked,
        onToggleBookmarked = { onEvent(ReaderEvent.ToggleBookmark) },
        // Non-null only when the chapter has a web URL, which is how the reader expresses upstream's
        // "is this an HttpSource" test without being able to see HttpSource (part 2 section 7.1).
        onOpenInWebView = onOpenInWebView.takeIf { chapterUrl != null },
        onOpenInBrowser = onOpenInBrowser.takeIf { chapterUrl != null },
        onShare = onShareChapter.takeIf { chapterUrl != null },
        chapterNavigatorType = chapterNavigatorType,
        verticalNavigatorHeight = verticalNavigatorHeight / 100f,
        onNextChapter = { onEvent(ReaderEvent.NextChapter) },
        enabledNext = state.window?.nextChapter != null,
        onPreviousChapter = { onEvent(ReaderEvent.PreviousChapter) },
        enabledPrevious = state.window?.prevChapter != null,
        currentPage = state.currentPage + 1,
        totalPages = state.totalPages,
        onPageIndexChange = { onEvent(ReaderEvent.SliderMoved(it)) },
        onPageIndexRelease = { onEvent(ReaderEvent.SliderReleased(it)) },
        readingMode = state.readingMode,
        onClickReadingMode = { onEvent(ReaderEvent.ShowDialog(ReaderDialog.ReadingModeSelect)) },
        orientation = state.orientation,
        onClickOrientation = { onEvent(ReaderEvent.ShowDialog(ReaderDialog.OrientationSelect)) },
        cropEnabled = cropEnabled,
        onClickCropBorder = {
            val pref = if (state.readingMode.isWebtoon) preferences.cropBordersWebtoon else preferences.cropBorders
            val enabled = !pref.get()
            pref.set(enabled)
            // Upstream's On or Off, and only the latest: a second tap replaces the first's toast.
            cropToast.value?.cancel()
            cropToast.value = context.toast(resources.getString(if (enabled) R.string.state_on else R.string.state_off))
        },
        onClickSettings = { onEvent(ReaderEvent.ShowDialog(ReaderDialog.Settings)) },
        hasNote = state.currentNote != null,
        onClickNote = { onEvent(ReaderEvent.EditCurrentNote) },
        // Experimental, and only on the long strip, the one viewer that can run it. The menu closes
        // so the strip is in view; ReaderScreen stops the scroll when the menu opens, not while it
        // is still open from this tap.
        onClickAutoScroll = {
            onEvent(ReaderEvent.HideMenu)
            onStartAutoScroll()
        }.takeIf { stripAutoScroll && state.readingMode.isWebtoon },
    )
}

/**
 * The slider's place: vertical on the chosen side for a reading mode listed in "Use vertical chapter
 * navigator in", otherwise horizontal, right to left for that mode. [readingMode] is the mode in
 * use, as upstream tests its resolved mode: the entry's own is DEFAULT for most entries, which the
 * setting never offers, so the vertical navigator never showed for them.
 *
 * The membership test is by enum name: :domain stores the set as Set<String> where upstream stores
 * Set<ReadingMode>, and getEnumSet serialises each entry as it.name, so a restored backup
 * round-trips with no mapping (part 2 section 6.4).
 */
internal fun chapterNavigatorType(
    readingMode: ReadingMode,
    verticalModes: Set<String>,
    onLeft: Boolean,
): ChapterNavigatorType = when {
    readingMode.name in verticalModes ->
        if (onLeft) ChapterNavigatorType.VERTICAL_LEFT else ChapterNavigatorType.VERTICAL_RIGHT
    readingMode == ReadingMode.RIGHT_TO_LEFT -> ChapterNavigatorType.HORIZONTAL_RTL
    else -> ChapterNavigatorType.HORIZONTAL_LTR
}

/**
 * The second and last `AndroidView`. It renders nothing until a viewer config raises
 * `setNavigationOverlay`, so it is always in the tree and always cheap.
 *
 * The view starts hidden, as upstream's layout declares it `gone`. A view built in code starts
 * visible, and `setNavigation` returns early for a visible view, so the tap regions were drawn on
 * every open until the first touch, whatever "Show tap zones overlay" said.
 *
 * Each rise of [dismissals] hides the overlay through the view's `performClick`, the path a touch on
 * it takes. The Experimental auto-scroll raises it when it starts: the strip then moves with no
 * touch, and the tap zones sat over it for the whole run.
 */
@Composable
internal fun ReaderNavigationOverlay(
    request: NavigationOverlayRequest?,
    dismissals: Int,
    modifier: Modifier = Modifier,
) {
    // The count when the view was last brought up to date, so a recomposition for another reason
    // does not hide it again. It starts at the first value, which is no dismissal.
    val seen = remember { SeenDismissals(dismissals) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ReaderNavigationOverlayView(it, null).apply { isVisible = false } },
        update = { view ->
            request?.let { view.setNavigation(it.navigation, it.showOnStart) }
            if (dismissals != seen.count) {
                seen.count = dismissals
                view.performClick()
            }
        },
    )
}

/** The dismissal count [ReaderNavigationOverlay] has acted on. */
private class SeenDismissals(var count: Int)
