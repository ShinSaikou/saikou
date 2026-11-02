package com.shinsaikou.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.chapter.interactor.GetBookmarkNotes
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.RemoveBookmarkNote
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.chapter.interactor.UpsertBookmarkNote
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.manga.model.MangaNotFoundException
import com.shinsaikou.domain.manga.interactor.SetMangaViewerFlags
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.bench.ReaderBenchHooks
import com.shinsaikou.reader.memory.MemoryInfoProvider
import com.shinsaikou.reader.memory.ReaderMemoryGuard
import com.shinsaikou.reader.pipeline.AnimatedImagePolicy
import com.shinsaikou.reader.pipeline.PageLoader
import com.shinsaikou.reader.pipeline.PageLoaderResolver
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.reader.state.ChapterWindow
import com.shinsaikou.reader.state.ModeResolver
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ProgressCommitter
import com.shinsaikou.reader.state.ReaderEffect
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderSession
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.reader.state.ReaderStateMachine
import com.shinsaikou.reader.state.SessionFilters
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * The reader's one view model. Contract 07 section 3.6.
 *
 * It owns the event loop, performs the effects that touch the database and the page pipeline, and
 * forwards the rest to the shell on [effects]. Every decision is [ReaderStateMachine]'s; this class
 * has no branching of its own beyond "which effect is this".
 *
 * Two additions to 3.6's constructor list, both forced by files that already exist:
 * [readerPreferences] and [animatedImagePolicy] are public, because `ReaderScreen` builds
 * `ReaderViewerHost` out of them and `:reader` has no other seam that carries them into the
 * composition.
 *
 * Two more, `context` and `memory`, are what part 1b section 4.10 asks for: this class owns
 * [ReaderMemoryGuard]'s whole life, because it is the only reader object that survives an activity
 * recreation.
 */
class ReaderViewModel @AssistedInject constructor(
    @Assisted private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getChapter: GetChapter,
    private val getScanlatorPriority: GetScanlatorPriority,
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val toggleBookmark: ToggleBookmark,
    private val getBookmarkNotes: GetBookmarkNotes,
    private val upsertBookmarkNote: UpsertBookmarkNote,
    private val removeBookmarkNote: RemoveBookmarkNote,
    private val pageLoaderResolver: PageLoaderResolver,
    private val downloadedChapters: DownloadedChapters,
    private val basePreferences: BasePreferences,
    progressCommitterFactory: ProgressCommitter.Factory,
    private val incognitoPolicy: IncognitoPolicy,
    val readerPreferences: ReaderPreferences,
    private val preloadPolicy: PreloadPolicy,
    val animatedImagePolicy: AnimatedImagePolicy,
    private val dispatchers: Dispatchers,
    private val context: Context,
    memory: MemoryInfoProvider,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(savedState: SavedStateHandle): ReaderViewModel
    }

    private val _state = MutableStateFlow(ReaderState(benchTag = savedState[ReaderIntents.KEY_BENCH_TAG]))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /**
     * A queue rather than a broadcast. The session loads before the screen's first composition has
     * started collecting, so a chapter that fails at once sends `Finish` to nobody; a shared flow
     * with no replay dropped it (36 of 42 counted runs, unit K3), and the reader stayed open with no
     * page, no spinner and no message. Queued, each effect waits for the screen, across an activity
     * recreation too, and goes to exactly one collector, as upstream's reader delivers its events.
     */
    private val _effects = Channel<ReaderEffect>(Channel.UNLIMITED)

    /** Only the effects part 2 section 5.2 lists; everything else is performed here. */
    val effects: Flow<ReaderEffect> = _effects.receiveAsFlow()

    /**
     * The entry's downloaded chapters, which the chapter transition marks. Empty until the session
     * names the entry, and followed only while the screen collects it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadedChapterIds: StateFlow<Set<Long>> = _state
        .map { it.manga?.id }
        .distinctUntilChanged()
        .flatMapLatest { mangaId -> mangaId?.let(downloadedChapters::subscribeByMangaId) ?: flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptySet())

    private val events = Channel<ReaderEvent>(Channel.UNLIMITED)

    /** Computed once per session: the display and the heap do not change while the reader is open. */
    private val preloadWindow: PreloadPolicy.Window by lazy { preloadPolicy.compute() }

    private val machine = ReaderStateMachine(
        longTapEnabled = { readerPreferences.readWithLongTap.get() },
        preloadWindow = { preloadWindow },
        showReadingMode = { readerPreferences.showReadingMode.get() },
    )

    private val committer = progressCommitterFactory.create(viewModelScope)

    /** One job per chapter id, cancelled when the chapter leaves the window. */
    private val loadJobs = mutableMapOf<Long, Job>()

    /**
     * The chapter a switch asked for while its preload was still fetching the page list. The
     * preload's job reports it as the current chapter when the list arrives, instead of as an
     * adjacent one. One at most: the owner's latest switch replaces any earlier one still waiting.
     * Touched only on the main thread, like [loadJobs].
     */
    private var switchWaitingOnPreload: Long? = null

    /**
     * The chapters whose page this session already decides: the one it opened on, placed by
     * [openingPage], and every chapter that has been on screen, which keeps the page it was left on
     * ([keepChapterPosition]). A load of any other chapter places it by its row instead (see [load]).
     * Touched only on the main thread, like [loadJobs].
     */
    private val shownChapters = mutableSetOf<Long>()

    private var openReported = false

    /**
     * Part 1b section 4.10. Registered below on the injected context, which is the application, so
     * exactly one guard exists per reader session however many times the activity is recreated, and
     * registration and unregistration are one pair with one owner. `ReaderActivity` therefore does
     * not override `onTrimMemory`: the framework delivers a trim to the activity and to every
     * registered callback, and running the trim twice would put it twice on the main thread, which
     * part 2 section 8.3 lists as a jank source.
     *
     * `onTrimLevel` is named rather than passed as a trailing lambda: the guard's last two
     * parameters are the injected clock and cache drop, so a trailing lambda binds to the wrong
     * one, which is what the shape recorded in `ARCHITECTURE.md` would do.
     */
    private val memoryGuard = ReaderMemoryGuard(
        context = context,
        memory = memory,
        onTrimLevel = { level -> send(ReaderEvent.TrimMemory(level)) },
    )

    init {
        context.registerComponentCallbacks(memoryGuard)
        viewModelScope.launch {
            for (event in events) {
                val (next, produced) = ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_REDUCE) {
                    machine.reduce(_state.value, event)
                }
                val previous = _state.value
                _state.value = next
                keepChapterPosition(next)
                saveReadingPosition(next)
                saveNoteDialog(previous, next)
                markBench(event, previous, next)
                produced.forEach { perform(it) }
                sampleMemory(event)
            }
        }
        loadSession()
    }

    fun send(event: ReaderEvent) {
        events.trySend(event)
    }

    override fun onCleared() {
        context.unregisterComponentCallbacks(memoryGuard)
        loadJobs.values.forEach(Job::cancel)
        loadJobs.clear()
        _state.value.window?.unref()
        super.onCleared()
    }

    // region Session

    private fun loadSession() {
        val mangaId = savedState.get<Long>(ReaderIntents.KEY_MANGA_ID)
        val chapterId = savedState.get<Long>(ReaderIntents.KEY_CHAPTER_ID)
        // The intent's page on a fresh start; after a process death, the page saveReadingPosition
        // last wrote, together with the chapter it was on. Absent means resume (see openingPage).
        val page = savedState.get<Int>(ReaderIntents.KEY_PAGE)
        val startedOn = savedState.get<Long>(ReaderIntents.KEY_START_CHAPTER_ID)
        // Present only after a process death that came while a note dialog was open.
        val noteDialog = restoredNoteDialog()
        if (mangaId == null || chapterId == null) {
            send(ReaderEvent.SessionFailed(IllegalArgumentException("reader started without a manga and a chapter")))
            return
        }
        viewModelScope.launch {
            // Ruling R69 item 4. A reader restored after process death comes back as the process
            // starts, before any screen has waited on the registry, and the chapter load below
            // resolves the entry's source by id: before the first load ends that is a stub, and the
            // chapter fails as a source that is not installed. The screen shows its spinner until
            // the session loads.
            sourceManager.isInitialized.first { it }
            var reopenNote: ReaderDialog.PageNote? = null
            val loaded = withContext(dispatchers.io) {
                runCatching {
                    // Named by its own key (R128 carry-over 2): a row removed while the process was
                    // dead reopens the reader into "That entry is no longer in the database.", as a
                    // row that leaves an open session does (observeChanges).
                    val manga = getManga.await(mangaId) ?: throw MangaNotFoundException(mangaId)
                    val chapters = withRequested(
                        getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true),
                        mangaId,
                        setOfNotNull(chapterId, startedOn),
                    )
                    val opening = openingChapter(chapters, restored = chapterId, startedOn = startedOn)
                        ?: throw ChapterGoneException(chapterId)
                    // Any chapter of the entry, not only the session's: a chapter finished at the
                    // long strip's boundary can be skipped as read now, and its note is still open.
                    reopenNote = noteDialog?.takeIf { dialog -> chapters.any { it.id == dialog.chapterId } }
                    val filters = SessionFilters(
                        skipRead = readerPreferences.skipRead.get(),
                        skipFiltered = readerPreferences.skipFiltered.get(),
                        skipDupe = readerPreferences.skipDupe.get(),
                        downloadedOnly = basePreferences.downloadedOnly.get(),
                    )
                    // A file lookup per manifest row, so it is only made when a filter reads it.
                    val downloadedFilter = filters.skipFiltered && manga.downloadedFilterRaw != Manga.SHOW_ALL
                    val downloaded = if (downloadedFilter || filters.downloadedOnly) {
                        downloadedChapters.subscribeByMangaId(mangaId).first()
                    } else {
                        emptySet()
                    }
                    // Only the duplicate pass reads the ranking, so it is read only when that runs.
                    val ranking = if (filters.skipDupe) getScanlatorPriority.await(mangaId) else emptyMap()
                    val session = ReaderSession.build(
                        manga = manga,
                        all = chapters,
                        initialChapterId = opening.id,
                        prefs = filters,
                        downloaded = downloaded,
                        ranking = ranking,
                    )
                    // The handle's page belongs to the chapter it was saved with, so a fallback
                    // to the chapter the reader was started on resumes that chapter instead.
                    session.chapterFor(opening.id).requestedPage =
                        openingPage(opening, page.takeIf { opening.id == chapterId })
                    ReaderEvent.SessionLoaded(
                        manga = manga,
                        session = session,
                        initialChapterId = opening.id,
                        incognito = incognitoPolicy.isIncognito(manga.source),
                        // The same derivation MangaChanged and PreferencesChanged use below, so
                        // the mode the session opens in and the mode it settles on after the first
                        // row or preference change can never disagree.
                        effectiveMode = ModeResolver.readingMode(
                            manga,
                            readerPreferences.defaultReadingMode.get(),
                        ),
                        effectiveOrientation = ModeResolver.orientation(
                            manga,
                            readerPreferences.defaultOrientationType.get(),
                        ),
                    )
                }
            }
            loaded
                .onSuccess {
                    shownChapters += it.initialChapterId
                    send(it)
                    reopenNote?.let { dialog -> send(ReaderEvent.ShowDialog(dialog)) }
                }
                .onFailure { error ->
                    val message = R.string.reader_chapter_gone.takeIf { error is ChapterGoneException }
                    send(ReaderEvent.SessionFailed(error, message))
                }
            if (loaded.isSuccess) {
                observeChanges(mangaId)
                observeNotes()
            }
        }
    }

    /**
     * The note dialog [saveNoteDialog] kept in the handle, or null. [loadSession] reopens it only
     * when the entry still has its chapter, since a note cannot be written to a chapter that is gone.
     */
    private fun restoredNoteDialog(): ReaderDialog.PageNote? {
        val chapterId = savedState.get<Long>(ReaderIntents.KEY_NOTE_CHAPTER_ID) ?: return null
        val pageIndex = savedState.get<Int>(ReaderIntents.KEY_NOTE_PAGE) ?: return null
        val text = savedState.get<String>(ReaderIntents.KEY_NOTE_TEXT) ?: return null
        return ReaderDialog.PageNote(chapterId, pageIndex, text)
    }

    /**
     * [filtered] with the [requested] chapters it lacks added back from the unfiltered list, as
     * upstream's reader does (upstream `2b4b8472b`). A notification, a History row or an Updates row
     * can name a chapter whose scanlator the owner excluded, and the filtered list alone closed the
     * reader with "That chapter is gone". Only the requested chapters come back; the rest of an
     * excluded scanlator's chapters stay out of the session.
     */
    private suspend fun withRequested(filtered: List<Chapter>, mangaId: Long, requested: Set<Long>): List<Chapter> {
        val missing = requested - filtered.mapTo(HashSet()) { it.id }
        if (missing.isEmpty()) return filtered
        return filtered + getChaptersByMangaId.await(mangaId).filter { it.id in missing }
    }

    /**
     * The chapter a session opens on. The handle's chapter first: the one the intent named, or
     * after a process death the one the reader was last on. A refresh while the process was dead
     * can have soft-deleted that one (ruling R68), and then the chapter the reader was started on
     * is the next best place, since the owner chose it. Null when neither is in the list.
     */
    private fun openingChapter(chapters: List<Chapter>, restored: Long, startedOn: Long?): Chapter? =
        chapters.firstOrNull { it.id == restored }
            ?: startedOn?.let { id -> chapters.firstOrNull { it.id == id } }

    /**
     * [page] when the caller named one (the intent's page, or the handle's after a process death).
     * Otherwise the page the chapter was left on, as `ReaderIntents.EXTRA_PAGE` and contract 07
     * section 4.1 promise, unless the chapter is finished: a read chapter opens at its first page,
     * as in upstream, whose `ChapterLoader` resumes only a chapter that is not read.
     */
    private fun openingPage(chapter: Chapter, page: Int?): Int = page ?: resumePage(chapter)

    /**
     * Where a chapter reached inside the session opens, by the same rule as [openingPage] with no
     * page named: the page it was left on while it is not read, its first page once it is.
     */
    private fun resumePage(chapter: Chapter): Int = if (chapter.read) 0 else chapter.lastPageRead.toInt()

    /** The chapter a session was asked to open is gone, and so is the one it was started on. */
    private class ChapterGoneException(chapterId: Long) :
        IllegalStateException("chapter $chapterId is no longer in the entry's chapter list")

    /**
     * The entry's row, followed for the session's life. It is the nullable flow (ruling R119 item 1):
     * Clear database can remove the row while the reader is open, from a second window on a large
     * screen, and the non-null flow threw from this collector and took the process with it. A row
     * that leaves ends the session the way any other failure does (phase 4 unit BF): the reducer
     * drops the progress still waiting to be written, since the chapters went with the entry, and
     * the reader closes naming the failure through R77's queue and R67's resolver. Nothing after the
     * first null belongs to the session. The first answer is the row the session just loaded,
     * unless it has already gone.
     */
    private fun observeChanges(mangaId: Long) {
        viewModelScope.launch {
            getManga.subscribeOrNull(mangaId)
                .transformWhile { manga ->
                    emit(manga)
                    manga != null
                }
                .withIndex()
                .collect { (index, manga) ->
                    when {
                        manga == null -> send(ReaderEvent.SessionFailed(MangaNotFoundException(mangaId)))
                        index > 0 -> send(
                            ReaderEvent.MangaChanged(
                                manga = manga,
                                effectiveMode = ModeResolver.readingMode(
                                    manga,
                                    readerPreferences.defaultReadingMode.get(),
                                ),
                                effectiveOrientation = ModeResolver.orientation(
                                    manga,
                                    readerPreferences.defaultOrientationType.get(),
                                ),
                            ),
                        )
                    }
                }
        }
        viewModelScope.launch {
            combine(
                readerPreferences.defaultReadingMode.changes(),
                readerPreferences.defaultOrientationType.changes(),
            ) { mode, orientation -> mode to orientation }
                .drop(1)
                .collect { (mode, orientation) ->
                    val manga = _state.value.manga ?: return@collect
                    send(
                        ReaderEvent.PreferencesChanged(
                            effectiveMode = ModeResolver.readingMode(manga, mode),
                            effectiveOrientation = ModeResolver.orientation(manga, orientation),
                        ),
                    )
                }
        }
    }

    /**
     * The one note collector (phase 4 unit BN): the page notes of the chapter on screen, followed
     * across chapter switches, so the bottom bar's marker is right for whichever page is shown.
     * `collectLatest` drops the previous chapter's subscription when the chapter changes; the
     * reducer also drops an answer for a chapter no longer on screen.
     */
    private fun observeNotes() {
        viewModelScope.launch {
            _state.map { it.currentChapter?.chapter?.id }
                .distinctUntilChanged()
                .filterNotNull()
                .collectLatest { chapterId ->
                    getBookmarkNotes.subscribe(chapterId).collect { notes ->
                        send(ReaderEvent.NotesChanged(chapterId, notes.associate { it.pageIndex.toInt() to it.note }))
                    }
                }
        }
    }

    /**
     * The chapter on screen keeps the page it is on as its requested page, so a session that comes
     * back to it (Previous after Next) opens it where it was left. The progress committer does the
     * same for every page it writes; this also covers the pages it never writes, in incognito and on
     * a page that failed. Once a chapter has been on screen its row is no longer read (see [load]).
     */
    private fun keepChapterPosition(state: ReaderState) {
        val chapter = state.currentChapter ?: return
        shownChapters += chapter.chapter.id
        if (state.scrubbing || state.currentPage < 0) return
        chapter.requestedPage = state.currentPage
    }

    /**
     * Keeps the handle on the chapter and page the reader shows, so a process the system killed in
     * the background reopens there when its task comes back (contract 07 part 2 section 9.2,
     * `tools/ci/reader_process_death.sh`). Both keys, because a chapter switch inside the session
     * moves the reader off the chapter the intent named; written from the reduced state rather than
     * from `CommitProgress`, which an incognito session never produces. A page the slider is
     * scrubbing through is not one the owner has settled on, so it waits for the release.
     */
    private fun saveReadingPosition(state: ReaderState) {
        val chapterId = state.currentChapter?.chapter?.id ?: return
        if (state.scrubbing || state.currentPage < 0) return
        if (savedState.get<Long>(ReaderIntents.KEY_CHAPTER_ID) == chapterId &&
            savedState.get<Int>(ReaderIntents.KEY_PAGE) == state.currentPage
        ) {
            return
        }
        savedState[ReaderIntents.KEY_CHAPTER_ID] = chapterId
        savedState[ReaderIntents.KEY_PAGE] = state.currentPage
    }

    /**
     * Keeps the handle on the note dialog while it is open, so a process the system killed while the
     * owner was writing a note reopens the dialog with the text typed so far when its task comes back
     * (phase 4 unit BN; [loadSession]). Opening writes the page and the text the field opens with,
     * each change to the field replaces the text ([ReaderEffect.KeepNoteText]), and the dialog
     * closing, by Save, Cancel or back, removes all three. Written on a change of dialog only, so the
     * keys also outlive a restored session's load, which runs with no dialog open.
     */
    private fun saveNoteDialog(previous: ReaderState, next: ReaderState) {
        val was = previous.dialog as? ReaderDialog.PageNote
        val now = next.dialog as? ReaderDialog.PageNote
        if (was?.chapterId == now?.chapterId && was?.pageIndex == now?.pageIndex) return
        if (now == null) {
            savedState.remove<Long>(ReaderIntents.KEY_NOTE_CHAPTER_ID)
            savedState.remove<Int>(ReaderIntents.KEY_NOTE_PAGE)
            savedState.remove<String>(ReaderIntents.KEY_NOTE_TEXT)
        } else {
            savedState[ReaderIntents.KEY_NOTE_CHAPTER_ID] = now.chapterId
            savedState[ReaderIntents.KEY_NOTE_PAGE] = now.pageIndex
            savedState[ReaderIntents.KEY_NOTE_TEXT] = now.note
        }
    }

    // endregion

    /**
     * The log markers of part 2 section 8.4, all of them, in one place. `reader.trim` is the one
     * exception: `ReaderMemoryGuard` emits it, because it also sees the synthesized levels the
     * activity never reports.
     */
    private fun markBench(event: ReaderEvent, previous: ReaderState, next: ReaderState) {
        when (event) {
            is ReaderEvent.SessionLoaded ->
                ReaderBenchHooks.mark("reader.session ok chapters=${event.session.chapters.size}")
            is ReaderEvent.SessionFailed -> ReaderBenchHooks.mark("reader.session fail")
            is ReaderEvent.ViewerBuilt ->
                ReaderBenchHooks.mark("reader.viewer mode=${next.readingMode.name} gen=${event.generation}")
            is ReaderEvent.ChapterLoaded -> ReaderBenchHooks.mark(
                "reader.chapter id=${event.chapter.chapter.id} pages=${event.chapter.pages?.size ?: 0}",
            )
            ReaderEvent.Finishing -> ReaderBenchHooks.mark("reader.close")
            else -> Unit
        }
        if (next.preload != previous.preload) {
            val w = next.preload
            ReaderBenchHooks.mark(
                "reader.preload window=${w.decodeAhead},${w.decodeBehind},${w.fetchAhead},${w.chapterPreloadAt}",
            )
        }
    }

    /**
     * The synthesized half of 4.10's level table, sampled once per page turn and once per chapter
     * load. Those are the two moments the heap grows, the read is two `Runtime` calls behind a rate
     * limit, and it is deliberately not an effect: the reducer has no opinion about the heap. It is
     * never called from a holder bind, which would put it inside `Reader.bindPage`.
     */
    private fun sampleMemory(event: ReaderEvent) {
        if (event is ReaderEvent.PageSelected || event is ReaderEvent.ChapterLoaded) {
            memoryGuard.sample()
        }
    }

    /**
     * `reader.ready` and the end of the `Reader.open` async section: the first page of the session
     * to reach [PageState.Ready]. Once per session, which is what makes it a cold-open measurement.
     */
    private fun awaitFirstReadyPage(page: ReaderPage) {
        if (openReported) return
        openReported = true
        viewModelScope.launch {
            page.statusFlow.first { it is PageState.Ready }
            ReaderBenchHooks.mark("reader.ready page=${page.number} of=${page.chapter.pages?.size ?: 0}")
            ReaderBenchHooks.endOpen()
        }
    }

    // region Effects

    private fun perform(effect: ReaderEffect) {
        when (effect) {
            is ReaderEffect.LoadChapter -> load(effect.chapter, adjacent = false)
            is ReaderEffect.PreloadChapter -> load(effect.chapter, adjacent = true)
            is ReaderEffect.MoveViewerToPage -> {
                awaitFirstReadyPage(effect.page)
                _effects.trySend(effect)
            }
            is ReaderEffect.RefChapters -> {
                ChapterWindow.applyRefs(effect.add, effect.release)
                cancelJobsOutside(effect.add)
            }
            is ReaderEffect.CommitProgress -> committer.onPageSelected(
                chapter = effect.chapter,
                pageIndex = effect.pageIndex,
                chapterFinished = effect.chapterFinished,
                immediate = effect.immediate,
                // The window the reduce that produced this effect left, so a page of the chapter
                // the reader just moved into names that chapter's next one (upstream's viewerChapters).
                nextChapterId = _state.value.window?.nextChapter?.chapter?.id,
                readBack = effect.readBack,
            )
            is ReaderEffect.FlushProgress -> viewModelScope.launch { committer.flush(effect.chapter) }
            is ReaderEffect.CommitHistory -> viewModelScope.launch { committer.commitHistory(effect.chapter) }
            // Launched while the view model is alive, as the finish's flush is, and queued behind it.
            ReaderEffect.DeletePendingChapters -> viewModelScope.launch { committer.deletePendingChapters() }
            ReaderEffect.DiscardProgress -> committer.discard()
            is ReaderEffect.RestartReadTimer -> committer.restartReadTimer(effect.chapter)
            is ReaderEffect.WriteBookmark -> viewModelScope.launch {
                toggleBookmark.await(effect.chapterId, effect.bookmark)
            }
            // Read from the database rather than from the collector's last answer, so a long press
            // on a page of the chapter beside the one on screen opens that page's own note too.
            is ReaderEffect.OpenNoteEditor -> viewModelScope.launch {
                val note = getBookmarkNotes.subscribe(effect.chapterId).first()
                    .firstOrNull { it.pageIndex == effect.pageIndex.toLong() }
                    ?.note
                    .orEmpty()
                send(ReaderEvent.ShowDialog(ReaderDialog.PageNote(effect.chapterId, effect.pageIndex, note)))
            }
            is ReaderEffect.WriteNote -> viewModelScope.launch {
                try {
                    upsertBookmarkNote.await(effect.chapterId, effect.pageIndex.toLong(), effect.note)
                } catch (e: Exception) {
                    if (!isConstraintFailure(e)) throw e
                    noteNotSaved(effect.chapterId, effect.pageIndex, effect.note, e)
                }
            }
            is ReaderEffect.RemoveNote -> viewModelScope.launch {
                try {
                    removeBookmarkNote.await(effect.chapterId, effect.pageIndex.toLong())
                } catch (e: Exception) {
                    if (!isConstraintFailure(e)) throw e
                    noteNotSaved(effect.chapterId, effect.pageIndex, text = null, e)
                }
            }
            is ReaderEffect.KeepNoteText -> savedState[ReaderIntents.KEY_NOTE_TEXT] = effect.text
            is ReaderEffect.WriteReadingMode -> viewModelScope.launch {
                setMangaViewerFlags.awaitSetReadingMode(effect.mangaId, effect.mode)
            }
            is ReaderEffect.WriteOrientation -> viewModelScope.launch {
                setMangaViewerFlags.awaitSetOrientation(effect.mangaId, effect.orientation)
            }
            else -> _effects.trySend(effect)
        }
    }

    /**
     * Loads [chapter]'s page list, then keeps collecting its loader's splits for as long as the
     * chapter stays in the window. The collection is what makes the job outlive the load.
     *
     * A running job is never started twice, and that includes a preload: its job stays active for
     * the whole collection, because the local and source loaders' splits never complete. So a
     * switch to a chapter that is already preloaded (the menu's Next chapter button, once the pager
     * has preloaded the next chapter) is answered here from the page list the preload already
     * holds, and a switch that lands while the preload is still fetching is handed to that job.
     * Before this, the switch was dropped and `loadingAdjacent` stayed true.
     *
     * The latest switch wins. A switch takes back any earlier one still waiting on a preload, so
     * Next then Previous ends on the previous chapter whichever load finishes first; and a load
     * cancelled because its chapter left the window reports nothing, so the chapter the window
     * released is not marked failed and no load error is shown for a switch the owner replaced.
     *
     * Only the fetch runs on the IO dispatcher. The chapter's loader, row and state are written back
     * here on the main thread, where the window's release runs too, and only while the job is still
     * active: a fetch that cannot be interrupted (a blocking file read, a call already on the wire)
     * finishes after the release has put the chapter back to `Wait`, and its `Loading` or `Loaded`
     * written from the IO thread left the chapter looking loaded, or loading for good, when it came
     * back into the window, so it was never preloaded again.
     *
     * A chapter the session has not placed yet ([shownChapters]) opens where its row says, read
     * when the chapter loads, so progress written earlier in the session counts: the page it was
     * left on while it is not read, its first page once it is. A preload reads it too, so Next and
     * Previous open there whether the chapter was preloaded or not. A preloaded chapter the owner
     * swipes into through its transition page is shown at the page the swipe reaches, as in upstream.
     */
    private fun load(chapter: ReaderChapter, adjacent: Boolean) {
        val id = chapter.chapter.id
        if (!adjacent) switchWaitingOnPreload = null
        if (loadJobs[id]?.isActive == true) {
            if (!adjacent) {
                if (chapter.state is ReaderChapter.State.Loaded) {
                    send(ReaderEvent.ChapterLoaded(chapter, chapter.requestedPage))
                } else {
                    switchWaitingOnPreload = id
                }
            }
            return
        }
        // Read on the main thread, like the set. A chapter joins it only by being on screen, which
        // takes a page list, and no other job for this chapter is live to produce one.
        val placed = id in shownChapters
        loadJobs[id] = viewModelScope.launch {
            chapter.state = ReaderChapter.State.Loading
            var resolved: PageLoader? = null
            val loaded = runCatching {
                withContext(dispatchers.io) {
                    val manga = _state.value.manga ?: error("no manga")
                    val source = sourceManager.getOrStub(manga.source)
                    val loader = chapter.pageLoader
                        ?: pageLoaderResolver.resolve(manga, chapter, source) { preloadWindow }
                            .also { resolved = it }
                    val pages = loader.getPages()
                    if (pages.isEmpty()) throw PageListEmptyException(chapter.chapter.id)
                    pages.forEach { it.chapter = chapter }
                    val row = if (placed) null else getChapter.await(id) ?: chapter.chapter
                    Fetched(loader, pages, row)
                }
            }
            // Cancelled by cancelJobsOutside: the chapter is out of the window and unref has
            // already put it back to Wait, so neither an outcome nor an event belongs to it now. A
            // loader this job resolved was never attached, so unref could not recycle it.
            if (!isActive) resolved?.recycle()
            ensureActive()
            resolved?.let { chapter.pageLoader = it }
            loaded.getOrNull()?.let { fetched ->
                fetched.row?.let { row ->
                    chapter.chapter = row
                    chapter.requestedPage = resumePage(row)
                }
                chapter.state = ReaderChapter.State.Loaded(fetched.pages)
            }
            val switchedTo = switchWaitingOnPreload == id
            if (switchedTo) switchWaitingOnPreload = null
            val asAdjacent = adjacent && !switchedTo
            loaded
                .onSuccess {
                    send(
                        if (asAdjacent) {
                            ReaderEvent.AdjacentChapterLoaded(chapter)
                        } else {
                            ReaderEvent.ChapterLoaded(chapter, chapter.requestedPage)
                        },
                    )
                }
                .onFailure { error ->
                    chapter.state = ReaderChapter.State.Error(error)
                    send(
                        if (asAdjacent) {
                            ReaderEvent.AdjacentChapterLoadFailed(chapter, error)
                        } else {
                            ReaderEvent.ChapterLoadFailed(chapter, error)
                        },
                    )
                }
            loaded.getOrNull()?.loader?.splits?.collectLatest { split ->
                send(ReaderEvent.PageSplit(split.page, split.parts))
            }
        }
    }

    /**
     * A note write the database refused on a constraint: the chapter's row left while the note
     * dialog was open, removed with its entry by Clear database from a second window (R128
     * carry-over 1). The reader is already closing in that case (R128 item 1), so all that is left
     * is to say so, through the reader's one message channel, and to keep what was typed: the note
     * dialog's keys are put back into the handle, which Save emptied when the dialog closed, so a
     * restore reopens the dialog with the text if the chapter is there again ([loadSession] reopens
     * it only then). Not while another note dialog is open, whose keys those are now. A removal has
     * no text to keep ([text] null).
     */
    private fun noteNotSaved(chapterId: Long, pageIndex: Int, text: String?, error: Exception) {
        logcat(LogPriority.WARN, error) { "Could not write the note of page $pageIndex of chapter $chapterId" }
        if (text != null && _state.value.dialog !is ReaderDialog.PageNote) {
            savedState[ReaderIntents.KEY_NOTE_CHAPTER_ID] = chapterId
            savedState[ReaderIntents.KEY_NOTE_PAGE] = pageIndex
            savedState[ReaderIntents.KEY_NOTE_TEXT] = text
        }
        _effects.trySend(ReaderEffect.ShowMessage(R.string.note_not_saved_entry_removed))
    }

    /** What a load fetched on the IO dispatcher; [row] is null for a chapter already placed. */
    private class Fetched(val loader: PageLoader, val pages: List<ReaderPage>, val row: Chapter?)

    private fun cancelJobsOutside(window: ViewerChapters) {
        val live = setOfNotNull(
            window.currChapter.chapter.id,
            window.prevChapter?.chapter?.id,
            window.nextChapter?.chapter?.id,
        )
        loadJobs.keys.filterNot(live::contains).forEach { id ->
            loadJobs.remove(id)?.cancel()
        }
        if (switchWaitingOnPreload !in live) switchWaitingOnPreload = null
    }

    // endregion
}

/** How long [ReaderViewModel.downloadedChapterIds] keeps following the manifest once nothing collects it. */
private const val STOP_TIMEOUT_MILLIS = 5_000L
