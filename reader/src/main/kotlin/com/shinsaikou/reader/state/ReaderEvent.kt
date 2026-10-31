package com.shinsaikou.reader.state

import androidx.annotation.StringRes
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import tachiyomi.domain.manga.model.Manga

/**
 * Everything that can happen to the reader. Contract 07 section 3.2.
 *
 * Sent by the view model, the viewers and the chrome; consumed only by [ReaderStateMachine].
 */
sealed interface ReaderEvent {

    // region Session

    data class SessionLoaded(
        val manga: Manga,
        val session: ReaderSession,
        val initialChapterId: Long,
        val incognito: Boolean,
        val effectiveMode: ReadingMode,
        val effectiveOrientation: ReaderOrientation,
    ) : ReaderEvent

    /** [message], when set, is what the owner is told instead of the error's own text. */
    data class SessionFailed(val error: Throwable, @StringRes val message: Int? = null) : ReaderEvent

    data class MangaChanged(
        val manga: Manga,
        val effectiveMode: ReadingMode,
        val effectiveOrientation: ReaderOrientation,
    ) : ReaderEvent

    data class PreferencesChanged(
        val effectiveMode: ReadingMode,
        val effectiveOrientation: ReaderOrientation,
    ) : ReaderEvent

    // endregion

    // region Chapter loading, the results of LoadChapter and PreloadChapter

    data class ChapterLoaded(val chapter: ReaderChapter, val requestedPage: Int) : ReaderEvent
    data class ChapterLoadFailed(val chapter: ReaderChapter, val error: Throwable) : ReaderEvent
    data class AdjacentChapterLoaded(val chapter: ReaderChapter) : ReaderEvent
    data class AdjacentChapterLoadFailed(val chapter: ReaderChapter, val error: Throwable) : ReaderEvent

    // endregion

    // region From the viewer

    data class PageSelected(val page: ReaderPage) : ReaderEvent
    data class TransitionSelected(val transition: ChapterTransition) : ReaderEvent
    data class PreloadRequested(val chapter: ReaderChapter) : ReaderEvent
    data class PageLongTapped(val page: ReaderPage) : ReaderEvent
    data class PageSplit(val page: ReaderPage, val parts: List<InsertPage>) : ReaderEvent

    // endregion

    // region From the chrome

    data object ToggleMenu : ReaderEvent
    data object ShowMenu : ReaderEvent
    data object HideMenu : ReaderEvent
    data class SliderMoved(val index: Int) : ReaderEvent
    data class SliderReleased(val index: Int) : ReaderEvent
    data object NextChapter : ReaderEvent
    data object PreviousChapter : ReaderEvent
    data object ToggleBookmark : ReaderEvent
    data class BookmarkWritten(val chapterId: Long, val bookmark: Boolean) : ReaderEvent
    data class SetReadingMode(val mode: ReadingMode) : ReaderEvent
    data class SetOrientation(val orientation: ReaderOrientation) : ReaderEvent
    data class ShowDialog(val dialog: ReaderDialog) : ReaderEvent
    data object DismissDialog : ReaderEvent
    data class BrightnessChanged(val value: Int) : ReaderEvent

    // endregion

    // region Page notes (phase 4 unit BN)

    /** The page sheet's Note button, on the page that was long-pressed; it closes the sheet. */
    data class EditNote(val page: ReaderPage) : ReaderEvent

    /** The bottom bar's marker: the note of the page on screen. */
    data object EditCurrentNote : ReaderEvent

    /** The note dialog's field changed; the text is kept for a process death, never written. */
    data class NoteTextChanged(val text: String) : ReaderEvent

    /** The note dialog's Save, with the text as typed; blank text removes the note. */
    data class SaveNote(val chapterId: Long, val pageIndex: Int, val text: String) : ReaderEvent

    /** The note collector's answer for [chapterId], keyed by page index. */
    data class NotesChanged(val chapterId: Long, val byPage: Map<Int, String>) : ReaderEvent

    // endregion

    // region Lifecycle and memory

    data object Resumed : ReaderEvent
    data object Paused : ReaderEvent
    data object Finishing : ReaderEvent
    data class TrimMemory(val level: Int) : ReaderEvent
    data class ViewerBuilt(val generation: Int) : ReaderEvent

    /**
     * The screen turned between portrait and landscape. The activity handles the change itself, so
     * no viewer is rebuilt (phase 5 unit FX, R126 carry-over 1).
     */
    data object OrientationChanged : ReaderEvent

    // endregion
}

/**
 * What the reducer asks the world to do. Contract 07 section 3.2.
 *
 * The view model performs the loads, the reference counting and the database writes; everything
 * else is forwarded to the shell on its effects flow.
 */
sealed interface ReaderEffect {

    /** Load [chapter] and make it the current one. */
    data class LoadChapter(val chapter: ReaderChapter) : ReaderEffect

    /** Load an adjacent chapter's page list only. */
    data class PreloadChapter(val chapter: ReaderChapter) : ReaderEffect

    data class RefChapters(val add: ViewerChapters, val release: ViewerChapters?) : ReaderEffect

    data class SetViewerChapters(val window: ViewerChapters) : ReaderEffect
    data class MoveViewerToPage(val page: ReaderPage) : ReaderEffect
    data class NotifySplitInserted(val page: ReaderPage) : ReaderEffect

    /**
     * [readBack] is the session's chapters from [chapter] backwards ([ReaderSession.readBack]), set
     * only when [chapterFinished]: what the delete after reading picks its chapter from.
     */
    data class CommitProgress(
        val chapter: ReaderChapter,
        val pageIndex: Int,
        val chapterFinished: Boolean,
        val immediate: Boolean,
        val readBack: List<Long> = emptyList(),
    ) : ReaderEffect

    data class FlushProgress(val chapter: ReaderChapter?) : ReaderEffect
    data class CommitHistory(val chapter: ReaderChapter) : ReaderEffect

    /**
     * Upstream's `onActivityFinish`: the chapters staged for deletion after reading are deleted now,
     * after the progress the same finish flushed (phase 3 unit DR).
     */
    data object DeletePendingChapters : ReaderEffect

    /**
     * The page index waiting for its debounced write is dropped, unwritten: the session failed, and
     * its entry may have left the database with its chapters (phase 4 unit BF).
     */
    data object DiscardProgress : ReaderEffect
    data class RestartReadTimer(val chapter: ReaderChapter) : ReaderEffect

    data class WriteBookmark(val chapterId: Long, val bookmark: Boolean) : ReaderEffect
    data class WriteReadingMode(val mangaId: Long, val mode: ReadingMode) : ReaderEffect
    data class WriteOrientation(val mangaId: Long, val orientation: ReaderOrientation) : ReaderEffect

    /** Reads the page's stored note and opens [ReaderDialog.PageNote] with it (unit BN). */
    data class OpenNoteEditor(val chapterId: Long, val pageIndex: Int) : ReaderEffect
    data class WriteNote(val chapterId: Long, val pageIndex: Int, val note: String) : ReaderEffect
    data class RemoveNote(val chapterId: Long, val pageIndex: Int) : ReaderEffect

    /** Keeps the open note dialog's text in the saved state handle (unit BN). */
    data class KeepNoteText(val text: String) : ReaderEffect

    /** The activity's `requestedOrientation`. */
    data class ApplyOrientation(val orientation: ReaderOrientation) : ReaderEffect

    /**
     * A marker, not an instruction: `ReaderState.viewerGeneration` is what actually rebuilds the
     * viewer, and performing this too would build two viewers for one mode change (decision D16).
     */
    data class RebuildViewer(val generation: Int, val mode: ReadingMode) : ReaderEffect

    /** `DisplayRefreshHost.flash()`. */
    data object PageChangedFlash : ReaderEffect

    /** `Viewer.trimMemory()`. */
    data object TrimViewer : ReaderEffect

    data class ShowMessage(@StringRes val res: Int) : ReaderEffect

    /** Upstream's reading mode toast, naming [mode]; a newer one replaces one still on screen. */
    data class ShowReadingMode(val mode: ReadingMode) : ReaderEffect

    /** Closes the reader; an [error] is shown first, as [message] when one is set. */
    data class Finish(val error: Throwable?, @StringRes val message: Int? = null) : ReaderEffect
}
