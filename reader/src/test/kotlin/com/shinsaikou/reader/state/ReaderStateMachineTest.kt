package com.shinsaikou.reader.state

import android.content.ComponentCallbacks2
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.pipeline.PageState
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** One test per row of contract 07 section 3.3, plus the six invariants that section lists. */
class ReaderStateMachineTest {

    private val first = loadedChapter(1, pageCount = 3)
    private val current = loadedChapter(2, pageCount = 3)
    private val nextWaiting = waitingChapter(3)
    private val session = session(first, current, nextWaiting)

    // ---- Session ----

    @Test
    fun `session loaded applies the orientation, builds the viewer and loads the first chapter`() {
        val (state, effects) = machine().reduce(
            ReaderState(),
            ReaderEvent.SessionLoaded(
                manga = manga(viewerFlags = ReadingMode.WEBTOON.flagValue.toLong()),
                session = session,
                initialChapterId = 2,
                incognito = true,
                effectiveMode = ReadingMode.WEBTOON,
                effectiveOrientation = ReaderOrientation.LOCKED_PORTRAIT,
            ),
        )

        state.phase shouldBe ReaderPhase.Initializing
        state.dialog shouldBe null
        state.session shouldBe session
        state.incognito shouldBe true
        state.readingMode shouldBe ReadingMode.WEBTOON
        state.mangaReadingMode shouldBe ReadingMode.WEBTOON
        state.viewerGeneration shouldBe 0
        effects shouldContainExactly listOf(
            ReaderEffect.ApplyOrientation(ReaderOrientation.LOCKED_PORTRAIT),
            ReaderEffect.RebuildViewer(0, ReadingMode.WEBTOON),
            ReaderEffect.LoadChapter(current),
        )
    }

    @Test
    fun `a second session load is ignored`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(ready, sessionLoaded())
        state shouldBe ready
        effects.shouldBeEmpty()
    }

    @Test
    fun `session failed is terminal`() {
        val error = IllegalStateException("no")
        val (state, effects) = machine().reduce(ReaderState(), ReaderEvent.SessionFailed(error))
        state.phase shouldBe ReaderPhase.Failed(error)
        effects shouldContainExactly listOf(ReaderEffect.DiscardProgress, ReaderEffect.Finish(error))
    }

    /**
     * An open session fails when its entry's row leaves the database (phase 4 unit BF): the progress
     * waiting for its write is dropped, the reader closes, and nothing the closing reader reports
     * afterwards asks for a write, since each would name a chapter that left with the entry.
     */
    @Test
    fun `an open session that fails drops its pending progress and asks for no write while it closes`() {
        val error = IllegalStateException("gone")
        val (failed, effects) = machine().reduce(readyState(session, current), ReaderEvent.SessionFailed(error))
        failed.phase shouldBe ReaderPhase.Failed(error)
        effects shouldContainExactly listOf(ReaderEffect.DiscardProgress, ReaderEffect.Finish(error))

        listOf(
            ReaderEvent.PageSelected(readyPage(current, 2)),
            ReaderEvent.PageSelected(readyPage(first, 0)),
            ReaderEvent.NextChapter,
            ReaderEvent.PreviousChapter,
            ReaderEvent.ToggleBookmark,
            ReaderEvent.SetReadingMode(ReadingMode.WEBTOON),
            ReaderEvent.SetOrientation(ReaderOrientation.LOCKED_PORTRAIT),
            ReaderEvent.SaveNote(current.chapter.id, 0, "note"),
            ReaderEvent.Paused,
            ReaderEvent.Resumed,
            ReaderEvent.ChapterLoaded(nextWaiting, requestedPage = 0),
        ).forEach { event ->
            machine().reduce(failed, event) shouldBe (failed to emptyList())
        }
        machine().reduce(failed, ReaderEvent.Finishing) shouldBe (failed to listOf(ReaderEffect.DeletePendingChapters))
    }

    // ---- Chapter loading ----

    @Test
    fun `chapter loaded opens the window and moves the viewer to the requested page`() {
        val start = ReaderState(session = session, preload = PreloadPolicy.DEGRADED)
        val (state, effects) = machine().reduce(start, ReaderEvent.ChapterLoaded(current, requestedPage = 1))

        state.phase shouldBe ReaderPhase.Ready
        state.window?.currChapter shouldBe current
        state.window?.prevChapter shouldBe first
        state.window?.nextChapter shouldBe nextWaiting
        state.currentPage shouldBe 1
        state.totalPages shouldBe 3
        state.loadingAdjacent shouldBe false
        // A degraded window is restored by the next chapter load.
        state.preload shouldBe PreloadPolicy.DEFAULT
        effects shouldContainExactly listOf(
            ReaderEffect.RefChapters(add = state.window!!, release = null),
            ReaderEffect.SetViewerChapters(state.window!!),
            ReaderEffect.RestartReadTimer(current),
            ReaderEffect.MoveViewerToPage(current.pages!![1]),
        )
    }

    @Test
    fun `a requested page past the end of the chapter is clamped`() {
        val (state, _) = machine().reduce(
            ReaderState(session = session),
            ReaderEvent.ChapterLoaded(current, requestedPage = 99),
        )
        state.currentPage shouldBe 2
    }

    @Test
    fun `the first chapter failing to load is terminal`() {
        val error = IllegalStateException("no pages")
        val (state, effects) = machine().reduce(
            ReaderState(session = session),
            ReaderEvent.ChapterLoadFailed(current, error),
        )
        state.phase shouldBe ReaderPhase.Failed(error)
        effects shouldContainExactly listOf(ReaderEffect.Finish(error))
    }

    @Test
    fun `a later chapter failing to load shows a message and keeps the reader open`() {
        val (state, effects) = machine().reduce(
            readyState(session, current).copy(loadingAdjacent = true),
            ReaderEvent.ChapterLoadFailed(nextWaiting, IllegalStateException("no pages")),
        )
        state.phase shouldBe ReaderPhase.Ready
        state.loadingAdjacent shouldBe false
        effects shouldContainExactly listOf(ReaderEffect.ShowMessage(R.string.reader_chapter_load_error))
    }

    @Test
    fun `an adjacent chapter loading re-sets the viewer's chapters`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(ready, ReaderEvent.AdjacentChapterLoaded(nextWaiting))
        state.window shouldBe ready.window
        effects shouldContainExactly listOf(ReaderEffect.SetViewerChapters(ready.window!!))
    }

    @Test
    fun `a chapter outside the window loading does nothing`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.AdjacentChapterLoaded(waitingChapter(99))).second.shouldBeEmpty()
    }

    @Test
    fun `an adjacent chapter failing to load produces no effects`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.AdjacentChapterLoadFailed(nextWaiting, Exception())).second.shouldBeEmpty()
    }

    // ---- Page selection ----

    @Test
    fun `selecting a page flashes and commits progress`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(ready, ReaderEvent.PageSelected(readyPage(current, 1)))

        state.currentPage shouldBe 1
        effects shouldContainExactly listOf(
            ReaderEffect.PageChangedFlash,
            ReaderEffect.CommitProgress(current, pageIndex = 1, chapterFinished = false, immediate = false),
            // Three pages and a window of four: the next chapter is already inside the preload range.
            ReaderEffect.PreloadChapter(nextWaiting),
        )
    }

    @Test
    fun `the last page of a chapter commits immediately and marks it finished`() {
        val ready = readyState(session, current, currentPage = 1)
        val (_, effects) = machine().reduce(ready, ReaderEvent.PageSelected(readyPage(current, 2)))
        effects shouldContainExactlyInAnyOrder listOf(
            ReaderEffect.PageChangedFlash,
            ReaderEffect.CommitProgress(
                current,
                pageIndex = 2,
                chapterFinished = true,
                immediate = true,
                // The session's chapters from this one back, for the delete after reading (unit DR).
                readBack = listOf(current.chapter.id, first.chapter.id),
            ),
            ReaderEffect.PreloadChapter(nextWaiting),
        )
    }

    @Test
    fun `a part of the current page does not flash again`() {
        val ready = readyState(session, current, currentPage = 1)
        val part = InsertPage(readyPage(current, 1), partIndex = 1)
        val (state, effects) = machine().reduce(ready, ReaderEvent.PageSelected(part))
        state.currentPage shouldBe 1
        effects.filterIsInstance<ReaderEffect.PageChangedFlash>().shouldBeEmpty()
    }

    @Test
    fun `an errored page commits nothing`() {
        val ready = readyState(session, current)
        val page = current.pages!![1].apply { status = PageState.Error(Exception()) }
        val (_, effects) = machine().reduce(ready, ReaderEvent.PageSelected(page))
        effects.filterIsInstance<ReaderEffect.CommitProgress>().shouldBeEmpty()
    }

    @Test
    fun `incognito commits nothing`() {
        val ready = readyState(session, current, incognito = true)
        val (_, effects) = machine().reduce(ready, ReaderEvent.PageSelected(readyPage(current, 1)))
        effects.filterIsInstance<ReaderEffect.CommitProgress>().shouldBeEmpty()
    }

    @Test
    fun `a page selection while scrubbing produces nothing at all`() {
        val ready = readyState(session, current).copy(scrubbing = true)
        val (state, effects) = machine().reduce(ready, ReaderEvent.PageSelected(readyPage(current, 2)))
        state shouldBe ready
        effects.shouldBeEmpty()
    }

    @Test
    fun `the next chapter is preloaded inside the preload window and not before it`() {
        // Three pages, chapterPreloadAt 2: page 0 leaves 2 remaining, page 1 leaves 1.
        val small = PreloadPolicy.DEGRADED
        val ready = readyState(session, current, currentPage = -1, preload = small)
        machine(preload = small)
            .reduce(ready, ReaderEvent.PageSelected(readyPage(current, 0)))
            .second.filterIsInstance<ReaderEffect.PreloadChapter>().shouldBeEmpty()

        machine(preload = small)
            .reduce(ready, ReaderEvent.PageSelected(readyPage(current, 1)))
            .second shouldContainExactlyInAnyOrder listOf(
            ReaderEffect.PageChangedFlash,
            ReaderEffect.CommitProgress(current, 1, chapterFinished = false, immediate = false),
            ReaderEffect.PreloadChapter(nextWaiting),
        )
    }

    @Test
    fun `an already loaded next chapter is not preloaded again`() {
        val loadedNext = loadedChapter(3)
        val ownSession = session(first, current, loadedNext)
        val ready = readyState(ownSession, current, currentPage = 1)
        machine().reduce(ready, ReaderEvent.PageSelected(readyPage(current, 2)))
            .second.filterIsInstance<ReaderEffect.PreloadChapter>().shouldBeEmpty()
    }

    @Test
    fun `selecting a page of an unloaded neighbour flushes the old chapter and loads it`() {
        val ready = readyState(session, current, currentPage = 2)
        val page = ReaderPage(0).apply { chapter = nextWaiting }
        val (state, effects) = machine().reduce(ready, ReaderEvent.PageSelected(page))

        state.loadingAdjacent shouldBe true
        state.window shouldBe ready.window
        effects shouldContainExactly listOf(
            ReaderEffect.FlushProgress(current),
            ReaderEffect.CommitHistory(current),
            ReaderEffect.LoadChapter(nextWaiting),
        )
    }

    @Test
    fun `selecting a page of a loaded neighbour re-centres the window synchronously`() {
        val loadedNext = loadedChapter(3, bookmark = true)
        val ownSession = session(first, current, loadedNext)
        val ready = readyState(ownSession, current, currentPage = 2)
        val (state, effects) = machine().reduce(ready, ReaderEvent.PageSelected(readyPage(loadedNext, 0)))

        state.window?.currChapter shouldBe loadedNext
        state.currentPage shouldBe 0
        state.totalPages shouldBe 3
        state.bookmarked shouldBe true
        state.loadingAdjacent shouldBe false
        effects shouldContainExactly listOf(
            ReaderEffect.FlushProgress(current),
            ReaderEffect.CommitHistory(current),
            ReaderEffect.RefChapters(add = state.window!!, release = ready.window),
            ReaderEffect.SetViewerChapters(state.window!!),
            ReaderEffect.RestartReadTimer(loadedNext),
            ReaderEffect.PageChangedFlash,
            ReaderEffect.CommitProgress(loadedNext, 0, chapterFinished = false, immediate = false),
        )
    }

    @Test
    fun `a page from a chapter outside the window is ignored`() {
        val ready = readyState(session, current)
        val stranger = loadedChapter(42)
        machine().reduce(ready, ReaderEvent.PageSelected(readyPage(stranger, 0))).second.shouldBeEmpty()
    }

    // ---- Transitions and preload requests ----

    @Test
    fun `a transition to a waiting chapter preloads it`() {
        val ready = readyState(session, current)
        val transition = ChapterTransition.Next(current, nextWaiting)
        val (state, effects) = machine().reduce(ready, ReaderEvent.TransitionSelected(transition))
        state.loadingAdjacent shouldBe true
        effects shouldContainExactly listOf(ReaderEffect.PreloadChapter(nextWaiting))
    }

    @Test
    fun `a transition to nothing and to a loaded chapter both do nothing`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.TransitionSelected(ChapterTransition.Next(current, null)))
            .second.shouldBeEmpty()
        machine().reduce(ready, ReaderEvent.TransitionSelected(ChapterTransition.Prev(current, first)))
            .second.shouldBeEmpty()
    }

    @Test
    fun `a preload request is honoured for a waiting or errored chapter only`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.PreloadRequested(nextWaiting)).second shouldContainExactly
            listOf(ReaderEffect.PreloadChapter(nextWaiting))

        nextWaiting.state = ReaderChapter.State.Error(Exception())
        machine().reduce(ready, ReaderEvent.PreloadRequested(nextWaiting)).second shouldContainExactly
            listOf(ReaderEffect.PreloadChapter(nextWaiting))

        machine().reduce(ready, ReaderEvent.PreloadRequested(first)).second.shouldBeEmpty()
    }

    // ---- Splits ----

    @Test
    fun `a split inserts its parts right after the parent and leaves the page count alone`() {
        val ready = readyState(session, current, currentPage = 1)
        val parent = current.pages!![1]
        val parts = listOf(InsertPage(parent, 1), InsertPage(parent, 2))

        val (state, effects) = machine().reduce(ready, ReaderEvent.PageSplit(parent, parts))

        current.pages!!.map { it.index } shouldContainExactly listOf(0, 1, 1, 1, 2)
        current.pages!![2] shouldBe parts[0]
        state.totalPages shouldBe 3
        effects shouldContainExactly listOf(ReaderEffect.NotifySplitInserted(parent))
    }

    @Test
    fun `a split of a page that is no longer in the chapter does nothing`() {
        val ready = readyState(session, current)
        val stranger = ReaderPage(0).apply { chapter = current }
        machine().reduce(ready, ReaderEvent.PageSplit(stranger, emptyList())).second.shouldBeEmpty()
    }

    // A part carries its page's index and sits after it in the list, so a list position is not a
    // page. The audit's split-part findings: read marking, the slider and resume.

    @Test
    fun `a split chapter entered through the strip counts its pages and is finished on its last one`() {
        val loadedNext = loadedChapter(3)
        splitPage(loadedNext, 0, parts = 3)
        val ready = readyState(session(first, current, loadedNext), current, currentPage = 2)

        val (entered, _) = machine().reduce(ready, ReaderEvent.PageSelected(readyPage(loadedNext, 0)))
        entered.totalPages shouldBe 3

        val (_, effects) = machine().reduce(entered, ReaderEvent.PageSelected(readyPage(loadedNext, 2)))
        effects.filterIsInstance<ReaderEffect.CommitProgress>().single().chapterFinished shouldBe true
    }

    @Test
    fun `a split chapter loaded again counts its pages and opens on the requested page, not on a part`() {
        splitPage(current, 0, parts = 2)

        val (state, effects) = machine().reduce(
            ReaderState(session = session),
            ReaderEvent.ChapterLoaded(current, requestedPage = 2),
        )
        state.totalPages shouldBe 3
        state.currentPage shouldBe 2
        effects.last() shouldBe ReaderEffect.MoveViewerToPage(readyPage(current, 2))

        machine().reduce(ReaderState(session = session), ReaderEvent.ChapterLoaded(current, requestedPage = 99))
            .first.currentPage shouldBe 2
    }

    @Test
    fun `the slider over a split chapter lands on the first part of each page and stops at the last page`() {
        splitPage(current, 1, parts = 2)
        val ready = readyState(session, current).copy(scrubbing = true, scrubStartPage = 0)

        machine().reduce(ready, ReaderEvent.SliderReleased(1)).second shouldContainExactly
            listOf(ReaderEffect.MoveViewerToPage(readyPage(current, 1)))
        machine().reduce(ready, ReaderEvent.SliderReleased(2)).second shouldContainExactly
            listOf(ReaderEffect.MoveViewerToPage(readyPage(current, 2)))
        machine().reduce(ready, ReaderEvent.SliderReleased(99)).first.currentPage shouldBe 2
    }

    @Test
    fun `a rebuilt viewer over a split chapter goes back to the page, not to a part`() {
        splitPage(current, 0, parts = 2)
        val ready = readyState(session, current, currentPage = 2)
        machine().reduce(ready, ReaderEvent.ViewerBuilt(0)).second.last() shouldBe
            ReaderEffect.MoveViewerToPage(readyPage(current, 2))
    }

    // ---- Chrome ----

    @Test
    fun `the menu toggles, shows and hides`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.ToggleMenu).first.menuVisible shouldBe true
        machine().reduce(ready.copy(menuVisible = true), ReaderEvent.ToggleMenu).first.menuVisible shouldBe false
        machine().reduce(ready, ReaderEvent.ShowMenu).first.menuVisible shouldBe true
        machine().reduce(ready.copy(menuVisible = true), ReaderEvent.HideMenu).first.menuVisible shouldBe false
    }

    @Test
    fun `dragging the slider moves the page without touching the viewer`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(ready, ReaderEvent.SliderMoved(2))
        state.scrubbing shouldBe true
        state.currentPage shouldBe 2
        effects.shouldBeEmpty()
    }

    @Test
    fun `releasing the slider moves the viewer once`() {
        val ready = readyState(session, current).copy(scrubbing = true, currentPage = 2)
        val (state, effects) = machine().reduce(ready, ReaderEvent.SliderReleased(2))
        state.scrubbing shouldBe false
        state.currentPage shouldBe 2
        effects shouldContainExactly listOf(ReaderEffect.MoveViewerToPage(current.pages!![2]))
    }

    @Test
    fun `next and previous chapter close out the current one first`() {
        val ready = readyState(session, current)
        val (nextState, nextEffects) = machine().reduce(ready, ReaderEvent.NextChapter)
        nextState.loadingAdjacent shouldBe true
        nextEffects shouldContainExactly listOf(
            ReaderEffect.FlushProgress(current),
            ReaderEffect.CommitHistory(current),
            ReaderEffect.LoadChapter(nextWaiting),
        )

        machine().reduce(ready, ReaderEvent.PreviousChapter).second.last() shouldBe
            ReaderEffect.LoadChapter(first)
    }

    @Test
    fun `there is nothing past either end of the session`() {
        machine().reduce(readyState(session, first), ReaderEvent.PreviousChapter).second.shouldBeEmpty()
        machine().reduce(readyState(session, nextWaiting), ReaderEvent.NextChapter).second.shouldBeEmpty()
    }

    @Test
    fun `the bookmark flips optimistically and the write follows`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(ready, ReaderEvent.ToggleBookmark)
        state.bookmarked shouldBe true
        effects shouldContainExactly listOf(ReaderEffect.WriteBookmark(current.chapter.id, true))
    }

    @Test
    fun `a bookmark written for another chapter is ignored`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.BookmarkWritten(current.chapter.id, true)).first.bookmarked shouldBe true
        machine().reduce(ready, ReaderEvent.BookmarkWritten(999, true)).first.bookmarked shouldBe false
    }

    @Test
    fun `setting the reading mode writes it and waits for the manga to come back`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(ready, ReaderEvent.SetReadingMode(ReadingMode.WEBTOON))
        state.mangaReadingMode shouldBe ReadingMode.WEBTOON
        // The effective mode is unchanged until MangaChanged arrives.
        state.readingMode shouldBe ready.readingMode
        state.viewerGeneration shouldBe 0
        effects shouldContainExactly listOf(ReaderEffect.WriteReadingMode(MANGA_ID, ReadingMode.WEBTOON))
    }

    @Test
    fun `setting the orientation writes it`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(ready, ReaderEvent.SetOrientation(ReaderOrientation.PORTRAIT))
        state.mangaOrientation shouldBe ReaderOrientation.PORTRAIT
        effects shouldContainExactly listOf(ReaderEffect.WriteOrientation(MANGA_ID, ReaderOrientation.PORTRAIT))
    }

    @Test
    fun `a manga change with a new effective mode bumps the generation once`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(
            ready,
            ReaderEvent.MangaChanged(
                manga = manga(viewerFlags = ReadingMode.WEBTOON.flagValue.toLong()),
                effectiveMode = ReadingMode.WEBTOON,
                effectiveOrientation = ready.orientation,
            ),
        )
        state.readingMode shouldBe ReadingMode.WEBTOON
        state.mangaReadingMode shouldBe ReadingMode.WEBTOON
        state.viewerGeneration shouldBe 1
        effects shouldContainExactly listOf(ReaderEffect.RebuildViewer(1, ReadingMode.WEBTOON))
    }

    @Test
    fun `a manga change that does not move the effective values does nothing`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(
            ready,
            ReaderEvent.MangaChanged(manga(), ready.readingMode, ready.orientation),
        )
        state.viewerGeneration shouldBe 0
        effects.shouldBeEmpty()
    }

    @Test
    fun `a preference change applies the same rule as a manga change`() {
        val ready = readyState(session, current)
        val (state, effects) = machine().reduce(
            ready,
            ReaderEvent.PreferencesChanged(ReadingMode.VERTICAL, ReaderOrientation.PORTRAIT),
        )
        state.readingMode shouldBe ReadingMode.VERTICAL
        state.orientation shouldBe ReaderOrientation.PORTRAIT
        state.viewerGeneration shouldBe 1
        effects shouldContainExactly listOf(
            ReaderEffect.RebuildViewer(1, ReadingMode.VERTICAL),
            ReaderEffect.ApplyOrientation(ReaderOrientation.PORTRAIT),
        )
    }

    @Test
    fun `a rebuilt viewer of the current generation gets the window and the page back`() {
        val ready = readyState(session, current, currentPage = 2)
        val (_, effects) = machine().reduce(ready, ReaderEvent.ViewerBuilt(0))
        effects shouldContainExactly listOf(
            ReaderEffect.SetViewerChapters(ready.window!!),
            ReaderEffect.MoveViewerToPage(current.pages!![2]),
        )
    }

    @Test
    fun `a rebuilt viewer of a stale generation is ignored`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.ViewerBuilt(1)).second.shouldBeEmpty()
        machine(showReadingMode = true).reduce(ready, ReaderEvent.ViewerBuilt(1)).second.shouldBeEmpty()
    }

    /**
     * Phase 4 unit MP1, R102 carry-over 1: upstream's `ReaderActivity.updateViewer` toasts the mode each
     * time it sets a viewer while Show reading mode is on. The viewer of a session is built before
     * its first chapter has loaded, so the mode is shown from a state with no window as well.
     */
    @Test
    fun `a built viewer shows its reading mode while Show reading mode is on`() {
        val opening = ReaderState(session = session, readingMode = ReadingMode.WEBTOON)
        machine(showReadingMode = true).reduce(opening, ReaderEvent.ViewerBuilt(0)).second shouldContainExactly
            listOf(ReaderEffect.ShowReadingMode(ReadingMode.WEBTOON))

        val ready = readyState(session, current, currentPage = 2).copy(readingMode = ReadingMode.VERTICAL)
        machine(showReadingMode = true).reduce(ready, ReaderEvent.ViewerBuilt(0)).second shouldContainExactly listOf(
            ReaderEffect.ShowReadingMode(ReadingMode.VERTICAL),
            ReaderEffect.SetViewerChapters(ready.window!!),
            ReaderEffect.MoveViewerToPage(current.pages!![2]),
        )
    }

    @Test
    fun `a built viewer shows no reading mode while Show reading mode is off`() {
        val opening = ReaderState(session = session, readingMode = ReadingMode.WEBTOON)
        machine(showReadingMode = false).reduce(opening, ReaderEvent.ViewerBuilt(0)).second.shouldBeEmpty()

        val ready = readyState(session, current, currentPage = 2)
        machine(showReadingMode = false).reduce(ready, ReaderEvent.ViewerBuilt(0)).second.none {
            it is ReaderEffect.ShowReadingMode
        } shouldBe true
    }

    /**
     * Phase 5 unit FX, R126 carry-over 1: a rotation recreates upstream's activity, whose new viewer
     * shows the mode again. Saikou's activity keeps itself and its viewer, so the turn itself shows
     * the mode, with no viewer rebuilt (D16), and only while Show reading mode is on.
     */
    @Test
    fun `an orientation change shows the reading mode while Show reading mode is on, rebuilding nothing`() {
        val ready = readyState(session, current, currentPage = 2).copy(readingMode = ReadingMode.WEBTOON)
        val (state, effects) = machine(showReadingMode = true).reduce(ready, ReaderEvent.OrientationChanged)

        state shouldBe ready
        state.viewerGeneration shouldBe ready.viewerGeneration
        effects shouldContainExactly listOf(ReaderEffect.ShowReadingMode(ReadingMode.WEBTOON))
    }

    @Test
    fun `an orientation change shows nothing while Show reading mode is off`() {
        val ready = readyState(session, current, currentPage = 2).copy(readingMode = ReadingMode.WEBTOON)
        val (state, effects) = machine(showReadingMode = false).reduce(ready, ReaderEvent.OrientationChanged)

        state shouldBe ready
        effects.shouldBeEmpty()
    }

    /** No viewer is on screen before the session loads, and the mode is not yet the entry's. */
    @Test
    fun `an orientation change before the session loads or after it failed shows nothing`() {
        machine(showReadingMode = true).reduce(ReaderState(), ReaderEvent.OrientationChanged).second.shouldBeEmpty()

        val error = IllegalStateException("gone")
        val machine = machine(showReadingMode = true)
        val (failed, _) = machine.reduce(readyState(session, current), ReaderEvent.SessionFailed(error))
        machine.reduce(failed, ReaderEvent.OrientationChanged).second.shouldBeEmpty()
    }

    @Test
    fun `a mode change shows the new mode once its viewer is built`() {
        val ready = readyState(session, current).copy(readingMode = ReadingMode.RIGHT_TO_LEFT)
        val machine = machine(showReadingMode = true)
        val (changed, _) = machine.reduce(
            ready,
            ReaderEvent.PreferencesChanged(ReadingMode.LEFT_TO_RIGHT, ready.orientation),
        )
        changed.viewerGeneration shouldBe 1

        val (_, effects) = machine.reduce(changed, ReaderEvent.ViewerBuilt(1))
        effects.first() shouldBe ReaderEffect.ShowReadingMode(ReadingMode.LEFT_TO_RIGHT)
    }

    @Test
    fun `dialogs open and close`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.ShowDialog(ReaderDialog.Settings)).first.dialog shouldBe
            ReaderDialog.Settings
        machine().reduce(ready.copy(dialog = ReaderDialog.Settings), ReaderEvent.DismissDialog).first.dialog shouldBe null
    }

    @Test
    fun `a long tap opens the page actions only when the preference allows it`() {
        val ready = readyState(session, current)
        val page = readyPage(current, 0)
        machine(longTapEnabled = true).reduce(ready, ReaderEvent.PageLongTapped(page)).first.dialog shouldBe
            ReaderDialog.PageActions(page)
        machine(longTapEnabled = false).reduce(ready, ReaderEvent.PageLongTapped(page)).first.dialog shouldBe null
    }

    @Test
    fun `with the menu showing a long tap opens the page actions even with the preference off`() {
        val ready = readyState(session, current).copy(menuVisible = true)
        val page = readyPage(current, 0)
        machine(longTapEnabled = false).reduce(ready, ReaderEvent.PageLongTapped(page)).first.dialog shouldBe
            ReaderDialog.PageActions(page)
    }

    /**
     * Phase 4 unit BN. The sheet's Note button sends only the note event, so the reducer is what
     * closes the sheet. A dismiss sent by the sheet after it was reduced behind the note dialog's
     * own opening on the API 26 AVD, and closed the note dialog.
     */
    @Test
    fun `the page sheet's note event closes the sheet and asks for the page's note`() {
        val page = readyPage(current, 1)
        val sheet = readyState(session, current).copy(dialog = ReaderDialog.PageActions(page))

        val (next, effects) = machine().reduce(sheet, ReaderEvent.EditNote(page))

        next.dialog shouldBe null
        effects shouldContainExactly listOf(ReaderEffect.OpenNoteEditor(current.chapter.id, 1))
    }

    /**
     * Phase 4 unit BN, verifier finding 1. A change to the note dialog's field is kept for a process
     * death while that dialog is open, and dropped otherwise: a late change from a dialog already
     * saved or cancelled would put its text back in the handle.
     */
    @Test
    fun `the note dialog's typed text is kept only while the note dialog is open`() {
        val ready = readyState(session, current)
        val open = ready.copy(dialog = ReaderDialog.PageNote(current.chapter.id, 1, ""))

        val (kept, keep) = machine().reduce(open, ReaderEvent.NoteTextChanged("half a line"))
        kept shouldBe open
        keep shouldContainExactly listOf(ReaderEffect.KeepNoteText("half a line"))

        val (closed, dropped) = machine().reduce(ready, ReaderEvent.NoteTextChanged("half a line"))
        closed shouldBe ready
        dropped shouldBe emptyList()
    }

    @Test
    fun `the brightness overlay is clamped`() {
        val ready = readyState(session, current)
        machine().reduce(ready, ReaderEvent.BrightnessChanged(500)).first.brightnessOverlayValue shouldBe 100
        machine().reduce(ready, ReaderEvent.BrightnessChanged(-500)).first.brightnessOverlayValue shouldBe -100
        machine().reduce(ready, ReaderEvent.BrightnessChanged(25)).first.brightnessOverlayValue shouldBe 25
    }

    // ---- Lifecycle ----

    @Test
    fun `resuming restarts the read timer`() {
        machine().reduce(readyState(session, current), ReaderEvent.Resumed).second shouldContainExactly
            listOf(ReaderEffect.RestartReadTimer(current))
    }

    @Test
    fun `pausing flushes progress and closes out the history row`() {
        machine().reduce(readyState(session, current), ReaderEvent.Paused).second shouldContainExactly
            listOf(ReaderEffect.FlushProgress(current), ReaderEffect.CommitHistory(current))
    }

    @Test
    fun `pausing in incognito flushes but writes no history`() {
        machine().reduce(readyState(session, current, incognito = true), ReaderEvent.Paused).second shouldContainExactly
            listOf(ReaderEffect.FlushProgress(current))
    }

    @Test
    fun `finishing flushes, closes out the history row, then deletes the chapters staged after reading`() {
        machine().reduce(readyState(session, current), ReaderEvent.Finishing).second shouldContainExactly listOf(
            ReaderEffect.FlushProgress(current),
            ReaderEffect.CommitHistory(current),
            ReaderEffect.DeletePendingChapters,
        )
    }

    @Test
    fun `finishing in incognito still deletes the chapters earlier sessions staged`() {
        machine().reduce(readyState(session, current, incognito = true), ReaderEvent.Finishing).second shouldContainExactly
            listOf(ReaderEffect.FlushProgress(current), ReaderEffect.DeletePendingChapters)
    }

    @Test
    fun `a page that does not finish the chapter carries no read back`() {
        val (_, effects) = machine().reduce(readyState(session, current), ReaderEvent.PageSelected(readyPage(current, 1)))
        effects.filterIsInstance<ReaderEffect.CommitProgress>().single().readBack shouldBe emptyList()
    }

    /**
     * The regression guard. Finishing is sent by `ReaderActivity.finish()` and by nothing else, so
     * answering it with the effect whose handler calls `finish()` closes a cycle that ran until the
     * process died: 45,759 finish requests and a heap of pending history writes in one run of the
     * reader's device suite. Finish belongs to the error paths, which close a reader on their own.
     */
    @Test
    fun `finishing never asks the activity to finish again`() {
        val ready = machine().reduce(readyState(session, current), ReaderEvent.Finishing).second
        val empty = machine().reduce(ReaderState(), ReaderEvent.Finishing).second

        ready.filterIsInstance<ReaderEffect.Finish>() shouldContainExactly emptyList()
        empty.filterIsInstance<ReaderEffect.Finish>() shouldContainExactly emptyList()
    }

    @Test
    fun `finishing before any chapter loaded writes nothing and still deletes what a killed process staged`() {
        machine().reduce(ReaderState(), ReaderEvent.Finishing).second shouldContainExactly
            listOf(ReaderEffect.DeletePendingChapters)
    }

    // ---- Memory ----

    @Test
    fun `a trim at running low or above degrades the preload window and trims the viewer`() {
        val ready = readyState(session, current)
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        ).forEach { level ->
            val (state, effects) = machine().reduce(ready, ReaderEvent.TrimMemory(level))
            state.preload shouldBe PreloadPolicy.DEGRADED
            effects shouldContainExactly listOf(ReaderEffect.TrimViewer)
        }
    }

    @Test
    fun `a trim below running low is ignored`() {
        val ready = readyState(session, current)
        @Suppress("DEPRECATION")
        val (state, effects) = machine().reduce(
            ready,
            ReaderEvent.TrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE),
        )
        state.preload shouldBe PreloadPolicy.DEFAULT
        effects.shouldBeEmpty()
    }

    // ---- The six invariants section 3_3 lists ----

    @Test
    fun `current page stays inside the page range while the reader is ready`() {
        val events = listOf(
            ReaderEvent.ChapterLoaded(current, requestedPage = 99),
            ReaderEvent.SliderReleased(99),
            ReaderEvent.SliderReleased(-5),
            ReaderEvent.PageSelected(readyPage(current, 2)),
            ReaderEvent.ViewerBuilt(0),
        )
        var state = ReaderState(session = session)
        events.forEach { event ->
            state = machine().reduce(state, event).first
            if (state.phase is ReaderPhase.Ready) {
                (state.currentPage in 0 until state.totalPages) shouldBe true
            }
        }
    }

    @Test
    fun `the current chapter is loaded whenever the reader is ready`() {
        val (state, _) = machine().reduce(
            ReaderState(session = session),
            ReaderEvent.ChapterLoaded(current, requestedPage = 0),
        )
        state.phase shouldBe ReaderPhase.Ready
        state.window!!.currChapter.state.shouldBeInstanceOf<ReaderChapter.State.Loaded>()
    }

    @Test
    fun `every released window is the one that was added before it`() {
        var state = ReaderState(session = session)
        val released = mutableListOf<ReaderEffect.RefChapters>()

        listOf(
            ReaderEvent.ChapterLoaded(current, 0),
            ReaderEvent.PageSelected(readyPage(first, 0)),
        ).forEach { event ->
            val (next, effects) = machine().reduce(state, event)
            released += effects.filterIsInstance<ReaderEffect.RefChapters>()
            state = next
        }

        released.size shouldBe 2
        released[0].release shouldBe null
        released[1].release shouldBe released[0].add
    }

    @Test
    fun `the viewer generation only moves on an effective mode change`() {
        val ready = readyState(session, current)
        val neutral = listOf(
            ReaderEvent.SetReadingMode(ReadingMode.WEBTOON),
            ReaderEvent.SetOrientation(ReaderOrientation.PORTRAIT),
            ReaderEvent.PreferencesChanged(ready.readingMode, ReaderOrientation.PORTRAIT),
            ReaderEvent.ToggleMenu,
            ReaderEvent.PageSelected(readyPage(current, 1)),
        )
        neutral.forEach { machine().reduce(ready, it).first.viewerGeneration shouldBe 0 }

        machine().reduce(ready, ReaderEvent.PreferencesChanged(ReadingMode.WEBTOON, ready.orientation))
            .first.viewerGeneration shouldBe 1
    }

    private fun sessionLoaded() = ReaderEvent.SessionLoaded(
        manga = manga(),
        session = session,
        initialChapterId = 2,
        incognito = false,
        effectiveMode = ReadingMode.RIGHT_TO_LEFT,
        effectiveOrientation = ReaderOrientation.FREE,
    )

    private fun <T> List<T>.shouldBeEmpty() {
        isEmpty() shouldBe true
    }
}
