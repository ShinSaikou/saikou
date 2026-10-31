package com.shinsaikou.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.presentation.core.components.SolveBanner
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.ReaderState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether the person in the reader is waiting on the network right now: the loading dialog is up,
 * an adjacent chapter is being fetched, or the page they are on has not arrived.
 *
 * This is the whole scoping rule for the reader's banner. The coordinator's map is fed by every
 * request in the process, a background library update included (and, from Phase 2, downloads), so
 * a banner shown whenever the map is non-empty would tick over a page someone is reading for a
 * request nobody on screen asked for.
 *
 * @param currentPageStatus the status of the page at `state.currentPage`, or null when the chapter
 *   has no page list yet or the page is not in it.
 */
internal fun ReaderState.isWaitingOnContent(currentPageStatus: PageState?): Boolean {
    if (phase is ReaderPhase.Failed) return false
    if (dialog == ReaderDialog.Loading || loadingAdjacent) return true
    if (window == null) return true
    return when (currentPageStatus) {
        null, PageState.Queue, PageState.LoadPage, PageState.DownloadImage -> true
        PageState.Ready, is PageState.Error -> false
    }
}

/**
 * The shared [SolveBanner], composed only while [isWaitingOnContent] holds. Nothing is collected
 * and nothing ticks otherwise, so the viewer paths the jank rows time pay nothing for it. No action
 * button: the reader has no route into the app's WebView screen.
 */
@Composable
internal fun ReaderSolveBanner(
    state: ReaderState,
    coordinator: CloudflareSolveCoordinator,
    modifier: Modifier = Modifier,
) {
    val page = state.currentChapter?.pages?.firstOrNull { it.index == state.currentPage }
    val status by (page?.statusFlow ?: NO_PAGE).collectAsState()
    if (!state.isWaitingOnContent(status)) return

    val inFlight by coordinator.inFlight.collectAsStateWithLifecycle()
    SolveBanner(inFlight = inFlight, modifier = modifier)
}

private val NO_PAGE = MutableStateFlow<PageState?>(null)
