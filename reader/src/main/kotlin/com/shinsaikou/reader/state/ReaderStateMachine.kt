package com.shinsaikou.reader.state

import android.content.ComponentCallbacks2
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.manga.model.readerOrientation
import com.shinsaikou.domain.manga.model.readingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.pipeline.PageState
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * The reader's whole logic, as one pure reducer. Contract 07 section 3.3.
 *
 * [reduce] takes a state and an event and returns the next state plus the effects the view model
 * and the shell must perform. It touches no Android API, no database and no coroutine. The
 * constructor parameters are suppliers rather than values because every answer can change during a
 * session: the long-tap and show reading mode preferences are toggled from the settings sheet inside
 * the reader, and the preload window is recomputed after a trim. Section 3.3 writes the first as a
 * plain `longTapEnabled: Boolean` constructor flag; a supplier keeps the reducer just as pure and
 * keeps the settings sheet working without rebuilding the machine.
 *
 * The one deliberate impurity is [ReaderChapter.replacePages] on a `PageSplit`, which section 3.3
 * specifies: the page list lives on the mutable chapter, not in the state.
 */
class ReaderStateMachine(
    private val longTapEnabled: () -> Boolean,
    private val preloadWindow: () -> PreloadPolicy.Window,
    private val showReadingMode: () -> Boolean,
) {

    fun reduce(state: ReaderState, event: ReaderEvent): Pair<ReaderState, List<ReaderEffect>> =
        if (state.phase is ReaderPhase.Failed) closing(state, event) else running(state, event)

    @Suppress("CyclomaticComplexMethod")
    private fun running(state: ReaderState, event: ReaderEvent): Pair<ReaderState, List<ReaderEffect>> = when (event) {
        is ReaderEvent.SessionLoaded -> sessionLoaded(state, event)
        // Also sent when the entry's row leaves an open session (phase 4 unit BF), so whatever
        // progress is still waiting for its write goes unwritten.
        is ReaderEvent.SessionFailed -> state.copy(phase = ReaderPhase.Failed(event.error)) to
            listOf(ReaderEffect.DiscardProgress, ReaderEffect.Finish(event.error, event.message))
        is ReaderEvent.MangaChanged -> modeChange(
            state = state.copy(
                manga = event.manga,
                mangaReadingMode = event.manga.readingMode,
                mangaOrientation = event.manga.readerOrientation,
            ),
            mode = event.effectiveMode,
            orientation = event.effectiveOrientation,
        )
        is ReaderEvent.PreferencesChanged -> modeChange(state, event.effectiveMode, event.effectiveOrientation)

        is ReaderEvent.ChapterLoaded -> chapterLoaded(state, event)
        is ReaderEvent.ChapterLoadFailed -> chapterLoadFailed(state, event)
        is ReaderEvent.AdjacentChapterLoaded -> adjacentChapterLoaded(state, event)
        is ReaderEvent.AdjacentChapterLoadFailed -> state.noEffects()

        is ReaderEvent.PageSelected -> pageSelected(state, event.page)
        is ReaderEvent.TransitionSelected -> transitionSelected(state, event.transition)
        is ReaderEvent.PreloadRequested ->
            if (event.chapter.isPreloadable()) {
                state to listOf(ReaderEffect.PreloadChapter(event.chapter))
            } else {
                state.noEffects()
            }
        is ReaderEvent.PageLongTapped -> if (longTapEnabled()) {
            state.copy(dialog = ReaderDialog.PageActions(event.page)).noEffects()
        } else {
            state.noEffects()
        }
        is ReaderEvent.PageSplit -> pageSplit(state, event.page, event.parts)

        ReaderEvent.ToggleMenu -> state.copy(menuVisible = !state.menuVisible).noEffects()
        ReaderEvent.ShowMenu -> state.copy(menuVisible = true).noEffects()
        ReaderEvent.HideMenu -> state.copy(menuVisible = false).noEffects()
        is ReaderEvent.SliderMoved -> state.copy(
            scrubbing = true,
            scrubStartPage = if (state.scrubbing) state.scrubStartPage else state.currentPage,
            currentPage = event.index,
        ).noEffects()
        is ReaderEvent.SliderReleased -> sliderReleased(state, event.index)
        ReaderEvent.NextChapter -> switchChapter(state, state.window?.nextChapter)
        ReaderEvent.PreviousChapter -> switchChapter(state, state.window?.prevChapter)
        ReaderEvent.ToggleBookmark -> toggleBookmark(state)
        is ReaderEvent.BookmarkWritten ->
            if (state.currentChapter?.chapter?.id == event.chapterId) {
                state.copy(bookmarked = event.bookmark).noEffects()
            } else {
                state.noEffects()
            }
        is ReaderEvent.SetReadingMode -> state.manga
            ?.let { state.copy(mangaReadingMode = event.mode) to listOf(ReaderEffect.WriteReadingMode(it.id, event.mode)) }
            ?: state.noEffects()
        is ReaderEvent.SetOrientation -> state.manga
            ?.let {
                state.copy(mangaOrientation = event.orientation) to
                    listOf(ReaderEffect.WriteOrientation(it.id, event.orientation))
            }
            ?: state.noEffects()
        is ReaderEvent.ShowDialog -> state.copy(dialog = event.dialog).noEffects()
        ReaderEvent.DismissDialog -> state.copy(dialog = null).noEffects()
        is ReaderEvent.BrightnessChanged ->
            state.copy(brightnessOverlayValue = event.value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)).noEffects()

        // A page's own index: a part of a split page carries its parent's, so its note is the
        // source page's. Its own chapter too, which is the current one except on a long press at a
        // chapter boundary of the long strip. The page sheet it came from is closed here rather
        // than by a dismiss from the sheet, which could land after the note dialog had opened.
        is ReaderEvent.EditNote -> state.copy(dialog = null) to
            listOf(ReaderEffect.OpenNoteEditor(event.page.chapter.chapter.id, event.page.index))
        ReaderEvent.EditCurrentNote -> state.currentChapter
            ?.takeIf { state.currentPage >= 0 }
            ?.let { state to listOf(ReaderEffect.OpenNoteEditor(it.chapter.id, state.currentPage)) }
            ?: state.noEffects()
        is ReaderEvent.NoteTextChanged -> if (state.dialog is ReaderDialog.PageNote) {
            state to listOf(ReaderEffect.KeepNoteText(event.text))
        } else {
            state.noEffects()
        }
        is ReaderEvent.SaveNote -> state.copy(dialog = null) to listOf(
            if (event.text.isBlank()) {
                ReaderEffect.RemoveNote(event.chapterId, event.pageIndex)
            } else {
                ReaderEffect.WriteNote(event.chapterId, event.pageIndex, event.text)
            },
        )
        is ReaderEvent.NotesChanged ->
            if (state.currentChapter?.chapter?.id == event.chapterId) {
                state.copy(pageNotes = PageNotes(event.chapterId, event.byPage)).noEffects()
            } else {
                state.noEffects()
            }

        ReaderEvent.Resumed -> state.currentChapter
            ?.let { state to listOf(ReaderEffect.RestartReadTimer(it)) }
            ?: state.noEffects()
        ReaderEvent.Paused -> state.currentChapter
            ?.let { state to leaveChapterEffects(state, it) }
            ?: state.noEffects()
        // No ReaderEffect.Finish here. Finishing is only ever sent by ReaderActivity.finish(), so
        // the activity is already on its way out by the time this arrives, and answering it with
        // the effect that calls finish() again closed a cycle: finish, Finishing, Finish, finish.
        // Each turn launched another NonCancellable history write, and the reader spun until the
        // process ran out of heap. Finish stays for the error paths, which decide to close a reader
        // nobody asked to close.
        // The staged deletes go last, after the leave-chapter flush, whose write can stage the
        // chapter just finished, and with or without a chapter: a killed process's are drained too.
        ReaderEvent.Finishing -> state to
            state.currentChapter?.let { leaveChapterEffects(state, it) }.orEmpty() + ReaderEffect.DeletePendingChapters
        is ReaderEvent.TrimMemory -> trimMemory(state, event.level)
        is ReaderEvent.ViewerBuilt -> viewerBuilt(state, event.generation)
        ReaderEvent.OrientationChanged -> orientationChanged(state)
    }

    /**
     * A failed session is closing, its `Finish` on the way to the screen, and asks for nothing more
     * but the staged deletes every finish runs. Its entry can have left the database with its
     * chapters (phase 4 unit BF, ruling R119 carry-over 1), and each write the other events ask for
     * names one of those chapters: a history row for a chapter that is gone fails its foreign key,
     * which took the process with it. A chapter load still in flight when the session failed lands
     * here too, and no longer opens a window over a reader that is closing.
     */
    private fun closing(state: ReaderState, event: ReaderEvent): Pair<ReaderState, List<ReaderEffect>> =
        if (event == ReaderEvent.Finishing) state to listOf(ReaderEffect.DeletePendingChapters) else state.noEffects()

    // region Session

    private fun sessionLoaded(
        state: ReaderState,
        event: ReaderEvent.SessionLoaded,
    ): Pair<ReaderState, List<ReaderEffect>> {
        if (state.phase !is ReaderPhase.Initializing) return state.noEffects()
        val next = state.copy(
            manga = event.manga,
            session = event.session,
            incognito = event.incognito,
            readingMode = event.effectiveMode,
            orientation = event.effectiveOrientation,
            mangaReadingMode = event.manga.readingMode,
            mangaOrientation = event.manga.readerOrientation,
            preload = preloadWindow(),
        )
        return next to listOf(
            ReaderEffect.ApplyOrientation(event.effectiveOrientation),
            ReaderEffect.RebuildViewer(next.viewerGeneration, event.effectiveMode),
            ReaderEffect.LoadChapter(event.session.chapterFor(event.initialChapterId)),
        )
    }

    /**
     * Shared by `MangaChanged` and `PreferencesChanged`: only a change in the *effective* value does
     * anything, and only a mode change bumps the generation. That is the invariant the tests pin.
     */
    private fun modeChange(
        state: ReaderState,
        mode: ReadingMode,
        orientation: ReaderOrientation,
    ): Pair<ReaderState, List<ReaderEffect>> {
        var next = state
        val effects = mutableListOf<ReaderEffect>()
        if (mode != state.readingMode) {
            next = next.copy(readingMode = mode, viewerGeneration = state.viewerGeneration + 1)
            effects += ReaderEffect.RebuildViewer(next.viewerGeneration, mode)
        }
        if (orientation != state.orientation) {
            next = next.copy(orientation = orientation)
            effects += ReaderEffect.ApplyOrientation(orientation)
        }
        return next to effects
    }

    // endregion

    // region Chapter loading

    private fun chapterLoaded(
        state: ReaderState,
        event: ReaderEvent.ChapterLoaded,
    ): Pair<ReaderState, List<ReaderEffect>> {
        val session = state.session ?: return state.noEffects()
        val pages = event.chapter.pages ?: return state.noEffects()
        val window = ChapterWindow.around(session, event.chapter)
        // A chapter loaded again can hold the parts of pages that split while it was on screen.
        val shown = pages.firstPartOf(event.requestedPage)
        val next = state.copy(
            phase = ReaderPhase.Ready,
            window = window,
            currentPage = shown.index,
            totalPages = pages.pageCount(),
            bookmarked = event.chapter.chapter.bookmark,
            loadingAdjacent = false,
            preload = preloadWindow(),
        )
        return next to listOf(
            ReaderEffect.RefChapters(add = window, release = state.window),
            ReaderEffect.SetViewerChapters(window),
            ReaderEffect.RestartReadTimer(event.chapter),
            ReaderEffect.MoveViewerToPage(shown),
        )
    }

    private fun chapterLoadFailed(
        state: ReaderState,
        event: ReaderEvent.ChapterLoadFailed,
    ): Pair<ReaderState, List<ReaderEffect>> = if (state.window == null) {
        state.copy(phase = ReaderPhase.Failed(event.error)) to listOf(ReaderEffect.Finish(event.error))
    } else {
        // The transition holder keeps its own retry from the chapter's state flow.
        state.copy(loadingAdjacent = false) to listOf(ReaderEffect.ShowMessage(R.string.reader_chapter_load_error))
    }

    private fun adjacentChapterLoaded(
        state: ReaderState,
        event: ReaderEvent.AdjacentChapterLoaded,
    ): Pair<ReaderState, List<ReaderEffect>> {
        val window = state.window ?: return state.noEffects()
        val isAdjacent = event.chapter === window.prevChapter || event.chapter === window.nextChapter
        if (!isAdjacent) return state.noEffects()
        return state.copy(loadingAdjacent = false) to listOf(ReaderEffect.SetViewerChapters(window))
    }

    // endregion

    // region Pages

    private fun pageSelected(state: ReaderState, page: ReaderPage): Pair<ReaderState, List<ReaderEffect>> {
        val window = state.window ?: return state.noEffects()
        return when {
            page.chapter === window.currChapter -> pageSelectedInCurrent(state, window, page)
            page.chapter === window.prevChapter || page.chapter === window.nextChapter ->
                pageSelectedInAdjacent(state, window, page)
            else -> state.noEffects()
        }
    }

    private fun pageSelectedInCurrent(
        state: ReaderState,
        window: ViewerChapters,
        page: ReaderPage,
    ): Pair<ReaderState, List<ReaderEffect>> {
        if (state.phase !is ReaderPhase.Ready || state.scrubbing) return state.noEffects()
        val next = state.copy(currentPage = page.index, movingBySlider = false)
        val effects = mutableListOf<ReaderEffect>()
        if (page.index != state.currentPage) {
            effects += ReaderEffect.PageChangedFlash
        }
        effects += progressEffects(next, window.currChapter, page)
        effects += chapterPreloadEffects(next, window, page)
        return next to effects
    }

    private fun pageSelectedInAdjacent(
        state: ReaderState,
        window: ViewerChapters,
        page: ReaderPage,
    ): Pair<ReaderState, List<ReaderEffect>> {
        val session = state.session ?: return state.noEffects()
        val outgoing = window.currChapter
        val leaving = leaveChapterEffects(state, outgoing)
        val pages = page.chapter.pages
        if (page.chapter.state !is ReaderChapter.State.Loaded || pages == null) {
            return state.copy(loadingAdjacent = true) to leaving + ReaderEffect.LoadChapter(page.chapter)
        }

        val newWindow = ChapterWindow.around(session, page.chapter)
        val next = state.copy(
            window = newWindow,
            currentPage = page.index,
            // Parts not counted: a chapter whose pages split before it was entered, as the long
            // strip does with the chapter below, would otherwise never reach its last page.
            totalPages = pages.pageCount(),
            bookmarked = page.chapter.chapter.bookmark,
            loadingAdjacent = false,
            movingBySlider = false,
        )
        val effects = mutableListOf<ReaderEffect>()
        effects += leaving
        effects += ReaderEffect.RefChapters(add = newWindow, release = window)
        effects += ReaderEffect.SetViewerChapters(newWindow)
        effects += ReaderEffect.RestartReadTimer(page.chapter)
        effects += ReaderEffect.PageChangedFlash
        effects += progressEffects(next, page.chapter, page)
        effects += chapterPreloadEffects(next, newWindow, page)
        return next to effects
    }

    /** Rules 5 and 6 of section 3.7: nothing is written in incognito, nothing for an errored page. */
    private fun progressEffects(
        state: ReaderState,
        chapter: ReaderChapter,
        page: ReaderPage,
    ): List<ReaderEffect> {
        if (state.incognito || page.status is PageState.Error) return emptyList()
        val finished = page.index == state.totalPages - 1
        return listOf(
            ReaderEffect.CommitProgress(
                chapter = chapter,
                pageIndex = page.index,
                chapterFinished = finished,
                immediate = finished,
                readBack = if (finished) state.session?.readBack(chapter).orEmpty() else emptyList(),
            ),
        )
    }

    private fun chapterPreloadEffects(
        state: ReaderState,
        window: ViewerChapters,
        page: ReaderPage,
    ): List<ReaderEffect> {
        val next = window.nextChapter ?: return emptyList()
        val remaining = state.totalPages - page.number
        if (remaining >= state.preload.chapterPreloadAt) return emptyList()
        if (next.state !is ReaderChapter.State.Wait) return emptyList()
        return listOf(ReaderEffect.PreloadChapter(next))
    }

    private fun transitionSelected(
        state: ReaderState,
        transition: ChapterTransition,
    ): Pair<ReaderState, List<ReaderEffect>> {
        val to = transition.to ?: return state.noEffects()
        if (!to.isPreloadable()) return state.noEffects()
        return state.copy(loadingAdjacent = to.state !is ReaderChapter.State.Loaded) to
            listOf(ReaderEffect.PreloadChapter(to))
    }

    private fun pageSplit(
        state: ReaderState,
        page: ReaderPage,
        parts: List<InsertPage>,
    ): Pair<ReaderState, List<ReaderEffect>> {
        val chapter = page.chapter
        val pages = chapter.pages ?: return state.noEffects()
        val at = pages.indexOf(page)
        if (at < 0) return state.noEffects()
        chapter.replacePages(pages.take(at + 1) + parts + pages.drop(at + 1))
        // totalPages is unchanged: every part reports its parent's number.
        return state to listOf(ReaderEffect.NotifySplitInserted(page))
    }

    // endregion

    // region Chrome

    private fun sliderReleased(state: ReaderState, index: Int): Pair<ReaderState, List<ReaderEffect>> {
        val released = state.copy(scrubbing = false, scrubStartPage = null)
        val pages = state.currentChapter?.pages ?: return released.noEffects()
        if (pages.isEmpty()) return released.noEffects()
        // The slider counts pages; a split page is shown from its first part.
        val target = pages.firstPartOf(index)
        val page = target.index
        // The viewer reports a page only when the release moves it, and that report is what brings
        // movingBySlider down again, so a release on the page the viewer is on does not raise it.
        val viewerPage = if (state.scrubbing) state.scrubStartPage else state.currentPage
        return released.copy(currentPage = page, movingBySlider = page != viewerPage) to
            listOf(ReaderEffect.MoveViewerToPage(target))
    }

    private fun switchChapter(
        state: ReaderState,
        target: ReaderChapter?,
    ): Pair<ReaderState, List<ReaderEffect>> {
        if (target == null) return state.noEffects()
        val current = state.currentChapter ?: return state.noEffects()
        return state.copy(loadingAdjacent = true) to
            leaveChapterEffects(state, current) + ReaderEffect.LoadChapter(target)
    }

    private fun toggleBookmark(state: ReaderState): Pair<ReaderState, List<ReaderEffect>> {
        val chapter = state.currentChapter ?: return state.noEffects()
        val bookmarked = !state.bookmarked
        return state.copy(bookmarked = bookmarked) to
            listOf(ReaderEffect.WriteBookmark(chapter.chapter.id, bookmarked))
    }

    /**
     * The viewer of the current generation is on screen. While Show reading mode is on, its mode is
     * shown, as upstream's `ReaderActivity.updateViewer` does each time it sets a viewer: on opening the
     * reader, after a mode change, and on an activity recreated with the session. It comes first, so
     * a viewer built before the first chapter has loaded shows it too.
     */
    private fun viewerBuilt(state: ReaderState, generation: Int): Pair<ReaderState, List<ReaderEffect>> {
        if (generation != state.viewerGeneration) return state.noEffects()
        val shown = if (showReadingMode()) listOf(ReaderEffect.ShowReadingMode(state.readingMode)) else emptyList()
        val window = state.window ?: return state to shown
        val pages = window.currChapter.pages ?: return state to shown
        if (pages.isEmpty()) return state to shown
        return state to shown + listOf(
            ReaderEffect.SetViewerChapters(window),
            ReaderEffect.MoveViewerToPage(pages.firstPartOf(state.currentPage)),
        )
    }

    /**
     * Upstream's activity is recreated by a rotation, and its new viewer shows the mode again
     * (`updateViewer`). This one handles the change itself and builds no viewer (R77, D16), so the
     * mode is shown here, the generation untouched, while Show reading mode is on (phase 5 unit FX,
     * R126 carry-over 1). Only once the session has loaded: before that no viewer is on screen and
     * the mode is the state's default, not the entry's.
     */
    private fun orientationChanged(state: ReaderState): Pair<ReaderState, List<ReaderEffect>> =
        if (showReadingMode() && state.session != null) {
            state to listOf(ReaderEffect.ShowReadingMode(state.readingMode))
        } else {
            state.noEffects()
        }

    // endregion

    // region Lifecycle

    private fun trimMemory(state: ReaderState, level: Int): Pair<ReaderState, List<ReaderEffect>> {
        val acts = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        if (!acts) return state.noEffects()
        return state.copy(preload = PreloadPolicy.DEGRADED) to listOf(ReaderEffect.TrimViewer)
    }

    /** Pause, finish and every chapter switch flush the page index and close out the history row. */
    private fun leaveChapterEffects(state: ReaderState, chapter: ReaderChapter): List<ReaderEffect> = buildList {
        add(ReaderEffect.FlushProgress(chapter))
        if (!state.incognito) add(ReaderEffect.CommitHistory(chapter))
    }

    // endregion

    private fun ReaderChapter.isPreloadable(): Boolean =
        state is ReaderChapter.State.Wait || state is ReaderChapter.State.Error

    private fun ReaderState.noEffects(): Pair<ReaderState, List<ReaderEffect>> = this to emptyList()

    companion object {
        const val MIN_BRIGHTNESS = -100
        const val MAX_BRIGHTNESS = 100
    }
}
