package com.shinsaikou.reader.state

import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import tachiyomi.domain.manga.model.Manga

/**
 * Everything the reader shell and the chrome render. Contract 07 section 3.1.
 *
 * Produced only by [ReaderStateMachine]. Nothing writes a field from outside the reducer.
 */
@Immutable
data class ReaderState(
    val phase: ReaderPhase = ReaderPhase.Initializing,
    val manga: Manga? = null,
    /**
     * The ordered chapter list of this session. Section 3.1 leaves it out of the field list while
     * section 3.3 sets it on `SessionLoaded` and reads it on every window change; it is here so the
     * reducer stays a pure function of state and event rather than holding session state of its own.
     */
    val session: ReaderSession? = null,
    /** Effective mode, never DEFAULT (section 3.8). */
    val readingMode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
    /** Effective orientation, never DEFAULT (section 3.8). */
    val orientation: ReaderOrientation = ReaderOrientation.FREE,
    /** Raw per-manga values for the chrome chips; may be DEFAULT. */
    val mangaReadingMode: ReadingMode = ReadingMode.DEFAULT,
    val mangaOrientation: ReaderOrientation = ReaderOrientation.DEFAULT,
    /** Bumped whenever the viewer must be rebuilt (the effective reading mode changed). */
    val viewerGeneration: Int = 0,
    /** The chapter window; null until the first chapter loaded. */
    val window: ViewerChapters? = null,
    /** Index into the current chapter's pages; parts report their parent's index. -1 unknown. */
    val currentPage: Int = -1,
    val totalPages: Int = -1,
    val bookmarked: Boolean = false,
    val loadingAdjacent: Boolean = false,
    val menuVisible: Boolean = false,
    val dialog: ReaderDialog? = null,
    @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    val incognito: Boolean = false,
    /** True while the slider is being dragged; page selections from the viewer are ignored. */
    val scrubbing: Boolean = false,
    /**
     * True from a slider release until the viewer reports the page the release moved it to. The
     * pager hides the menu on a page selection unless the slider caused it, and in this reader the
     * slider moves the viewer only on release, so the release's own move has to count as the
     * slider's; upstream keeps the menu open there too.
     */
    val movingBySlider: Boolean = false,
    /**
     * The page the viewer was on when the current drag began; null while the slider is not being
     * dragged. A release back on that page moves nothing, so the viewer reports no page and
     * [movingBySlider] would stay up until the owner's next swipe, which would keep the menu.
     */
    val scrubStartPage: Int? = null,
    /** Preload window in force; degraded after a trim until the next chapter load. */
    val preload: PreloadPolicy.Window = PreloadPolicy.DEFAULT,
    /** Set by the bench intent; ignored otherwise (section 8). */
    val benchTag: String? = null,
    /**
     * The page notes of the chapter the view model's one note collector follows (phase 4 unit BN).
     * Null until its first answer. It names its chapter, so a chapter switch shows no note of the
     * chapter left behind while the new chapter's answer is on its way.
     */
    val pageNotes: PageNotes? = null,
) {
    val currentChapter: ReaderChapter? get() = window?.currChapter

    /** The entry the chapter on screen belongs to, [manga] unless the title is merged. */
    val currentOwner: Manga? get() = currentChapter?.chapter?.let { session?.ownerOf(it) } ?: manga

    /**
     * The note on the page on screen, or null: what the bottom bar's marker shows. Keyed by
     * [currentPage], which a part of a split page reports as its parent's index, so every part of a
     * noted page shows the marker.
     */
    val currentNote: String?
        get() = pageNotes
            ?.takeIf { it.chapterId == currentChapter?.chapter?.id }
            ?.byPage
            ?.get(currentPage)
}

/** One chapter's page notes, keyed by the reader page's index. */
@Immutable
data class PageNotes(val chapterId: Long, val byPage: Map<Int, String>)

sealed interface ReaderPhase {
    data object Initializing : ReaderPhase
    data object Ready : ReaderPhase

    /** Terminal: the shell shows the message and finishes. */
    data class Failed(val error: Throwable) : ReaderPhase
}

sealed interface ReaderDialog {
    data object Loading : ReaderDialog
    data object Settings : ReaderDialog
    data object ReadingModeSelect : ReaderDialog
    data object OrientationSelect : ReaderDialog
    data class PageActions(val page: ReaderPage) : ReaderDialog

    /**
     * The one-field note dialog for one page (unit BN). [note] is the text the field opens with: the
     * page's stored note or "", or after a process death the text that was typed and not saved.
     */
    data class PageNote(val chapterId: Long, val pageIndex: Int, val note: String) : ReaderDialog
}
