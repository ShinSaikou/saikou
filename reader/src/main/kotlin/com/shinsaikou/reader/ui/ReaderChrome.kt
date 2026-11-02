package com.shinsaikou.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.compose.foundation.layout.fillMaxSize
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.reader.viewer.NavigationOverlayRequest
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.ReaderNavigationOverlayView
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
    onOpenInWebView: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShareChapter: () -> Unit,
) {
    val cropBorders by preferences.cropBorders.collectAsState()
    val cropBordersWebtoon by preferences.cropBordersWebtoon.collectAsState()
    val verticalNavigator by preferences.verticalNavigator.collectAsState()
    val verticalNavigatorOnLeft by preferences.verticalNavigatorOnLeft.collectAsState()
    val verticalNavigatorHeight by preferences.verticalNavigatorHeight.collectAsState()

    // Upstream asks ReadingMode.isPagerType(pref); :domain's enum has isWebtoon and no ViewerType, so
    // the expression inverts. This is the reader's only use of it.
    val cropEnabled = if (state.readingMode.isWebtoon) cropBordersWebtoon else cropBorders

    // The membership test is by enum name: :domain stores the set as Set<String> where upstream stores
    // Set<ReadingMode>, and getEnumSet serialises each entry as it.name, so a restored backup
    // round-trips with no mapping (part 2 section 6.4).
    val chapterNavigatorType = when {
        verticalNavigator.contains(state.mangaReadingMode.name) ->
            if (verticalNavigatorOnLeft) ChapterNavigatorType.VERTICAL_LEFT else ChapterNavigatorType.VERTICAL_RIGHT
        state.readingMode == ReadingMode.RIGHT_TO_LEFT -> ChapterNavigatorType.HORIZONTAL_RTL
        else -> ChapterNavigatorType.HORIZONTAL_LTR
    }

    ReaderAppBars(
        visible = state.menuVisible,
        mangaTitle = state.manga?.displayTitle,
        chapterTitle = state.currentChapter?.chapter?.name,
        navigateUp = onNavigateUp,
        onClickTopAppBar = onNavigateUp,
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
            pref.set(!pref.get())
        },
        onClickSettings = { onEvent(ReaderEvent.ShowDialog(ReaderDialog.Settings)) },
        hasNote = state.currentNote != null,
        onClickNote = { onEvent(ReaderEvent.EditCurrentNote) },
    )
}

/**
 * The second and last `AndroidView`. It renders nothing until a viewer config raises
 * `setNavigationOverlay`, so it is always in the tree and always cheap.
 *
 * The view starts hidden, as upstream's layout declares it `gone`. A view built in code starts
 * visible, and `setNavigation` returns early for a visible view, so the tap regions were drawn on
 * every open until the first touch, whatever "Show tap zones overlay" said.
 */
@Composable
internal fun ReaderNavigationOverlay(
    request: NavigationOverlayRequest?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ReaderNavigationOverlayView(it, null).apply { isVisible = false } },
        update = { view ->
            request?.let { view.setNavigation(it.navigation, it.showOnStart) }
        },
    )
}
