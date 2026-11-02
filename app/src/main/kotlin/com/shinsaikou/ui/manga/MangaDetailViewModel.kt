package com.shinsaikou.ui.manga

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.chapter.interactor.GetAvailableScanlators
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.interactor.SetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.manga.interactor.SetMangaViewerFlags
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences.ChapterSwipeAction
import com.shinsaikou.domain.preferences.AutoTrackState
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.source.toMangaInfo
import com.shinsaikou.domain.track.TrackProgress
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.chapter.ChapterDownload
import com.shinsaikou.ui.chapter.ChapterDownloadAction
import com.shinsaikou.ui.chapter.chapterDownloadOf
import com.shinsaikou.ui.chapter.startDownloadNow
import com.shinsaikou.ui.components.DownloadAction
import com.shinsaikou.ui.components.DuplicateEntry
import com.shinsaikou.ui.longPressSelection
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.track.repository.TrackRepository

/**
 * The chapter list, and the one screen that fetches one.
 *
 * Before this existed nothing in the app ever called `SyncChaptersWithSource`: the only caller was
 * the library update worker, whose periodic run cancels itself while `autoUpdateInterval` is 0, so
 * a freshly added entry stayed at zero chapters and the reader was unreachable. Opening an entry
 * that has never been fetched, or arriving from a browse listing, now refreshes once; anything else
 * is the owner asking for one, either by pulling or through the app bar's Refresh item.
 */
@AssistedInject
class MangaDetailViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val fromSource: Boolean,
    private val getMangaWithChapters: GetMangaWithChapters,
    private val getManga: GetManga,
    private val updateManga: UpdateManga,
    private val setMangaDetailsOverride: SetMangaDetailsOverride,
    private val updateMangaNotes: UpdateMangaNotes,
    private val coverActions: MangaCoverActions,
    private val refreshMangaFromSource: RefreshMangaFromSource,
    private val sourceManager: SourceManager,
    private val setReadStatus: SetReadStatus,
    private val toggleBookmark: ToggleBookmark,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getScanlatorPriority: GetScanlatorPriority,
    private val setScanlatorPriority: SetScanlatorPriority,
    private val getNextChapters: GetNextChapters,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val libraryPreferences: LibraryPreferences,
    private val readerPreferences: ReaderPreferences,
    private val uiPreferences: UiPreferences,
    private val downloadQueue: DownloadQueue,
    private val downloadManifest: DownloadManifest,
    private val downloadedChapters: DownloadedChapters,
    private val basePreferences: BasePreferences,
    private val trackPreferences: TrackPreferences,
    private val trackProgress: TrackProgress,
    private val autoDownload: AutoDownload,
    private val trackerManager: TrackerManager,
    private val trackRepository: TrackRepository,
    private val dispatchers: Dispatchers,
) : StateViewModel<MangaDetailViewModel.State>(State.Loading) {

    sealed interface State {
        data object Loading : State

        /**
         * No row has this id (phase 4 unit missing-entry): an entry intent that outlived its entry,
         * such as a new-chapters notification tapped after Clear database removed it, or a row
         * deleted while this screen was open. The screen says so where it stands, with its back
         * arrow, and never pops itself: the tablet library hosts it as a pane, where popping would
         * take the library with it. A row that comes back with this id is shown again.
         */
        data object NotFound : State

        data class Success(
            val manga: Manga,
            val chapters: List<Chapter>,
            val sourceName: String?,
            val sourceInstalled: Boolean,
            /** Every group the entry has chapters from, read unfiltered so an exclusion stays clearable. */
            val availableScanlators: Set<String> = emptySet(),
            val excludedScanlators: Set<String> = emptySet(),
            /** The title's scanlator ranking, top group first; empty when the title is unranked. */
            val scanlatorRanking: List<String> = emptyList(),
            /**
             * The per-entry reader overrides as they are stored, which is to say either of them may
             * be DEFAULT. The two global values sit beside them rather than being folded in,
             * because "revert to default" has to be able to tell an override from the default it
             * happens to equal.
             */
            val readingMode: ReadingMode = ReadingMode.DEFAULT,
            val orientation: ReaderOrientation = ReaderOrientation.DEFAULT,
            val defaultReadingMode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
            val defaultOrientation: ReaderOrientation = ReaderOrientation.FREE,
            val nextUnread: Chapter? = null,
            val refreshing: Boolean = false,
            val descriptionExpanded: Boolean = false,
            /** Absolute address for the WebView item, or null when the source has no web presence. */
            val webViewUrl: String? = null,
            val selection: Set<Long> = emptySet(),
            val dialog: Dialog? = null,
            /**
             * The chapters whose files are on disk, from [DownloadedChapters]: a manifest row whose
             * folder is gone is not in it (phase 2 decision Q4).
             */
            val downloaded: Set<Long> = emptySet(),
            /** This entry's rows in the download queue, by chapter id, with their page progress. */
            val queued: Map<Long, DownloadQueueEntry> = emptyMap(),
            /** `BasePreferences.downloadedOnly`, which forces the downloaded filter on, as in upstream. */
            val downloadedOnly: Boolean = false,
            /** The tracking button, which exists only while a tracker in the graph is configured. */
            val tracking: Tracking = Tracking(),
            /** Relative timestamps, `relative_time_v2`, for the rows' upload dates. */
            val relativeTime: Boolean = true,
            /** The stored date format, `app_date_format`, for the rows' upload dates. */
            val dateFormat: String = "",
        ) : State {
            /** A Local-source entry is on disk by definition and the download queue refuses it. */
            val isLocal: Boolean
                get() = manga.source == SourceIds.LOCAL

            /** The downloaded filter as it applies, which the More tab's switch can force on. */
            val downloadedFilter: TriState
                get() = if (downloadedOnly) TriState.ENABLED_IS else manga.downloadedFilter

            /**
             * Every term here is a control the owner can see and change in the chapter settings
             * sheet, or the More tab's switch that forces the downloaded row on and says so there.
             */
            val filterActive: Boolean
                get() = manga.unreadFilterRaw != Manga.SHOW_ALL ||
                    manga.bookmarkedFilterRaw != Manga.SHOW_ALL ||
                    downloadedFilter != TriState.DISABLED ||
                    excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val selectionMode: Boolean
                get() = selection.isNotEmpty()

            /**
             * The selected rows as chapters. Every bulk write takes whole [Chapter] values rather
             * than ids, because [SetReadStatus.await] skips rows already in the target state and it
             * can only do that while it can still see `read` and `lastPageRead`.
             */
            val selectedChapters: List<Chapter>
                get() = chapters.filter { it.id in selection }

            /**
             * What the chapter row's download control draws ([chapterDownloadOf]); a Local chapter
             * is None, because there is nothing to download or delete.
             */
            fun downloadOf(chapterId: Long): ChapterDownload =
                if (isLocal) ChapterDownload.None else chapterDownloadOf(chapterId, downloaded, queued)

            /**
             * Whether the rows draw their download control: not on a Local entry, whose chapters
             * are on disk by definition and which the queue refuses, and not while chapters are
             * selected, as in upstream (whose control stays drawn but disabled there; plan 2.6 has it
             * off). A source that is not installed keeps it, as it keeps the swipe and the
             * selection bar's Download; upstream hides it there too (`isLocalOrStub`).
             */
            val showsDownloadControl: Boolean
                get() = !isLocal && !selectionMode

            /** The selection bar's actions for what is selected now ([selectionActionsOf]). */
            val selectionActions: SelectionActions
                get() = selectionActionsOf(selectedChapters, ::downloadOf, downloadsOffered = !isLocal)

            /**
             * What a swipe set to [action] does to [chapter] now, upstream's `getSwipeAction` and
             * `executeChapterSwipeAction` in one: read and bookmark toggle, and a download starts,
             * cancels or deletes by the row's state. Null is no action, which the row draws as no
             * swipe: [ChapterSwipeAction.Disabled], a download on a Local entry, whose chapters
             * are on disk by definition and which the queue refuses, and any swipe in
             * [selectionMode]. Upstream's row still swipes while chapters are selected; Saikou's does
             * not (plan 2.6 keeps its part E off, R110 item 4): a row being selected is not a row
             * being acted on, and a download swipe on a downloaded chapter deletes with no dialog.
             */
            fun swipeOf(action: ChapterSwipeAction, chapter: Chapter): ChapterSwipe? {
                if (selectionMode) return null
                return when (action) {
                    ChapterSwipeAction.ToggleRead ->
                        if (chapter.read) ChapterSwipe.MarkUnread else ChapterSwipe.MarkRead
                    ChapterSwipeAction.ToggleBookmark ->
                        if (chapter.bookmark) ChapterSwipe.RemoveBookmark else ChapterSwipe.Bookmark
                    ChapterSwipeAction.Download -> if (isLocal) null else when (downloadOf(chapter.id)) {
                        ChapterDownload.None, ChapterDownload.Failed -> ChapterSwipe.StartDownload
                        ChapterDownload.Queued, is ChapterDownload.Downloading -> ChapterSwipe.CancelDownload
                        ChapterDownload.Downloaded -> ChapterSwipe.DeleteDownload
                    }
                    ChapterSwipeAction.Disabled -> null
                }
            }
        }
    }

    /**
     * The header's tracking button (phase 3 unit T3), upstream's: shown while a configured tracker is in
     * the graph ([offered]); [count] is the manga's rows whose tracker is signed in, and with no
     * tracker signed in ([anySignedIn] false) the button opens the Tracking settings, not the sheet.
     */
    data class Tracking(
        val offered: Boolean = false,
        val anySignedIn: Boolean = false,
        val count: Int = 0,
    )

    /**
     * One swipe's effect on one chapter row. The two removals and the delete are upstream's undo
     * swipes (`isUndo`), drawn by the swipe library in its own way; the effect is the same toggle.
     */
    enum class ChapterSwipe {
        MarkRead,
        MarkUnread,
        Bookmark,
        RemoveBookmark,
        StartDownload,
        CancelDownload,
        DeleteDownload,
    }

    /**
     * The two questions an add can raise, and the three surfaces that settle how the chapter list
     * is shown. Neither question is asked unless it has an answer worth acting on: a duplicate the
     * owner may not know about, and a category choice that only exists once the owner has made
     * categories and declined to name a default.
     *
     * One field holds one of these, so one modal is open at a time. Opening the scanlator filter
     * from inside the settings sheet therefore closes the sheet, which is what makes cancelling it
     * land back on the chapter list rather than on a sheet the owner had forgotten about.
     */
    sealed interface Dialog {
        /** Every library entry the duplicate check matched, in its order (source, then address). */
        data class DuplicateManga(val duplicates: List<DuplicateEntry>) : Dialog

        /**
         * Upstream's migrate dialog from the duplicate question (phase 4 unit MA): [current] is the
         * entry already in the library, [target] the one on this screen.
         */
        data class Migrate(val current: Manga, val target: Manga) : Dialog
        data class ChangeCategory(val categories: List<Category>, val selected: Set<Long>) : Dialog
        data object ChapterSettings : Dialog
        data object ScanlatorFilter : Dialog
        data object ReadingMode : Dialog

        /** The confirmation before a delete, holding only the chapters that have files to delete. */
        data class DeleteChapters(val chapterIds: List<Long>) : Dialog

        /** The track sheet (phase 3 unit T3), whose pages and state are [TrackSheetViewModel]'s. */
        data object TrackSheet : Dialog

        /**
         * The owner's title and description (phase 5 unit ED). The dialog reads what it prefills
         * from the entry itself, so the case carries nothing.
         */
        data object EditDetails : Dialog

        /**
         * The owner's notes on the entry, which backup, sync, migration and the library's `notes:`
         * search already carried with no way to see or write them. The dialog reads the entry's own
         * notes, so the case carries nothing.
         */
        data object Notes : Dialog

        /**
         * The cover viewer (phase 5 unit ED): [editable] while a cover can be set (a library or
         * Local entry), [customCover] while the entry has a custom cover file to delete.
         */
        data class Cover(val editable: Boolean, val customCover: Boolean) : Dialog
    }

    sealed interface Event {
        data class Snackbar(val messageRes: Int, val args: List<Any> = emptyList()) : Event
        data class OpenReader(val mangaId: Long, val chapterId: Long) : Event
        data class OpenWebView(val url: String, val sourceId: Long, val title: String) : Event

        /**
         * `autoUpdateTrackOnMarkRead` is `ASK`: upstream's snackbar, "Update trackers to chapter N?"
         * with an OK action, whose tap is [confirmTrackerUpdate] with [chapterNumber].
         */
        data class ConfirmTrackerUpdate(val chapterNumber: Double) : Event

        /** The tracking button with no tracker signed in: upstream opens the Tracking settings. */
        data object OpenTrackingSettings : Event

        /**
         * Upstream's "Add to library?" after a download starts for an entry not in the library, with
         * Add and a dismiss action; Add is [addToLibraryFromPrompt]. Once per screen.
         */
        data object PromptAddToLibrary : Event

        /** The library button took the entry out; the snackbar's Undo is [undoRemoveFromLibrary] with [removal]. */
        data class RemovedFromLibrary(val removal: Removal) : Event
    }

    /**
     * What a removal took away that a plain add would not give back: the date the entry joined the
     * library, which an add resets to now and which a date-added sort reads, and its categories as
     * they stood, which an add replaces with the default category.
     */
    data class Removal(val dateAdded: Long, val categoryIds: Set<Long>)

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /**
     * The swipe to the right, the row's start actions, and the swipe to the left, its end actions,
     * wired as upstream's `MangaViewModel` wires them: the start actions from `swipeToEndAction`, the
     * end actions from `swipeToStartAction`. Read once per open, as upstream reads them.
     */
    val chapterSwipeStartAction: ChapterSwipeAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction: ChapterSwipeAction = libraryPreferences.swipeToStartAction.get()

    /** Guards the one automatic refresh so a database emission cannot start a second fetch. */
    private var autoRefreshed = false

    /** Guards the chapter defaults, applied once per open to an entry not in the library. */
    private var defaultsApplied = false

    /**
     * Upstream's `hasPromptedToAddBefore`, kept here as a field where upstream keeps it in `State.Success`;
     * both last as long as the screen's view model. The add-to-library prompt after a download is
     * shown once for this screen, whatever the owner answered.
     *
     * On the manga route the view model lives in the back stack entry's store, which a rotation
     * keeps, so a rotation asks nothing new. The library's detail pane keeps it in a store of its
     * own that a rotation rebuilds (`LibraryDetailPane.kt`), and that is harmless here only because
     * the pane shows library entries alone and closes once its entry leaves the library
     * (`LibraryScreen.kt`'s `openEntry`), so the prompt never fires there. A pane that showed an
     * entry outside the library would need this flag saved.
     */
    private var hasPromptedToAddBefore = false

    /**
     * This entry's queue rows as last seen. The queue writes a row per page, so it is collected on
     * its own rather than re-running the projection, and a projection copies the last value in.
     */
    private var queued: Map<Long, DownloadQueueEntry> = emptyMap()

    /** The tracking button as last seen, collected on its own and copied in like [queued]. */
    private var tracking = Tracking(offered = trackerManager.trackers.any { it.isConfigured })

    /** The two date settings as last seen, `relativeTime` then `dateFormat`, copied in like [queued]. */
    private var dateSettings: Pair<Boolean, String> = true to ""

    /**
     * The entry's chapters as the last projection read them, before the chapter list's filters:
     * Upstream's `allChapters`, which the download menu draws from when `skipFiltered` is off.
     */
    private var allChapters: List<Chapter> = emptyList()

    /**
     * Whether the entry's flows are worth collecting: always until the screen first watches
     * [state], so nothing read before then waits on a subscriber, and after that while the screen
     * watches it and for [STOP_TIMEOUT_MS] after it stops, which a rotation or a quick switch away
     * stays inside.
     *
     * Upstream collects only while subscribed too (7ff97761b). With the reader open over this
     * screen, each page's progress write re-ran the whole projection here, the next chapter query
     * and the chapter list's filter and sort, for a screen nobody was looking at. Watching again
     * collects again, so the screen comes back with whatever changed meanwhile.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val watched: Flow<Boolean> = mutableState.subscriptionCount
        .map { it > 0 }
        .dropWhile { !it }
        .transformLatest { watching ->
            if (!watching) delay(STOP_TIMEOUT_MS)
            emit(watching)
        }
        .onStart { emit(true) }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> Flow<T>.whileWatched(): Flow<T> = watched.flatMapLatest { if (it) this else emptyFlow() }

    /** Everything the projection reads that arrives as a flow. */
    private data class Inputs(
        val manga: Manga,
        val chapters: List<Chapter>,
        val excludedScanlators: Set<String>,
        val availableScanlators: Set<String>,
        val downloaded: Set<Long>,
        val downloadedOnly: Boolean,
        val ranking: List<ScanlatorPriority>,
    )

    init {
        viewModelScope.launch {
            // Ruling R69 item 4. Every projection resolves the entry's source by id, and the first
            // one decides the automatic refresh. A screen restored after process death comes back
            // as the process starts, when an installed extension still reads as missing, so it
            // waits for the registry first and shows its loading state meanwhile.
            sourceManager.isInitialized.first { it }
            combine(
                getMangaWithChapters.subscribe(mangaId, applyScanlatorFilter = true),
                // The two scanlator collections travel together: combine takes five flows.
                combine(getExcludedScanlators.subscribe(mangaId), getScanlatorPriority.subscribe(mangaId), ::Pair),
                getAvailableScanlators.subscribe(mangaId),
                downloadedChapters.subscribeByMangaId(mangaId),
                basePreferences.downloadedOnly.withCurrent(),
            ) { entry, (excluded, ranking), available, downloaded, downloadedOnly ->
                entry?.let { (manga, chapters) ->
                    Inputs(manga, chapters, excluded, available, downloaded, downloadedOnly, ranking)
                }
            }
                .whileWatched()
                .flowOn(dispatchers.io)
                .collect { inputs -> if (inputs == null) mutableState.value = State.NotFound else project(inputs) }
        }
        viewModelScope.launch {
            downloadQueue.subscribe()
                .map { rows -> rows.filter { it.mangaId == mangaId }.associateBy { it.chapterId } }
                .distinctUntilChanged()
                .whileWatched()
                .flowOn(dispatchers.io)
                .collect { rows ->
                    queued = rows
                    mutableState.update { if (it is State.Success) it.copy(queued = rows) else it }
                }
        }
        viewModelScope.launch {
            combine(uiPreferences.relativeTime.withCurrent(), uiPreferences.dateFormat.withCurrent(), ::Pair)
                .distinctUntilChanged()
                .collect { settings ->
                    dateSettings = settings
                    val (relativeTime, dateFormat) = settings
                    mutableState.update {
                        if (it is State.Success) it.copy(relativeTime = relativeTime, dateFormat = dateFormat) else it
                    }
                }
        }
        if (tracking.offered) {
            viewModelScope.launch {
                combine(trackRepository.getTracksByMangaIdAsFlow(mangaId), trackerManager.loggedInTrackersFlow()) { tracks, signedIn ->
                    val ids = signedIn.mapTo(mutableSetOf()) { it.id }
                    tracking.copy(anySignedIn = ids.isNotEmpty(), count = tracks.count { it.trackerId in ids })
                }
                    .distinctUntilChanged()
                    .flowOn(dispatchers.io)
                    .collect { value ->
                        tracking = value
                        mutableState.update { if (it is State.Success) it.copy(tracking = value) else it }
                    }
            }
        }
    }

    private suspend fun project(inputs: Inputs) {
        val (manga, chapters, excludedScanlators, availableScanlators, downloaded, downloadedOnly, ranking) = inputs
        // Upstream applies the chapter defaults on every open of an entry that is not in the library
        // (`MangaViewModel`'s init; phase 4 unit CS, challenge c2 g3 A item 3). The write re-emits
        // the entry, so the projection after this one shows it with them.
        if (!defaultsApplied) {
            defaultsApplied = true
            if (!manga.favorite) withContext(dispatchers.io) { setMangaDefaultChapterFlags.await(manga) }
        }
        allChapters = chapters
        val source = sourceManager.get(manga.source)
        val sort = getChapterSort(manga)
        val isLocal = manga.source == SourceIds.LOCAL
        val downloadedFilter = if (downloadedOnly) TriState.ENABLED_IS else manga.downloadedFilter
        val visible = chapters
            .filter { chapter ->
                applyFilter(manga.unreadFilter) { !chapter.read } &&
                    applyFilter(manga.bookmarkedFilter) { chapter.bookmark } &&
                    applyFilter(downloadedFilter) { isLocal || chapter.id in downloaded }
            }
            .sortedWith { a, b -> sort(a, b) }
        val visibleIds = visible.mapTo(mutableSetOf()) { it.id }
        // Everything this projection needs off the main thread is read before the state is touched,
        // so the carry-over below sees the state as it is at the moment of the write. Reading it
        // into a local and assigning after the suspension dropped every write that landed inside
        // the window: a refresh the owner had just started, a row they had just selected, a dialog
        // they had just opened.
        // The resume button opens the copy of the next number the title's ranking prefers, and the
        // first copy, as before, when the title is unranked (phase 4 unit SC).
        val rankOf = ranking.associate { it.scanlator to it.priority }
        val (nextUnread, defaultReadingMode, defaultOrientation) = withContext(dispatchers.io) {
            Triple(
                getNextChapters.await(mangaId).removeDuplicates(null, rankOf).firstOrNull(),
                ReadingMode.fromFlags(readerPreferences.defaultReadingMode.get().toLong()),
                ReaderOrientation.fromFlags(readerPreferences.defaultOrientationType.get().toLong()),
            )
        }
        mutableState.update { current ->
            val previous = current as? State.Success
            State.Success(
                manga = manga,
                chapters = visible,
                sourceName = source?.name,
                sourceInstalled = source != null && source !is StubSource,
                availableScanlators = availableScanlators,
                excludedScanlators = excludedScanlators,
                scanlatorRanking = ranking.map { it.scanlator },
                readingMode = ReadingMode.fromFlags(manga.viewerFlags),
                orientation = ReaderOrientation.fromFlags(manga.viewerFlags),
                defaultReadingMode = defaultReadingMode,
                defaultOrientation = defaultOrientation,
                nextUnread = nextUnread,
                refreshing = previous?.refreshing == true,
                descriptionExpanded = previous?.descriptionExpanded == true,
                webViewUrl = webViewUrl(source, manga),
                // A bulk write re-emits the whole list, and rebuilding Success from nothing would
                // clear the selection and close an open dialog while the owner is still using them.
                // Both survive a re-projection; the selection loses only ids a filter change took
                // off screen.
                selection = previous?.selection.orEmpty().intersect(visibleIds),
                dialog = previous?.dialog,
                downloaded = downloaded,
                queued = queued,
                downloadedOnly = downloadedOnly,
                tracking = tracking,
                relativeTime = dateSettings.first,
                dateFormat = dateSettings.second,
            )
        }
        if (!autoRefreshed && (fromSource || !manga.initialized || chapters.isEmpty()) && rowExists()) {
            autoRefreshed = true
            startRefresh(manual = fromSource, ownerAsked = false)
        }
    }

    /**
     * Whether the entry's row is in the database now, read in the step that would start the
     * automatic refresh (phase 4 unit BF, R119 carry-over 2). The entry and its chapters arrive on
     * two flows that re-query apart, so Clear database's delete can reach one projection as the old
     * entry with the empty chapter list the delete left, before the entry's null; an empty list
     * starts the automatic refresh, whose failure was a stray snackbar under the not-found state.
     * By the time that list arrives the row is already gone, so this read answers for it. A
     * projection that finds no row leaves the one automatic refresh unused.
     */
    private suspend fun rowExists(): Boolean = withContext(dispatchers.io) { getManga.await(mangaId) } != null

    /**
     * The entry's own page when the source can build one, otherwise the source's landing page.
     *
     * `homeUrl` is the gate rather than `getMangaUrl`, because a source with no web presence at all
     * (the Local source, and any source not backed by an HTTP client) returns null from both, and a
     * source that has a site but no per-entry address still has somewhere worth opening.
     */
    private fun webViewUrl(source: Source?, manga: Manga): String? {
        val home = source?.homeUrl ?: return null
        return source.getMangaUrl(manga.toMangaInfo()) ?: home
    }

    /**
     * Details then chapters, in that order, because the chapter sanitizer strips the title from
     * each chapter name and a stale title leaves the prefix behind.
     *
     * A failure never replaces a list that is already on screen: it becomes a snackbar and the rows
     * stay as they were, which is what makes an offline open of a fetched entry usable.
     */
    fun refresh(manual: Boolean = true) = startRefresh(manual, ownerAsked = true)

    /**
     * [ownerAsked] is a pull or the Refresh item, the only refresh whose new chapters go to
     * [AutoDownload] (phase 3 unit AD), with the queue started as upstream's manual fetch does. The
     * automatic refresh on open downloads nothing, even arriving from a browse listing, where it
     * counts as manual for the fetch interval: upstream's first load is not a manual fetch either.
     */
    private fun startRefresh(manual: Boolean, ownerAsked: Boolean) {
        val current = state.value as? State.Success ?: return
        if (current.refreshing) return
        mutableState.value = current.copy(refreshing = true)
        viewModelScope.launch {
            try {
                val source = sourceManager.get(current.manga.source)
                if (source == null || source is StubSource) {
                    eventChannel.send(Event.Snackbar(R.string.error_source_not_installed))
                    return@launch
                }
                // Details then chapters, the chapters asked with the row the details left
                // ([RefreshMangaFromSource], shared with migration since phase 4 unit MA).
                val (manga, newChapters) = refreshMangaFromSource(
                    source = source,
                    manga = current.manga,
                    fetchDetails = true,
                    fetchChapters = true,
                    manualFetch = manual,
                )
                if (ownerAsked && newChapters.isNotEmpty()) queueNewChapters(manga, newChapters)
                eventChannel.send(
                    when {
                        newChapters.isNotEmpty() -> Event.Snackbar(R.string.manga_new_chapters, listOf(newChapters.size))
                        manual -> Event.Snackbar(R.string.manga_up_to_date)
                        else -> return@launch
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                eventChannel.send(Event.Snackbar(R.string.manga_refresh_failed, listOf(e)))
            } finally {
                mutableState.update { if (it is State.Success) it.copy(refreshing = false) else it }
            }
        }
    }

    /** A failure here is logged: the chapters are in, and the refresh did what it was asked. */
    private suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>) {
        try {
            withContext(dispatchers.io) { autoDownload.queueNewChapters(manga, chapters, autoStart = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not queue the new chapters of entry ${manga.id}" }
        }
    }

    /** Opens the entry's page on the source's own site. Silent when the source has no web presence. */
    fun openInWebView() {
        val current = state.value as? State.Success ?: return
        val url = current.webViewUrl ?: return
        viewModelScope.launch {
            eventChannel.send(Event.OpenWebView(url, current.manga.source, current.manga.displayTitle))
        }
    }

    /**
     * Removing writes immediately, and its snackbar offers Undo ([undoRemoveFromLibrary]). Adding
     * asks first, and this is the only entry point that can open either dialog: the database is not
     * touched until whatever it asked has been answered, so an owner who dismisses the duplicate
     * question ends up exactly where they started rather than with a half added entry.
     */
    fun toggleFavorite() {
        val current = state.value as? State.Success ?: return
        val manga = current.manga
        viewModelScope.launch {
            if (manga.favorite) {
                val categoryIds = withContext(dispatchers.io) { getCategories.await(manga.id) }
                    .mapTo(mutableSetOf()) { it.id }
                updateManga.awaitUpdateFavorite(manga.id, false)
                eventChannel.send(Event.RemovedFromLibrary(Removal(manga.dateAdded, categoryIds)))
                return@launch
            }
            // Every match, as upstream lists them, each with its source and chapter count so the owner
            // can tell the copies apart; a source that is gone keeps the name it was last seen with.
            val duplicates = withContext(dispatchers.io) {
                getDuplicateLibraryManga(manga).map { match ->
                    val source = sourceManager.getOrStub(match.manga.source)
                    DuplicateEntry(
                        manga = match.manga,
                        chapterCount = match.chapterCount,
                        sourceName = source.name,
                        sourceInstalled = source !is StubSource,
                    )
                }
            }
            if (duplicates.isNotEmpty()) {
                showDialog(Dialog.DuplicateManga(duplicates))
                return@launch
            }
            add(manga)
        }
    }

    /**
     * The removal snackbar's Undo: the entry back in the library with the date it was first added,
     * so a date-added sort puts it where it was, and with its categories. A removal leaves the
     * category rows in place, so they are written only when something changed them meanwhile, and
     * an entry already added back by then is left alone.
     */
    fun undoRemoveFromLibrary(removal: Removal) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                val manga = getManga.await(mangaId) ?: return@withContext
                if (manga.favorite) return@withContext
                updateManga.await(MangaUpdate(id = mangaId, favorite = true, dateAdded = removal.dateAdded))
                val categoryIds = getCategories.await(mangaId).mapTo(mutableSetOf()) { it.id }
                if (categoryIds != removal.categoryIds) setMangaCategories.await(mangaId, removal.categoryIds.toList())
            }
        }
    }

    /** "Add anyway" from the duplicate dialog: the same add, with the duplicate question answered. */
    fun addAnyway() {
        val manga = (state.value as? State.Success)?.manga ?: return
        dismissDialog()
        viewModelScope.launch { add(manga) }
    }

    /**
     * "Migrate" from the duplicate dialog, upstream's tap on a duplicate: the migrate dialog, moving
     * [duplicateId], the entry picked in the dialog and already in the library, onto this one.
     */
    fun showMigrateDialog(duplicateId: Long) {
        val current = state.value as? State.Success ?: return
        val duplicates = (current.dialog as? Dialog.DuplicateManga)?.duplicates ?: return
        val duplicate = duplicates.firstOrNull { it.manga.id == duplicateId }?.manga ?: return
        showDialog(Dialog.Migrate(current = duplicate, target = current.manga))
    }

    /** The migrate dialog closed on a migration that threw; it has logged the cause. */
    fun migrationFailed() {
        dismissDialog()
        viewModelScope.launch { eventChannel.send(Event.Snackbar(R.string.migration_single_failed)) }
    }

    /**
     * Either applies the named default category, or hands the choice back to the owner.
     *
     * The picker only appears when there is a real choice to make: no default named, and at least
     * one category that is not the built in Uncategorized row. Showing it to an owner who has never
     * made a category would be a dialog with one disabled button.
     */
    private suspend fun add(manga: Manga) {
        val default = libraryPreferences.defaultCategory.get().toLong()
        val categories = withContext(dispatchers.io) { getCategories.await() }
        val choosable = categories.filterNot { it.isSystemCategory }
        if (default < 0 && choosable.isNotEmpty()) {
            val selected = withContext(dispatchers.io) { getCategories.await(manga.id) }.mapTo(mutableSetOf()) { it.id }
            showDialog(Dialog.ChangeCategory(choosable, selected))
            return
        }
        updateManga.awaitUpdateFavorite(manga.id, true)
        if (default >= 0 && categories.any { it.id == default }) {
            setMangaCategories.await(manga.id, listOf(default))
        }
        eventChannel.send(Event.Snackbar(R.string.manga_added_to_library))
    }

    /**
     * Confirms the category picker. The favorite flag is only written while the entry is not
     * already one, because `awaitUpdateFavorite(id, true)` resets `dateAdded` to now and a later
     * category edit has no business moving an entry to the top of a date sorted library.
     */
    fun setCategories(categoryIds: List<Long>) {
        val manga = (state.value as? State.Success)?.manga ?: return
        dismissDialog()
        viewModelScope.launch {
            val adding = !manga.favorite
            if (adding) updateManga.awaitUpdateFavorite(manga.id, true)
            setMangaCategories.await(manga.id, categoryIds)
            if (adding) eventChannel.send(Event.Snackbar(R.string.manga_added_to_library))
        }
    }

    private fun showDialog(dialog: Dialog) {
        mutableState.update { if (it is State.Success) it.copy(dialog = dialog) else it }
    }

    /** The header's tracking button, upstream's: the sheet, or the Tracking settings with no tracker signed in. */
    fun openTracking() {
        val current = state.value as? State.Success ?: return
        if (current.tracking.anySignedIn) {
            showDialog(Dialog.TrackSheet)
        } else {
            viewModelScope.launch { eventChannel.send(Event.OpenTrackingSettings) }
        }
    }

    fun dismissDialog() {
        mutableState.update { if (it is State.Success) it.copy(dialog = null) else it }
    }

    fun openChapter(chapter: Chapter) {
        viewModelScope.launch { eventChannel.send(Event.OpenReader(mangaId, chapter.id)) }
    }

    /**
     * The silent return is deliberate. The button that calls this is only composed while
     * `nextUnread` is not null, so reaching it with nothing to read means the state changed under
     * a tap already in flight, and a snackbar for that would report a race to the owner as if it
     * were an error.
     */
    fun continueReading() {
        val next = (state.value as? State.Success)?.nextUnread ?: return
        openChapter(next)
    }

    fun markRead(chapter: Chapter, read: Boolean) {
        viewModelScope.launch {
            val result = setReadStatus.await(read, chapter)
            if (read) pushMarkedRead(result)
        }
    }

    /**
     * Upstream's tracker push after a mark read (`MangaViewModel.markChaptersRead`), through the same
     * record-then-enqueue path as the reader (phase 3 unit T1). The number is the highest chapter
     * this mark actually wrote, never the one the owner tapped: "mark previous as read" on the last
     * chapter pushes the one before it (challenge 5 T1 item 1). A write that marked nothing, or
     * marked unread, pushes nothing.
     *
     * `autoUpdateTrackOnMarkRead` decides: `ALWAYS` records and says so, `ASK` shows upstream's
     * snackbar when a push would be recorded, and `NEVER` does nothing. Upstream refreshes every track
     * from the network before either; here the retry worker refreshes each row before it pushes,
     * and nothing on this path waits on the network.
     *
     * Only this screen pushes on a mark read. Updates, Library and the notification's action mark
     * read without pushing, as in upstream, whose only `TrackChapter` callers are its reader and this
     * screen's view model.
     */
    private suspend fun pushMarkedRead(result: SetReadStatus.Result) {
        val marked = (result as? SetReadStatus.Result.Success)?.chapters.orEmpty()
        val chapterNumber = marked.maxOfOrNull { it.chapterNumber } ?: return
        try {
            when (trackPreferences.autoUpdateTrackOnMarkRead.get()) {
                AutoTrackState.NEVER -> Unit
                AutoTrackState.ALWAYS -> {
                    val recorded = withContext(dispatchers.io) { trackProgress.record(mangaId, chapterNumber) }
                    if (recorded > 0) {
                        eventChannel.send(Event.Snackbar(R.string.trackers_updated_summary, listOf(chapterNumber.toInt())))
                    }
                }
                AutoTrackState.ASK -> {
                    val pushable = withContext(dispatchers.io) { trackProgress.wouldRecord(mangaId, chapterNumber) }
                    if (pushable) eventChannel.send(Event.ConfirmTrackerUpdate(chapterNumber))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not record the tracker push after a mark read" }
        }
    }

    /** The OK action of [Event.ConfirmTrackerUpdate]'s snackbar. */
    fun confirmTrackerUpdate(chapterNumber: Double) {
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) { trackProgress.record(mangaId, chapterNumber) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not record the confirmed tracker push" }
            }
        }
    }

    fun toggleChapterBookmark(chapter: Chapter) {
        viewModelScope.launch { toggleBookmark.await(chapter.id, !chapter.bookmark) }
    }

    /**
     * A chapter row swiped with [action] set for that side (phase 4 unit CS), upstream's
     * `chapterSwipe`: the effect is [State.Success.swipeOf] as the state stands. Read goes through
     * [markRead], so a swipe that marks read pushes to trackers as the selection bar's mark does;
     * bookmark through [toggleChapterBookmark]; a download starts, cancels or deletes the one chapter, with
     * no dialog, as upstream's swipe does.
     */
    fun swipeChapter(chapter: Chapter, action: ChapterSwipeAction) {
        val current = state.value as? State.Success ?: return
        when (current.swipeOf(action, chapter)) {
            null -> Unit
            ChapterSwipe.MarkRead -> markRead(chapter, read = true)
            ChapterSwipe.MarkUnread -> markRead(chapter, read = false)
            ChapterSwipe.Bookmark, ChapterSwipe.RemoveBookmark -> toggleChapterBookmark(chapter)
            ChapterSwipe.StartDownload -> startDownloadNow(current.manga, chapter)
            ChapterSwipe.CancelDownload -> cancelDownload(chapter)
            ChapterSwipe.DeleteDownload -> deleteDownload(chapter)
        }
    }

    /** Upstream's start now, through the one path both screens share ([DownloadQueue.startDownloadNow]). */
    private fun startDownloadNow(manga: Manga, chapter: Chapter) {
        viewModelScope.launch {
            downloadQueue.startDownloadNow(manga, chapter)
            promptToAddAfterDownload()
        }
    }

    /**
     * A tap on a chapter row's download control (plan 2.6 part D), upstream's
     * `runChapterDownloadActions` for one chapter: a download queues it and starts the queue, as the
     * selection bar's Download does; start now is the swipe's; cancel removes its queue row; delete
     * removes its files and manifest row with no dialog, as the swipe does. Nothing happens where
     * the row draws no control ([State.Success.showsDownloadControl]).
     */
    fun runChapterDownloadAction(chapter: Chapter, action: ChapterDownloadAction) {
        val current = state.value as? State.Success ?: return
        if (!current.showsDownloadControl) return
        when (action) {
            ChapterDownloadAction.START -> viewModelScope.launch {
                downloadQueue.enqueue(current.manga, listOf(chapter), autoStart = true)
                promptToAddAfterDownload()
            }
            ChapterDownloadAction.START_NOW -> startDownloadNow(current.manga, chapter)
            ChapterDownloadAction.CANCEL -> cancelDownload(chapter)
            ChapterDownloadAction.DELETE -> deleteDownload(chapter)
        }
    }

    /**
     * The end of upstream's `startDownload` (`MangaViewModel.kt:660-671`), which every download this
     * screen starts reaches once it is queued: the selection bar's ([downloadSelected]), the app
     * bar's menu ([download]), a swipe's start now ([startDownloadNow]) and the row's download
     * control ([runChapterDownloadAction]). An entry not in the
     * library, on a screen that has not asked yet, gets the question; asking is recorded before the
     * question goes out, so a second download while it is on screen asks nothing.
     */
    private suspend fun promptToAddAfterDownload() {
        val current = state.value as? State.Success ?: return
        if (current.manga.favorite || hasPromptedToAddBefore) return
        hasPromptedToAddBefore = true
        eventChannel.send(Event.PromptAddToLibrary)
    }

    /**
     * The prompt's Add: the favourite button's add ([toggleFavorite]), with its duplicate question
     * and category choice, while the entry is still not in the library (upstream's `!isFavorited`
     * check after the snackbar), so an entry added meanwhile is not taken out again.
     */
    fun addToLibraryFromPrompt() {
        val current = state.value as? State.Success ?: return
        if (current.manga.favorite) return
        toggleFavorite()
    }

    /** Removes the chapter's queue row, stops it if it is in flight, and deletes its partial folder. */
    private fun cancelDownload(chapter: Chapter) {
        viewModelScope.launch { downloadQueue.remove(listOf(chapter.id)) }
    }

    /** The chapter's files, then its manifest row, with no confirmation, as upstream's swipe deletes. */
    private fun deleteDownload(chapter: Chapter) {
        viewModelScope.launch { downloadManifest.delete(listOf(chapter.id)) }
    }

    fun toggleSelection(chapterId: Long) {
        mutableState.update { current ->
            if (current !is State.Success) return@update current
            val selection = if (chapterId in current.selection) {
                current.selection - chapterId
            } else {
                current.selection + chapterId
            }
            current.copy(selection = selection)
        }
    }

    /**
     * A long press on [chapterId]'s row, upstream's `toggleSelection(fromLongPress = true)`: see
     * [longPressSelection]. The list's order is [State.Success.chapters], which the rows follow.
     */
    fun longPressChapter(chapterId: Long) {
        mutableState.update { current ->
            if (current !is State.Success) return@update current
            current.copy(selection = longPressSelection(current.chapters.map { it.id }, current.selection, chapterId))
        }
    }

    fun selectAll() {
        mutableState.update { current ->
            if (current is State.Success) {
                current.copy(selection = current.chapters.mapTo(mutableSetOf()) { it.id })
            } else {
                current
            }
        }
    }

    fun invertSelection() {
        mutableState.update { current ->
            if (current is State.Success) {
                current.copy(selection = current.chapters.mapTo(mutableSetOf()) { it.id } - current.selection)
            } else {
                current
            }
        }
    }

    fun clearSelection() {
        mutableState.update { if (it is State.Success) it.copy(selection = emptySet()) else it }
    }

    /**
     * Bulk mark read or unread. The selection is cleared before the write rather than after it,
     * because the write re-emits the list and an owner watching rows change state under a bar that
     * is still lit reads it as a bar that did nothing.
     */
    fun markSelectedRead(read: Boolean) {
        val selected = (state.value as? State.Success)?.selectedChapters.orEmpty()
        if (selected.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            val result = setReadStatus.await(read, *selected.toTypedArray())
            if (read) pushMarkedRead(result)
        }
    }

    /**
     * Marks everything before the selection read, in one pass.
     *
     * `awaitAllBefore` marks every chapter that sorts before its argument, so the union over a
     * whole selection is whatever sorts before the latest selected chapter. Picking that one and
     * calling once saves a database write per selected row and lands on the same set.
     */
    fun markSelectedPreviousRead() {
        val current = state.value as? State.Success ?: return
        val selected = current.selectedChapters
        if (selected.isEmpty()) return
        val sort = getChapterSort(current.manga, sortDescending = false)
        val latest = selected.maxWithOrNull { a, b -> sort(a, b) } ?: return
        clearSelection()
        viewModelScope.launch { pushMarkedRead(setReadStatus.awaitAllBefore(latest, read = true)) }
    }

    /**
     * Queues the selection, oldest first so the chapters arrive in reading order, and starts the
     * worker. The queue skips what is already downloaded and refuses the Local source outright; the
     * bar offers neither action on a Local entry, so the second is only a guard.
     */
    fun downloadSelected() {
        val current = state.value as? State.Success ?: return
        val selected = current.selectedChapters
        if (selected.isEmpty() || current.isLocal) return
        val sort = getChapterSort(current.manga, sortDescending = false)
        clearSelection()
        viewModelScope.launch {
            downloadQueue.enqueue(current.manga, selected.sortedWith { a, b -> sort(a, b) }, autoStart = true)
            promptToAddAfterDownload()
        }
    }

    /**
     * The bar's download menu (phase 3 unit AD), upstream's `runDownloadAction` as written: the next N
     * are the unread chapters that are neither downloaded nor in the queue, in the entry's reading
     * order ([getChapterSort], reversed when the entry sorts descending), and taken from the list as
     * filtered when `skipFiltered` is on (upstream's `filteredChapters`) or from every chapter when it
     * is off; Unread is all of them, and Bookmarked the bookmarked ones that are neither downloaded
     * nor queued. They are queued oldest source position first, the order upstream's `Downloader` gives
     * them, and the queue is started. A Local entry offers no menu; this is only a guard.
     */
    fun download(action: DownloadAction) {
        val current = state.value as? State.Success ?: return
        if (current.isLocal) return
        val pool = if (readerPreferences.skipFiltered.get()) current.chapters else allChapters
        val notDownloaded = pool.filter { it.id !in current.downloaded && it.id !in current.queued }
        val unread = notDownloaded.filter { !it.read }
        val unreadSorted = unread.sortedWith(getChapterSort(current.manga))
            .let { if (current.manga.sortDescending()) it.reversed() else it }
        val chosen = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> unreadSorted.take(1)
            DownloadAction.NEXT_5_CHAPTERS -> unreadSorted.take(5)
            DownloadAction.NEXT_10_CHAPTERS -> unreadSorted.take(10)
            DownloadAction.NEXT_25_CHAPTERS -> unreadSorted.take(25)
            DownloadAction.UNREAD_CHAPTERS -> unread
            DownloadAction.BOOKMARKED_CHAPTERS -> notDownloaded.filter { it.bookmark }
        }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            downloadQueue.enqueue(current.manga, chosen.sortedByDescending { it.sourceOrder }, autoStart = true)
            promptToAddAfterDownload()
        }
    }

    /**
     * Asks before deleting, and only about the selected chapters that have files: a selection with
     * none says so rather than asking to delete nothing. The selection stays until the owner
     * answers, so cancelling lands back where they were.
     */
    fun deleteSelected() {
        val current = state.value as? State.Success ?: return
        if (current.isLocal) return
        val ids = current.selectedChapters.map { it.id }.filter { it in current.downloaded }
        if (ids.isEmpty()) {
            viewModelScope.launch { eventChannel.send(Event.Snackbar(R.string.chapter_delete_none)) }
            return
        }
        showDialog(Dialog.DeleteChapters(ids))
    }

    /** The confirmation's delete: files first, then the manifest rows, both inside [DownloadManifest]. */
    fun confirmDeleteChapters() {
        val dialog = (state.value as? State.Success)?.dialog as? Dialog.DeleteChapters ?: return
        dismissDialog()
        clearSelection()
        viewModelScope.launch { downloadManifest.delete(dialog.chapterIds) }
    }

    /** Bulk bookmark or unbookmark. One call per chapter is all [ToggleBookmark] offers. */
    fun bookmarkSelected(bookmark: Boolean) {
        val selected = (state.value as? State.Success)?.selectedChapters.orEmpty()
        if (selected.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            selected.forEach { chapter -> toggleBookmark.await(chapter.id, bookmark) }
        }
    }

    /** The three chapter filter toggles, so a row in the sheet names one instead of a flag word. */
    enum class ChapterFilter { DOWNLOADED, UNREAD, BOOKMARKED }

    /** What the scanlator dialog's confirm does to the title's ranking (phase 4 unit SC). */
    sealed interface RankingChange {
        /** Nothing was moved and no ranking exists, or the ranking is unchanged: nothing is written. */
        data object Keep : RankingChange

        /** "Reset order": the title is unranked again. */
        data object Clear : RankingChange

        /** The ticked groups in the dialog's order, top first. */
        data class Write(val order: List<String>) : RankingChange
    }

    fun showChapterSettings() = showDialog(Dialog.ChapterSettings)

    fun showScanlatorFilter() = showDialog(Dialog.ScanlatorFilter)

    fun showReadingMode() = showDialog(Dialog.ReadingMode)

    /** The overflow's Edit details (phase 5 unit ED). */
    fun showEditDetails() = showDialog(Dialog.EditDetails)

    /**
     * The edit dialog's Save. [SetMangaDetailsOverride] stores an edit only where a text differs
     * from the source's, so the source's own text clears that edit, and it refuses a blank title,
     * which the dialog has refused already. The screen shows the result through the entry's own
     * flow; only a write that did not happen is said, on this screen's snackbar.
     */
    fun saveDetails(title: String, description: String) {
        val manga = (state.value as? State.Success)?.manga ?: return
        if (title.isBlank()) return
        dismissDialog()
        viewModelScope.launch {
            val saved = withContext(dispatchers.io) { setMangaDetailsOverride.await(manga.id, title, description) }
            if (!saved) eventChannel.send(Event.Snackbar(R.string.manga_edit_failed))
        }
    }

    /** The overflow's Notes, and a tap on the notes under the description. */
    fun showNotes() = showDialog(Dialog.Notes)

    /**
     * The notes dialog's Save, trimmed, so a note of only blank lines clears the notes. Text equal to
     * what is stored writes nothing, which keeps an unchanged Save from bumping the entry's sync clock.
     * The write goes through the entry's update, whose triggers stamp `notes_modified_at` and log the
     * entry for sync while it is in the library, as every other entry edit does.
     */
    fun saveNotes(notes: String) {
        val manga = (state.value as? State.Success)?.manga ?: return
        dismissDialog()
        val edited = notes.trim()
        if (edited == manga.notes) return
        viewModelScope.launch {
            val saved = withContext(dispatchers.io) { updateMangaNotes(manga.id, edited) }
            if (!saved) eventChannel.send(Event.Snackbar(R.string.manga_notes_failed))
        }
    }

    /** A tap on the header's cover: upstream's cover viewer, knowing whether a custom cover exists. */
    fun showCover() {
        val current = state.value as? State.Success ?: return
        viewModelScope.launch {
            val customCover = coverActions.customCoverExists(current.manga.id)
            showDialog(Dialog.Cover(editable = current.isLocal || current.manga.favorite, customCover = customCover))
        }
    }

    /** The picked image as the entry's cover; the viewer closes and the snackbar says how it went. */
    fun setCustomCover(uri: Uri) = coverAction(R.string.cover_updated, R.string.manga_cover_update_failed) {
        coverActions.setCustomCover(it, uri)
    }

    fun deleteCustomCover() = coverAction(R.string.cover_updated, R.string.manga_cover_update_failed) {
        coverActions.deleteCustomCover(it)
    }

    fun saveCover() = coverAction(R.string.manga_cover_saved, R.string.manga_cover_save_failed) {
        coverActions.save(it)
    }

    /** The share sheet is the success; only a failure is said. */
    fun shareCover() = coverAction(null, R.string.manga_cover_share_failed) { coverActions.share(it) }

    /**
     * Every cover action closes the viewer first, so its result lands on this screen's snackbar
     * rather than under the viewer's window, and the header shows a changed cover at once.
     */
    private fun coverAction(done: Int?, failed: Int, action: suspend (Manga) -> Result<*>) {
        val manga = (state.value as? State.Success)?.manga ?: return
        dismissDialog()
        viewModelScope.launch {
            val result = action(manga)
            result.exceptionOrNull()?.let { e ->
                logcat(LogPriority.WARN, e) { "Cover action failed for entry ${manga.id}" }
            }
            val message = if (result.isSuccess) done else failed
            if (message != null) eventChannel.send(Event.Snackbar(message))
        }
    }

    /** The edit dialog's Reset: both edits cleared, so the source's title and description show again. */
    fun resetDetails() {
        val manga = (state.value as? State.Success)?.manga ?: return
        dismissDialog()
        viewModelScope.launch {
            val reset = withContext(dispatchers.io) { setMangaDetailsOverride.awaitReset(manga.id) }
            if (!reset) eventChannel.send(Event.Snackbar(R.string.manga_edit_failed))
        }
    }

    /**
     * Writes one filter bit. [TriState] arrives already cycled by the row that was tapped, so the
     * only thing decided here is which of the three flag words each state means.
     */
    fun setChapterFilter(filter: ChapterFilter, value: TriState) {
        val manga = (state.value as? State.Success)?.manga ?: return
        viewModelScope.launch {
            when (filter) {
                ChapterFilter.DOWNLOADED -> setMangaChapterFlags.awaitSetDownloadedFilter(
                    manga,
                    when (value) {
                        TriState.DISABLED -> Manga.SHOW_ALL
                        TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
                        TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
                    },
                )
                ChapterFilter.UNREAD -> setMangaChapterFlags.awaitSetUnreadFilter(
                    manga,
                    when (value) {
                        TriState.DISABLED -> Manga.SHOW_ALL
                        TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
                        TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
                    },
                )
                ChapterFilter.BOOKMARKED -> setMangaChapterFlags.awaitSetBookmarkFilter(
                    manga,
                    when (value) {
                        TriState.DISABLED -> Manga.SHOW_ALL
                        TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
                        TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
                    },
                )
            }
        }
    }

    /**
     * The tapped field's own constant, never `manga.sorting`. The interactor flips the direction
     * when the two match and switches the field when they do not, so passing the current sorting
     * back in is how the old flip-only control could never change the field at all.
     */
    fun setChapterSort(sorting: Long) {
        val manga = (state.value as? State.Success)?.manga ?: return
        viewModelScope.launch { setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sorting) }
    }

    fun setChapterDisplay(displayMode: Long) {
        val manga = (state.value as? State.Success)?.manga ?: return
        viewModelScope.launch { setMangaChapterFlags.awaitSetDisplayMode(manga, displayMode) }
    }

    /** The volume headers toggle (phase 4 unit VG), a bit of this entry's own chapter flags. */
    fun setChapterGroupByVolume(enabled: Boolean) {
        val manga = (state.value as? State.Success)?.manga ?: return
        viewModelScope.launch { setMangaChapterFlags.awaitSetGroupByVolume(manga, enabled) }
    }

    /**
     * Upstream's `setCurrentSettingsAsDefault` (phase 4 unit CS): this entry's chapter settings become
     * the six defaults, and only with "Also apply to all entries in my library" ticked
     * ([applyToLibrary]) does every entry in the library take them. Otherwise an entry takes them
     * when it is opened while not in the library ([project]), or from its own Reset.
     *
     * The sheet stays open, because the owner is likely to keep adjusting it.
     */
    fun setAsDefaultChapterSettings(applyToLibrary: Boolean) {
        val manga = (state.value as? State.Success)?.manga ?: return
        viewModelScope.launch {
            withContext(dispatchers.io) {
                libraryPreferences.setChapterSettingsDefault(manga)
                if (applyToLibrary) setMangaDefaultChapterFlags.awaitAll()
            }
            eventChannel.send(Event.Snackbar(R.string.chapter_settings_updated))
        }
    }

    /** Upstream's `resetToDefaultSettings`: the stored defaults onto this entry, with no question and no snackbar. */
    fun resetToDefaultChapterSettings() {
        val manga = (state.value as? State.Success)?.manga ?: return
        viewModelScope.launch { withContext(dispatchers.io) { setMangaDefaultChapterFlags.await(manga) } }
    }

    /**
     * The scanlator dialog's one confirm: the unticked groups become the exclusions and, as
     * [ranking] says, the ticked groups in order become the ranking, or the ranking is cleared.
     *
     * The dialog closes before the write, for the reason [markSelectedRead] gives: the write
     * re-emits the chapter list, and a modal still sitting over a list that is changing under it
     * reads as a modal that did nothing.
     */
    fun confirmScanlators(excluded: Set<String>, ranking: RankingChange) {
        if (state.value !is State.Success) return
        dismissDialog()
        viewModelScope.launch {
            setExcludedScanlators.await(mangaId, excluded)
            when (ranking) {
                RankingChange.Keep -> Unit
                RankingChange.Clear -> setScanlatorPriority.clear(mangaId)
                is RankingChange.Write -> setScanlatorPriority.await(mangaId, ranking.order)
            }
        }
    }

    fun setReadingMode(mode: ReadingMode) {
        if (state.value !is State.Success) return
        dismissDialog()
        viewModelScope.launch { setMangaViewerFlags.awaitSetReadingMode(mangaId, mode) }
    }

    fun setOrientation(orientation: ReaderOrientation) {
        if (state.value !is State.Success) return
        dismissDialog()
        viewModelScope.launch { setMangaViewerFlags.awaitSetOrientation(mangaId, orientation) }
    }

    fun toggleDescription() {
        mutableState.update { if (it is State.Success) it.copy(descriptionExpanded = !it.descriptionExpanded) else it }
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, fromSource: Boolean): MangaDetailViewModel
    }
}

/** Upstream's `WhileSubscribed` timeout for the entry screen. */
private const val STOP_TIMEOUT_MS = 5_000L
