package com.shinsaikou.ui.updates

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateSkipCounts
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.preferences.UpdatesPreferences
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.LibraryUpdateErrorStore
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.libraryupdate.LibraryUpdateWorker
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.chapter.ChapterDownload
import com.shinsaikou.ui.chapter.ChapterDownloadAction
import com.shinsaikou.ui.chapter.chapterDownloadOf
import com.shinsaikou.ui.chapter.startDownloadNow
import com.shinsaikou.ui.longPressSelection
import com.shinsaikou.ui.util.UpdateErrorGroup
import com.shinsaikou.ui.util.groupUpdateErrors
import com.shinsaikou.ui.util.stoppedEntries
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The updates tab. The rows are upstream's: the stored filters of [UpdatesPreferences] go into the
 * query, which caps what it answers at the 500 newest rows after filtering, and the Downloaded
 * filter is applied afterwards from the downloaded chapter ids, as upstream applies it after its query
 * from download state (phase 4 unit UF). So that one filter narrows the 500, and the others do not.
 * The query reads only chapters uploaded after [since], three months before the view model started,
 * as upstream's does.
 *
 * Selection is a set of chapter ids rather than a flag on the row: `UpdatesWithRelations` is the
 * database's own projection and it re-emits on every write, so a selected copy of a row would be
 * replaced by an unselected one the moment a bulk action lands.
 *
 * The list is drawn from [State.uiModels]: upstream's day headers over the rows, and one entry's
 * consecutive chapters within a day folded into one row (phase 4 unit UG). A fold is presentation
 * only; the rows, the selection and the bulk actions stay per chapter, so a collapsed fold's
 * chapters are selected, counted and marked like any other.
 *
 * Each row carries upstream's download control (plan 2.6, unit ID3), drawn from [State.downloaded] and
 * [State.queued], which follow only the chapters the list shows.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(UpdatesViewModel::class)
@Inject
class UpdatesViewModel(
    private val getUpdates: GetUpdates,
    private val getChapter: GetChapter,
    private val setReadStatus: SetReadStatus,
    private val toggleBookmark: ToggleBookmark,
    private val libraryUpdateScheduler: LibraryUpdateScheduler,
    private val errorStore: LibraryUpdateErrorStore,
    private val sourceManager: SourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val updatesPreferences: UpdatesPreferences,
    private val uiPreferences: UiPreferences,
    private val downloadedChapters: DownloadedChapters,
    private val downloadQueue: DownloadQueue,
    private val downloadManifest: DownloadManifest,
    private val getManga: GetManga,
    private val getCategories: GetCategories,
    private val dispatchers: Dispatchers,
    clock: Clock,
) : StateViewModel<UpdatesViewModel.State>(State()) {

    /**
     * Upstream's date limit for recent chapters: now less three months in the device's zone, read once
     * when the view model starts and passed to every query it runs, whatever filter changes later.
     * The query compares it with a chapter's upload date, as upstream's does, not its fetch date.
     */
    private val since: Instant = clock.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())

    data class State(
        val loading: Boolean = true,
        val items: List<UpdatesWithRelations> = emptyList(),
        val updating: Boolean = false,
        val selection: Set<Long> = emptySet(),
        val updateErrorCount: Int = 0,
        val dialog: Dialog? = null,
        val filters: Filters = Filters(),
        /** Every live category, the default one first, for the filter dialog's category tab. */
        val categories: List<Category> = emptyList(),
        /**
         * The owner's last open or close of a fold, for each chapter that fold held, as
         * [updatesUiModels] reads it; a fold none of whose chapters is here has its default.
         */
        val foldExpanded: Map<Long, Boolean> = emptyMap(),
        /** Relative timestamps, `relative_time_v2`, for the day headers. */
        val relativeTime: Boolean = true,
        /** The stored date format, `app_date_format`, for the day headers. */
        val dateFormat: String = "",
        /**
         * When a library update last started ([LibraryPreferences.lastUpdatedTimestamp]), for
         * upstream's line above the rows; 0 when none has run.
         */
        val lastUpdated: Long = 0L,
        /** How many entries the last automatic run skipped, by reason, for the line under [lastUpdated]. */
        val lastAutoSkipped: Map<String, Int> = emptyMap(),
        /** Which of the shown chapters are on disk, a row whose folder is gone left out. */
        val downloaded: Set<Long> = emptySet(),
        /** The queue's rows for the shown chapters, by chapter id. */
        val queued: Map<Long, DownloadQueueEntry> = emptyMap(),
    ) {
        val selectionMode: Boolean
            get() = selection.isNotEmpty()

        /** Whether the toolbar's filter icon is lit. */
        val hasActiveFilters: Boolean
            get() = filters.hasActiveFilters

        /**
         * Safe as a size comparison because the projection prunes ids it no longer shows, so the
         * selection is always a subset of [items].
         */
        val allSelected: Boolean
            get() = items.isNotEmpty() && selection.size == items.size

        /** What a row's download control draws; a Local chapter draws none ([showsDownloadControl]). */
        fun downloadOf(update: UpdatesWithRelations): ChapterDownload =
            if (update.sourceId == SourceIds.LOCAL) {
                ChapterDownload.None
            } else {
                chapterDownloadOf(update.chapterId, downloaded, queued)
            }

        /**
         * Whether a row draws its download control: not for a Local chapter, which the queue
         * refuses, and not while rows are selected, as on the entry screen.
         */
        fun showsDownloadControl(update: UpdatesWithRelations): Boolean =
            !selectionMode && update.sourceId != SourceIds.LOCAL

        /** Upstream's `getUiModel`, with the folds: the day of a row is read in [zone]. */
        fun uiModels(zone: TimeZone = TimeZone.currentSystemDefault()): List<UpdatesUiModel> =
            updatesUiModels(items, foldExpanded, zone)
    }

    /** The seven stored filters as one value, upstream's `ItemPreferences`. */
    data class Filters(
        val downloaded: TriState = TriState.DISABLED,
        val unread: TriState = TriState.DISABLED,
        val started: TriState = TriState.DISABLED,
        val bookmarked: TriState = TriState.DISABLED,
        val excludedScanlators: Boolean = false,
        val includedCategories: List<Long> = emptyList(),
        val excludedCategories: List<Long> = emptyList(),
    ) {
        /**
         * Upstream lights the icon for the four tri-states only: the scanlator switch and the category
         * lists leave it unlit, as they do in upstream's `hasActiveFilters`.
         */
        val hasActiveFilters: Boolean
            get() = listOf(downloaded, unread, started, bookmarked).any { it != TriState.DISABLED }

        /** A category's row in the dialog: included, excluded, or neither, read as upstream's dialog reads it. */
        fun categoryState(categoryId: Long): TriState = when (categoryId) {
            in includedCategories -> TriState.ENABLED_IS
            in excludedCategories -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }
    }

    /** The dialog's four tri-state rows, so a row names one instead of a preference. */
    enum class UpdatesFilter {
        DOWNLOADED,
        UNREAD,
        STARTED,
        BOOKMARKED,
    }

    sealed interface Dialog {
        /**
         * Groups are built once, when the dialog opens, the way the library's picker builds its rows.
         * [stoppedEntries] is how many of the errors are a stopped site's, counted from the same read.
         */
        data class ErrorList(val groups: List<UpdateErrorGroup>, val stoppedEntries: Int) : Dialog

        /** Upstream's filter dialog. It reads [State.filters], so it holds nothing a rotation could lose. */
        data object Filter : Dialog
    }

    sealed interface Event {
        /** [formatArgs] fill the message's placeholders, when it has any. */
        data class Snackbar(@StringRes val messageRes: Int, val formatArgs: List<Any> = emptyList()) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        // Reaching the tab is what "seen" means for the badge, so it clears once here rather than
        // on every emission of the row query.
        libraryPreferences.newUpdatesCount.set(0)

        viewModelScope.launch {
            combine(rows(), downloadedFilter()) { rows, downloaded ->
                rows.filter { row -> applyFilter(downloaded.state) { row.chapterId in downloaded.ids } }
            }
                .flowOn(dispatchers.io)
                .collect { visible ->
                    val visibleIds = visible.mapTo(mutableSetOf()) { it.chapterId }
                    mutableState.update {
                        it.copy(
                            loading = false,
                            items = visible,
                            // Marking read while the unread filter is on takes the row off screen.
                            // A selection that kept it would count rows nobody can see and the bulk
                            // actions would keep writing to them.
                            selection = it.selection intersect visibleIds,
                        )
                    }
                }
        }
        viewModelScope.launch {
            filters().collect { filters -> mutableState.update { it.copy(filters = filters) } }
        }
        viewModelScope.launch {
            downloadStates().collect { (downloaded, queued) ->
                mutableState.update { it.copy(downloaded = downloaded, queued = queued) }
            }
        }
        viewModelScope.launch {
            getCategories.subscribe()
                .flowOn(dispatchers.io)
                .collect { categories -> mutableState.update { it.copy(categories = categories) } }
        }
        viewModelScope.launch {
            combine(uiPreferences.relativeTime.withCurrent(), uiPreferences.dateFormat.withCurrent()) { relative, format ->
                relative to format
            }.collect { (relative, format) ->
                mutableState.update { it.copy(relativeTime = relative, dateFormat = format) }
            }
        }
        viewModelScope.launch {
            libraryPreferences.lastUpdatedTimestamp.withCurrent().collect { at ->
                mutableState.update { it.copy(lastUpdated = at) }
            }
        }
        viewModelScope.launch {
            libraryPreferences.lastAutoUpdateSkipped.withCurrent().collect { stored ->
                mutableState.update { it.copy(lastAutoSkipped = LibraryUpdateSkipCounts.decode(stored)) }
            }
        }
        viewModelScope.launch {
            libraryUpdateScheduler.isRunning.collect { running ->
                mutableState.update { it.copy(updating = running) }
            }
        }
        viewModelScope.launch {
            // Idempotent, and the only thing that fills `errors` in a process that did not run the
            // update itself. Without it the badge is always zero on a fresh launch.
            errorStore.load()
            errorStore.errors.collect { errors ->
                mutableState.update { it.copy(updateErrorCount = errors.size) }
            }
        }
    }

    /**
     * The last run's failures, grouped by source once, when the dialog opens.
     *
     * The store is loaded here as well as in `init`, and before the read. The error notification
     * asks for this dialog on the first frame of a cold start, when the process that ran the update
     * is long dead and nothing orders the read after the `init` load; the read could win and open
     * the dialog empty. `load()` is idempotent, so the second call costs one small file read.
     */
    fun showErrors() {
        viewModelScope.launch {
            errorStore.load()
            val errors = errorStore.errors.value
            val groups = withContext(dispatchers.io) {
                groupUpdateErrors(errors, sourceManager::getOrStub)
            }
            mutableState.update { it.copy(dialog = Dialog.ErrorList(groups, stoppedEntries(errors))) }
        }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    /**
     * The error dialog's Retry: a manual run over the entries the stored errors name, as many as a
     * run can take. A row the run checks leaves the list, or goes to its end when the entry fails
     * again, so the next Retry takes the rest.
     */
    fun retryErrors() {
        val ids = errorStore.errors.value.map { it.mangaId }.distinct().take(LibraryUpdateWorker.MAX_MANGA_IDS)
        dismissDialog()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val event = if (libraryUpdateScheduler.startNow(mangaIds = ids)) {
                Event.Snackbar(R.string.library_update_entries_started)
            } else {
                Event.Snackbar(notStartedRes())
            }
            eventChannel.send(event)
        }
    }

    /**
     * The manual library update behind the pull gesture. `startNow` answers false when a run is
     * already going, which is the case the KEEP policy would otherwise swallow silently, and in a
     * start without extensions, where runs wait.
     */
    fun refresh() {
        viewModelScope.launch {
            val started = libraryUpdateScheduler.startNow()
            eventChannel.send(Event.Snackbar(if (started) R.string.library_update_started else notStartedRes()))
        }
    }

    /** Why `startNow` started nothing. */
    private fun notStartedRes(): Int =
        if (libraryUpdateScheduler.isPaused) R.string.library_update_paused else R.string.library_update_already_running

    /** The toolbar's filter icon. */
    fun showFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.Filter) }
    }

    fun setFilter(filter: UpdatesFilter, value: TriState) {
        preferenceFor(filter).set(value)
    }

    /** Upstream's Filter excluded scanlators switch. */
    fun toggleExcludedScanlators() {
        updatesPreferences.filterExcludedScanlators.toggle()
    }

    /**
     * A category row of the dialog, cycling neither, included, excluded, as upstream's
     * `UpdatesSettingsViewModel.cycleCategory` does: an included id moves to the excluded list in
     * one step. An id is added to a list only when it is not already there, so a restore that left
     * it in both lists cannot make a row that needs an extra tap to clear.
     */
    fun cycleCategory(categoryId: Long) {
        val included = updatesPreferences.filterIncludedCategories
        val excluded = updatesPreferences.filterExcludedCategories
        when (categoryId) {
            in included.get() -> {
                included.getAndSet { it - categoryId }
                excluded.getAndSet { if (categoryId in it) it else it + categoryId }
            }
            in excluded.get() -> excluded.getAndSet { it - categoryId }
            else -> included.getAndSet { it + categoryId }
        }
    }

    /** A tap on a chapter's row in selection mode picks it or leaves it. */
    fun toggleSelection(chapterId: Long) {
        mutableState.update {
            val selection = if (chapterId in it.selection) it.selection - chapterId else it.selection + chapterId
            it.copy(selection = selection)
        }
    }

    /**
     * A long press on [chapterId]'s row, upstream's `toggleSelection(fromLongPress = true)`: see
     * [longPressSelection]. The order is [State.items], newest fetch first, which the rows follow.
     */
    fun longPressChapter(chapterId: Long) {
        mutableState.update { it.copy(selection = longPressSelection(it.selectionOrder(), it.selection, chapterId)) }
    }

    /**
     * A long press on a fold's row: [longPressSelection] with the fold's chapters as the pressed
     * block, the hidden ones of a collapsed fold too, so the bulk actions reach them. A fold's
     * chapters are one run of [State.items] whether it is open or closed ([updatesUiModels]), so
     * the block sits where the rows would, and a range reaching past a closed fold takes its
     * chapters with it. When every one is already selected, the press leaves them all.
     */
    fun longPressFold(fold: UpdatesUiModel.Fold) {
        mutableState.update {
            it.copy(selection = longPressSelection(it.selectionOrder(), it.selection, fold.chapterIds))
        }
    }

    private fun State.selectionOrder(): List<Long> = items.map { it.chapterId }

    /**
     * A tap on a fold's row opens a collapsed fold and closes an open one. The choice is stored for
     * each of its chapters, so it stays with them across re-emissions of the query.
     */
    fun toggleFold(fold: UpdatesUiModel.Fold) {
        val open = !fold.expanded
        mutableState.update { it.copy(foldExpanded = it.foldExpanded + fold.chapterIds.associateWith { open }) }
    }

    fun selectAll() {
        mutableState.update { state -> state.copy(selection = state.items.mapTo(mutableSetOf()) { it.chapterId }) }
    }

    fun invertSelection() {
        mutableState.update { state ->
            val inverted = state.items.mapNotNullTo(mutableSetOf()) { row ->
                row.chapterId.takeUnless { it in state.selection }
            }
            state.copy(selection = inverted)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    /**
     * `SetReadStatus` takes chapters and a row carries only ids, so the chapter is loaded first:
     * the `mangaId` overload would take every other chapter of the entry with it.
     */
    fun markRead(update: UpdatesWithRelations, read: Boolean) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                val chapter = getChapter.await(update.chapterId) ?: return@withContext null
                setReadStatus.await(read, chapter)
            }
            if (result == null || result is SetReadStatus.Result.InternalError) {
                eventChannel.send(Event.Snackbar(R.string.error_generic))
            }
        }
    }

    /**
     * The selection's mark, one `SetReadStatus` call for the whole batch so the change log gains
     * one entry rather than one per row.
     *
     * Every selected id is loaded through [GetChapter] first, for the same reason the single-row
     * mark does. An id the database can no longer resolve is dropped instead of failing the batch,
     * and a batch where every id is gone marks nothing at all: calling `SetReadStatus` with an
     * empty array answers `NoChapters`, which a caller reads as a quiet success.
     */
    fun markRead(read: Boolean) {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            val failed = withContext(dispatchers.io) {
                val chapters = ids.mapNotNull { getChapter.await(it) }
                if (chapters.isEmpty()) return@withContext true
                val result = setReadStatus.await(read, *chapters.toTypedArray())
                result is SetReadStatus.Result.InternalError || chapters.size != ids.size
            }
            if (failed) {
                eventChannel.send(Event.Snackbar(R.string.error_generic))
            }
        }
    }

    /**
     * A tap on a row's download control (plan 2.6 part D), upstream's `UpdatesScreenModel.downloadChapters`
     * for one row, down the paths the entry screen's control uses: a download queues the chapter and
     * starts the queue, start now moves it to the front ([DownloadQueue.startDownloadNow]), cancel
     * removes its queue row, delete its files and manifest row with no dialog. Nothing happens where
     * the row draws no control. A row whose entry or chapter the database no longer has says so.
     */
    fun runChapterDownloadAction(update: UpdatesWithRelations, action: ChapterDownloadAction) {
        if (!state.value.showsDownloadControl(update)) return
        viewModelScope.launch {
            when (action) {
                ChapterDownloadAction.START, ChapterDownloadAction.START_NOW -> {
                    val (manga, chapter) = withContext(dispatchers.io) {
                        getManga.await(update.mangaId) to getChapter.await(update.chapterId)
                    }
                    if (manga == null || chapter == null) {
                        eventChannel.send(Event.Snackbar(R.string.error_generic))
                    } else if (action == ChapterDownloadAction.START) {
                        downloadQueue.enqueue(manga, listOf(chapter), autoStart = true)
                    } else {
                        downloadQueue.startDownloadNow(manga, chapter)
                    }
                }
                ChapterDownloadAction.CANCEL -> downloadQueue.remove(listOf(update.chapterId))
                ChapterDownloadAction.DELETE -> downloadManifest.delete(listOf(update.chapterId))
            }
        }
    }

    /**
     * Bookmarking needs no chapter load: [ToggleBookmark] writes a `ChapterUpdate` keyed by id. It
     * logs and swallows its own failures, so there is no result here worth reporting to the owner.
     */
    fun toggleBookmark(bookmark: Boolean) {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            withContext(dispatchers.io) {
                ids.forEach { toggleBookmark.await(it, bookmark) }
            }
        }
    }

    private fun preferenceFor(filter: UpdatesFilter): Preference<TriState> = when (filter) {
        UpdatesFilter.DOWNLOADED -> updatesPreferences.filterDownloaded
        UpdatesFilter.UNREAD -> updatesPreferences.filterUnread
        UpdatesFilter.STARTED -> updatesPreferences.filterStarted
        UpdatesFilter.BOOKMARKED -> updatesPreferences.filterBookmarked
    }

    /** Upstream's `getUpdatesItemPreferenceFlow`, every key read again on each change of any of them. */
    private fun filters(): Flow<Filters> = combine(
        updatesPreferences.filterDownloaded.withCurrent(),
        updatesPreferences.filterUnread.withCurrent(),
        updatesPreferences.filterStarted.withCurrent(),
        updatesPreferences.filterBookmarked.withCurrent(),
        updatesPreferences.filterExcludedScanlators.withCurrent(),
        updatesPreferences.filterIncludedCategories.withCurrent(),
        updatesPreferences.filterExcludedCategories.withCurrent(),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        Filters(
            downloaded = values[0] as TriState,
            unread = values[1] as TriState,
            started = values[2] as TriState,
            bookmarked = values[3] as TriState,
            excludedScanlators = values[4] as Boolean,
            includedCategories = values[5] as List<Long>,
            excludedCategories = values[6] as List<Long>,
        )
    }.distinctUntilChanged()

    /**
     * The query with upstream's six filters, run again only when one of them changes: the Downloaded
     * filter is not among them. The chapter settings unit's carry-over 5 (a hardcoded
     * `hideExcludedScanlators = false`) ends here, the stored switch going in as upstream's does.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun rows(): Flow<List<UpdatesWithRelations>> =
        filters()
            .map { it.copy(downloaded = TriState.DISABLED) }
            .distinctUntilChanged()
            .flatMapLatest { filters ->
                getUpdates.subscribe(
                    instant = since,
                    unread = filters.unread.toBooleanOrNull(),
                    started = filters.started.toBooleanOrNull(),
                    bookmarked = filters.bookmarked.toBooleanOrNull(),
                    hideExcludedScanlators = filters.excludedScanlators,
                    includedCategories = filters.includedCategories,
                    excludedCategories = filters.excludedCategories,
                )
            }

    /**
     * The Downloaded filter with the ids it tests against. The ids are the manifest's, a row whose
     * folder is gone left out as the chapter list leaves it, and they are read only while the filter
     * is on: the answer checks every downloaded chapter's folder on each manifest change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun downloadedFilter(): Flow<DownloadedFilter> =
        updatesPreferences.filterDownloaded.withCurrent()
            .distinctUntilChanged()
            .flatMapLatest { state ->
                if (state == TriState.DISABLED) {
                    flowOf(DownloadedFilter(state, emptySet()))
                } else {
                    downloadedChapters.subscribeAll().map { ids -> DownloadedFilter(state, ids) }
                }
            }

    private data class DownloadedFilter(val state: TriState, val ids: Set<Long>)

    /**
     * The download state of the chapters the list shows, for their controls: the manifest's answer
     * for those ids only ([DownloadedChapters.subscribeByChapterIds], which checks only their folders)
     * and the queue's rows for them. Both follow the shown ids, so a new row or a filter change
     * asks again; a Local row is left out, since it draws no control.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun downloadStates(): Flow<Pair<Set<Long>, Map<Long, DownloadQueueEntry>>> =
        state
            .map { current ->
                current.items.filter { it.sourceId != SourceIds.LOCAL }.mapTo(mutableSetOf()) { it.chapterId }
            }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                combine(
                    downloadedChapters.subscribeByChapterIds(ids),
                    downloadQueue.subscribe()
                        .map { rows -> rows.filter { it.chapterId in ids }.associateBy { it.chapterId } }
                        .distinctUntilChanged(),
                    ::Pair,
                )
            }
            .flowOn(dispatchers.io)
}

private fun TriState.toBooleanOrNull(): Boolean? = when (this) {
    TriState.DISABLED -> null
    TriState.ENABLED_IS -> true
    TriState.ENABLED_NOT -> false
}
