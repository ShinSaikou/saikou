package com.shinsaikou.ui.library

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.category.CategoryTree
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.library.interactor.FilterLibrary
import com.shinsaikou.domain.library.interactor.GetLibraryItems
import com.shinsaikou.domain.library.interactor.SetManualLibraryOrder
import com.shinsaikou.domain.library.interactor.SortLibrary
import com.shinsaikou.domain.library.model.LibraryFilters
import com.shinsaikou.domain.library.model.LibraryItem
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.MergeEntries
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.libraryupdate.LibraryUpdateWorker
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.components.CategoryCheck
import com.shinsaikou.ui.components.DownloadAction
import com.shinsaikou.ui.components.categoryChecks
import com.shinsaikou.ui.longPressSelection
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.domain.library.model.search.QueryNode
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.source.model.Source as DomainSource

/**
 * The library tab. Rows come from the database; the category grouping, the filter toggles, the sort
 * and the display choices come from preferences and are re-read whenever any of them changes, which
 * is why a settings change is visible on the next frame rather than the next process.
 *
 * Selection lives here rather than in the composable on purpose. A selection is the one piece of
 * library state a rotation must not drop, and the bulk actions it feeds are suspending database
 * writes that outlive any single composition.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(LibraryViewModel::class)
@Inject
class LibraryViewModel(
    private val getLibraryItems: GetLibraryItems,
    private val filterLibrary: FilterLibrary,
    private val sortLibrary: SortLibrary,
    private val sourceManager: SourceManager,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val setReadStatus: SetReadStatus,
    private val updateManga: UpdateManga,
    private val getNextChapters: GetNextChapters,
    private val getScanlatorPriority: GetScanlatorPriority,
    private val setDisplayMode: SetDisplayMode,
    private val setSortModeForCategory: SetSortModeForCategory,
    /**
     * Public because the settings sheet's checkbox rows are the vendored `CheckboxItem(label, pref)`,
     * which subscribes to the `Preference` itself. A copy of those seven booleans in [State] would
     * be a second source of truth that every write has to remember to refresh; the settings screen
     * hands its sections the same object for the same reason.
     */
    val libraryPreferences: LibraryPreferences,
    private val basePreferences: BasePreferences,
    private val libraryUpdateScheduler: LibraryUpdateScheduler,
    private val dispatchers: Dispatchers,
    private val downloadQueue: DownloadQueue,
    private val downloadedChapters: DownloadedChapters,
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId,
    private val trackerManager: TrackerManager,
    private val trackRepository: TrackRepository,
    private val setManualLibraryOrder: SetManualLibraryOrder,
    private val mergeEntries: MergeEntries,
) : StateViewModel<LibraryViewModel.State>(State()) {

    /**
     * One row with every badge already resolved, so a composable decides nothing: a null badge is
     * one that is switched off or has nothing to say, and is not drawn.
     */
    @Immutable
    data class LibraryItemUi(
        val item: LibraryItem,
        val unreadBadge: Long?,
        val downloadBadge: Long?,
        val localBadge: Boolean,
        val languageBadge: String?,
        val showContinueReading: Boolean,
    ) {
        val id: Long get() = item.id
    }

    @Immutable
    data class State(
        val loading: Boolean = true,
        /**
         * Includes the default category (id 0) only when it holds entries or is the only one. In
         * [CategoryTree]'s order, each child after its parent (phase 5 unit CT); still one flat list,
         * one page per category, so the stored tab index and every per-category read stay as they were.
         */
        val categories: List<Category> = emptyList(),
        /** Each shown child category's parent, by the child's id; empty while no child is shown. */
        val categoryParents: Map<Long, Category> = emptyMap(),
        val itemsByCategory: Map<Long, List<LibraryItemUi>> = emptyMap(),
        val activeCategoryIndex: Int = 0,
        /** The unfiltered feed size, which is what tells an empty library from an empty filter. */
        val total: Int = 0,
        /** Entries left after filter and search, counted once each however many categories hold them. */
        val shown: Int = 0,
        val query: String = "",
        val updating: Boolean = false,
        val filters: LibraryFilters = LibraryFilters(),
        /** True while `BasePreferences.downloadedOnly` is forcing the downloaded filter on. */
        val downloadedOnly: Boolean = false,
        val globalSort: LibrarySort = LibrarySort.default,
        /** `categorizedDisplaySettings`: a sort restored from another reader can be per category. */
        val categorizedSort: Boolean = false,
        /**
         * The manual order is what the pages show: "Manual ordering" is on and no category has a sort
         * of its own, contract 04's rule (phase 4 unit MS). With per-category sorts on, every tab
         * has its own sort and the manual order is left out of all of them.
         */
        val manualSort: Boolean = false,
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.default,
        /** 0 means "fit as many as the width allows"; anything else is a fixed column count. */
        val columns: Int = 0,
        val showCategoryTabs: Boolean = true,
        val showItemCount: Boolean = false,
        /** Manga ids, not row indices: the rows under a selection are re-sorted on every tick. */
        val selection: Set<Long> = emptySet(),
        /**
         * Every library entry's category ids, taken from the unfiltered feed. The category picker
         * reads membership from here rather than from the rows because a selection made before a
         * search survives the search, and the filtered list no longer holds those rows. A merged
         * title's other members are keys too, with the title's categories, so a pane left open on
         * one when the main entry changes still reads as in the library.
         */
        val categoriesByManga: Map<Long, List<Long>> = emptyMap(),
        /**
         * The library's Local entries by id, from the unfiltered feed for the reason
         * [categoriesByManga] is. A selection holding one is offered no Download, as in upstream
         * (phase 3 unit AD).
         */
        val localManga: Set<Long> = emptySet(),
        /**
         * The signed-in trackers, by id: the filter tab's tracker rows and the tracker score sort
         * are offered only while there is one (phase 3 unit T3).
         */
        val signedInTrackers: List<SignedInTracker> = emptyList(),
        /** The experimental per-entry interval, the only way an entry can match the filter for it. */
        val customUpdateInterval: Boolean = false,
        /** The experimental merged entries, which is when a selection offers Merge. */
        val mergedEntries: Boolean = false,
        val dialog: Dialog? = null,
    ) {
        /**
         * The stored index can outlive the category it named: a restore, a delete or a filter that
         * empties the default category all change how many tabs there are, so every read coerces.
         */
        val activeCategory: Category?
            get() = categories.getOrNull(activeCategoryIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0)))

        /**
         * The sort the sheet shows and the active page is ordered by. Derived rather than stored so
         * that moving to another tab under a per-category sort needs no new projection.
         */
        val sort: LibrarySort get() = if (categorizedSort) activeCategory.sort else globalSort

        val selectionMode: Boolean get() = selection.isNotEmpty()

        val selectionHasLocal: Boolean get() = selection.any { it in localManga }

        /** Merging takes two entries or more, whatever the selection holds beyond that. */
        val canMerge: Boolean get() = mergedEntries && selection.size >= 2

        /** True when every category came back empty, which a filter or a search can do to a full library. */
        val isEmpty: Boolean get() = itemsByCategory.values.all { it.isEmpty() }

        /**
         * The downloaded filter counts even when `downloadedOnly` is what turned it on; a signed-in
         * tracker's filter counts only when it is not DISABLED.
         */
        val hasActiveFilters: Boolean get() = filters.isActive

        /**
         * Whether the pages draw a drag handle (phase 4 unit MS). A drag writes the order of a whole
         * tab, so the tab on screen has to be all of it and in the manual order: no search, no
         * filter, the manual order showing. A selection hides the handles too, since a long press is
         * how the owner selects and the two gestures would otherwise share every cell.
         */
        val canReorder: Boolean
            get() = manualSort && query.isEmpty() && !hasActiveFilters && !selectionMode

        fun items(categoryId: Long): List<LibraryItemUi> = itemsByCategory[categoryId].orEmpty()

        /** What the owner can actually see: the active category's rows, after filter and search. */
        val visibleItems: List<LibraryItemUi> get() = activeCategory?.let { items(it.id) }.orEmpty()
    }

    /** A signed-in tracker as the filter tab names it. */
    data class SignedInTracker(val id: Long, val name: String)

    /** A row of the merge dialog. */
    data class MergeChoice(val mangaId: Long, val title: String, val sourceName: String)

    sealed interface Dialog {
        /** Filter, sort and display, in one sheet. */
        data object SettingsSheet : Dialog

        /** Rows are built once, when the picker opens, so a tick underneath cannot re-tick a box. */
        data class ChangeCategory(val rows: List<CategoryCheck>) : Dialog

        /** [mergedTitles] of the [count] are merged, so every source of those goes too. */
        data class RemoveSelected(val count: Int, val mergedTitles: Int = 0) : Dialog

        /** The selected entries to merge in selection order, which is the chapter order. */
        data class MergeSelected(val entries: List<MergeChoice>) : Dialog
    }

    sealed interface Event {
        /** [formatArgs] fill the message's placeholders, when it has any. */
        data class Snackbar(val messageRes: Int, val formatArgs: List<Any> = emptyList()) : Event

        data class OpenReader(val mangaId: Long, val chapterId: Long) : Event
    }

    private val queryFlow = MutableStateFlow("")

    /**
     * Which column preference the projection reads and [setColumns] writes. The view model cannot
     * see the configuration, so the screen reports it; keeping it here rather than reading both
     * preferences into [State] is what lets one slider and one number mean the orientation the
     * owner is actually holding.
     */
    private val landscapeFlow = MutableStateFlow(false)

    /**
     * Every library entry by id, from the unfiltered feed, written on Main with the projection. The
     * download menu reads the selected entries from here, for the reason [State.categoriesByManga]
     * is unfiltered: a selection outlives the search that hid its rows.
     */
    private var libraryManga: Map<Long, Manga> = emptyMap()

    /** Each merged title's entries by the main entry's id, which is the id a row and a selection hold. */
    private var mergedMembers: Map<Long, List<Manga>> = emptyMap()

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    @OptIn(FlowPreview::class)
    private val debouncedQuery: Flow<String> = queryFlow
        // An empty query is the screen's resting state, so the first projection must not wait a
        // quarter second for it. Only real typing is debounced, and 468 titles are re-filtered once
        // the owner stops rather than once per keystroke.
        .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }

    init {
        // Synchronously, before the first composition reads the state: the pager takes its initial
        // page from this value exactly once, and a value that arrived one frame later would open
        // the library on the first category every time.
        mutableState.update { it.copy(activeCategoryIndex = libraryPreferences.lastUsedCategory.get()) }
        viewModelScope.launch {
            combine(
                getLibraryItems.subscribe(),
                getCategories.subscribe(),
                // The stubs are an input too, so a not-installed source's remembered name reaches
                // the search once its lookup has ended (see project).
                combine(sourceManager.getCatalogueSources(), sourceManager.getStubSources()) { sources, _ -> sources },
                debouncedQuery,
                combine(settingsChanges(), tracking()) { _, tracking -> tracking },
            ) { items, categories, sources, query, tracking -> project(items, categories, sources, query, tracking) }
                .flowOn(dispatchers.default)
                .collect { projection ->
                    libraryManga = projection.mangaById
                    mergedMembers = projection.mergedMembers
                    // update, never a whole-state assignment. The projection is computed on the
                    // default dispatcher while the selection and the isRunning collector write from
                    // Main, so overwriting every field here would drop a selection made while it was
                    // in flight, which is the whole reason the merge below names its fields one by one.
                    mutableState.update { current ->
                        current.copy(
                            loading = false,
                            // The held lists while equal: a chapter or entry change then hands the
                            // tab row the same instances, and it skips (phase 5 unit CT).
                            categories = projection.categories.takeUnless { it == current.categories } ?: current.categories,
                            categoryParents = projection.categoryParents.takeUnless { it == current.categoryParents }
                                ?: current.categoryParents,
                            itemsByCategory = projection.itemsByCategory,
                            total = projection.total,
                            shown = projection.shown,
                            query = projection.query,
                            categoriesByManga = projection.categoriesByManga,
                            localManga = projection.localManga,
                            filters = projection.filters,
                            downloadedOnly = projection.downloadedOnly,
                            globalSort = projection.globalSort,
                            categorizedSort = projection.categorizedSort,
                            manualSort = projection.manualSort,
                            displayMode = projection.displayMode,
                            columns = projection.columns,
                            showCategoryTabs = projection.showCategoryTabs,
                            showItemCount = projection.showItemCount,
                            signedInTrackers = projection.signedInTrackers,
                            customUpdateInterval = projection.customUpdateInterval,
                            mergedEntries = projection.mergedEntries,
                        )
                    }
                    logcat(LogPriority.DEBUG) { "$PROJECTION_LOG state set at ${SystemClock.elapsedRealtime()}" }
                }
        }
        viewModelScope.launch {
            libraryUpdateScheduler.isRunning.collect { running ->
                mutableState.update { it.copy(updating = running) }
            }
        }
    }

    fun search(query: String) {
        queryFlow.value = query
    }

    /** Called by the screen on every configuration it is composed in; see [landscapeFlow]. */
    fun setLandscape(landscape: Boolean) {
        landscapeFlow.value = landscape
    }

    /** The manual library update, over every category the update settings allow. */
    fun updateNow() {
        startUpdate(categoryId = null, startedRes = R.string.library_update_started)
    }

    /**
     * Upstream's Update category: a manual run over the entries of the category showing, and only
     * those. Before the first projection there is no category to name, so nothing starts.
     */
    fun updateCategory() {
        val category = state.value.activeCategory ?: return
        startUpdate(categoryId = category.id, startedRes = R.string.library_update_category_started)
    }

    /**
     * `startNow` answers false when a run is already going, which is the case the KEEP policy
     * would otherwise swallow silently, and in a start without extensions, where runs wait.
     */
    private fun startUpdate(categoryId: Long?, startedRes: Int) {
        viewModelScope.launch {
            val started = libraryUpdateScheduler.startNow(categoryId = categoryId)
            eventChannel.send(Event.Snackbar(if (started) startedRes else notStartedRes()))
        }
    }

    /** A manual run over the selected entries only, whatever their categories. */
    fun updateSelected() {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) return
        clearSelection()
        startUpdateOf(withMembers(ids))
    }

    private fun startUpdateOf(ids: List<Long>) {
        viewModelScope.launch {
            val event = if (ids.size > LibraryUpdateWorker.MAX_MANGA_IDS) {
                Event.Snackbar(R.string.library_update_too_many_format, listOf(LibraryUpdateWorker.MAX_MANGA_IDS))
            } else if (libraryUpdateScheduler.startNow(mangaIds = ids)) {
                Event.Snackbar(R.string.library_update_entries_started)
            } else {
                Event.Snackbar(notStartedRes())
            }
            eventChannel.send(event)
        }
    }

    /** Why `startNow` started nothing. */
    private fun notStartedRes(): Int =
        if (libraryUpdateScheduler.isPaused) R.string.library_update_paused else R.string.library_update_already_running

    fun stopUpdate() {
        viewModelScope.launch { libraryUpdateScheduler.stop() }
    }

    /**
     * Records the page the pager settled on. The index is stored rather than the category id
     * because that is what upstream's `last_used_category` holds, so a restored backup opens the
     * library where the owner's other device left it.
     */
    fun setActiveCategory(index: Int) {
        val current = state.value
        if (index == current.activeCategoryIndex) return
        mutableState.update { it.copy(activeCategoryIndex = index) }
        libraryPreferences.lastUsedCategory.set(index)
    }

    fun setDisplayMode(mode: LibraryDisplayMode) {
        setDisplayMode.await(mode)
    }

    /** 0 is the auto column count, so the slider's lower end is a real value rather than a floor. */
    fun setColumns(count: Int) {
        if (landscapeFlow.value) {
            libraryPreferences.landscapeColumns.set(count)
        } else {
            libraryPreferences.portraitColumns.set(count)
        }
    }

    fun setFilter(filter: LibraryFilter, value: TriState) {
        preferenceFor(filter).set(value)
    }

    /** A signed-in tracker's row in the filter tab, upstream's `filterTracking` (phase 3 unit T3). */
    fun setTrackerFilter(trackerId: Long, value: TriState) {
        libraryPreferences.filterTracking(trackerId.toInt()).set(value)
    }

    /**
     * `SetSortModeForCategory` decides for itself whether the choice belongs to the active category
     * or to the whole library, which is what keeps a per-category sort and the global one from
     * drifting apart, and it reseeds the random sort. It reads the database, so it runs on IO.
     */
    fun setSort(type: LibrarySort.Type, direction: LibrarySort.Direction) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                setSortModeForCategory.await(state.value.activeCategory, type, direction)
            }
        }
    }

    /**
     * Opens the next unread chapter of [mangaId], or says there is none.
     *
     * The button this comes from is drawn off a row's unread count, which is a projection that can
     * be a tick behind the chapter table, so "nothing left to read" is a real answer here rather
     * than a race, and it is worth a word instead of a tap that does nothing.
     *
     * Of the next number's copies it opens the one the title's scanlator ranking prefers, and the
     * first, as before, when the title is unranked (phase 4 unit SC).
     */
    fun continueReading(mangaId: Long) {
        viewModelScope.launch {
            val next = withContext(dispatchers.io) {
                getNextChapters.await(mangaId)
                    .removeDuplicates(null, getScanlatorPriority.await(mangaId))
                    .firstOrNull()
            }
            if (next == null) {
                eventChannel.send(Event.Snackbar(R.string.chapter_no_next_unread))
            } else {
                eventChannel.send(Event.OpenReader(mangaId, next.id))
            }
        }
    }

    /**
     * Writes [order], the tab of [categoryId] as a drag of [mangaId] left it on screen, as the tab's
     * new order (phase 4 unit MS). The order is one for the whole library, shared by every category,
     * so a tab does not number its entries from 1: its new order fills, first to last, the positions
     * its ranked entries already hold, and then as many positions after the library's last one as
     * the tab has unranked entries (position 0). The entries the new order puts last take those new
     * positions, whether or not they were the unranked ones. An entry in no other tab keeps its place
     * there; an entry the tab shares with another moves in both. The write is one [SetManualLibraryOrder] call
     * over the library's ranked entries in their new order.
     *
     * The page hands over the whole order it drew, not an index, because the tab can change under a
     * drag: a library update or a sync that lands mid-drag can reorder, add or remove the tab's
     * entries while the page keeps drawing the drag's own copy. The drop is only applied while the
     * tab, less [mangaId], still reads as [order] does; otherwise the owner dropped the entry among
     * rows that are no longer there, and any index into the tab as it now stands would put it
     * somewhere they did not, so nothing is written.
     *
     * Returns whether a write was made. Refused while [State.canReorder] is false, which the handle
     * already is; a drop that lands where the entry started writes nothing.
     */
    fun reorder(categoryId: Long, mangaId: Long, order: List<Long>): Boolean {
        val current = state.value
        if (!current.canReorder) return false
        val tab = current.items(categoryId).map { it.id }
        if (mangaId !in tab || order == tab) return false
        if (order.size != tab.size || order.filter { it != mangaId } != tab.filter { it != mangaId }) return false
        val write = manualOrder(libraryManga.values, order)
        if (write.isEmpty()) return false
        viewModelScope.launch {
            withContext(dispatchers.io) { setManualLibraryOrder.await(write) }
        }
        return true
    }

    /**
     * The library's ranked entries in the order [tab]'s move leaves them, cut after the last entry
     * whose position changes; [SetManualLibraryOrder] numbers them from 1.
     *
     * The ranked entries are taken by position, ties by id. The slots [tab]'s ranked entries hold
     * among them are handed out in [tab]'s order, first to last, and the entries left over (as many
     * as the tab has unranked entries, the last ones in [tab]'s order) take slots after the last. While the positions run 1 to n, which every write here leaves
     * them, each entry's number is its slot and only the entries whose slot changed are written
     * with a new value. A sync that merged two devices' orders can leave a gap or two entries on one
     * position; the write then numbers the library from 1 again, keeping every other entry's order.
     */
    private fun manualOrder(library: Collection<Manga>, tab: List<Long>): List<Long> {
        val ranked = library.filter { it.manualPosition > 0L }
            .sortedWith(compareBy<Manga>({ it.manualPosition }, { it.id }))
        val inTab = tab.toSet()
        val next = ArrayDeque(tab)
        val order = ranked.mapTo(mutableListOf()) { manga -> if (manga.id in inTab) next.removeFirst() else manga.id }
        order += next
        val positions = library.associate { it.id to it.manualPosition }
        var end = order.size
        while (end > 0 && positions[order[end - 1]] == end.toLong()) end--
        return order.subList(0, end)
    }

    fun showSettingsSheet() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun toggleSelection(mangaId: Long) {
        mutableState.update { current ->
            val selection = if (mangaId in current.selection) {
                current.selection - mangaId
            } else {
                current.selection + mangaId
            }
            current.copy(selection = selection)
        }
    }

    /**
     * A long press on [mangaId] in [categoryId]'s tab, upstream's `toggleRangeSelection`: see
     * [longPressSelection]. The order is that tab's rows as they are shown, after filter, search and
     * sort, so a range is what lies between the two covers on screen; what is selected on another
     * tab stays selected.
     */
    fun longPressEntry(categoryId: Long, mangaId: Long) {
        mutableState.update { current ->
            val order = current.items(categoryId).map(LibraryItemUi::id)
            current.copy(selection = longPressSelection(order, current.selection, mangaId))
        }
    }

    /**
     * Adds what the owner can see to the selection, so a filter, a search or the category tab they
     * are on all narrow what "all" means, and what was selected on another tab stays selected, as
     * upstream's `selectAll` adds the active category's items to its selection (phase 4 unit MP1).
     */
    fun selectAll() {
        mutableState.update { current ->
            current.copy(selection = current.selection + current.visibleItems.map(LibraryItemUi::id))
        }
    }

    /**
     * Inverts the selection over what the owner can see and keeps the rest of it, as upstream's
     * `invertSelection` flips the active category's items only (phase 4 unit MP1).
     */
    fun invertSelection() {
        mutableState.update { current ->
            val visible = current.visibleItems.mapTo(mutableSetOf(), LibraryItemUi::id)
            current.copy(selection = (current.selection - visible) + (visible - current.selection))
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    /**
     * Marks every selected entry read or unread.
     *
     * [SetReadStatus] takes a whole manga id and loads that entry's chapters itself, so the loop is
     * one suspending call per entry on [Dispatchers.io] rather than a chapter query per row raised
     * on the main thread. A merged title is every one of its members.
     */
    fun markSelectedRead(read: Boolean) {
        val ids = withMembers(state.value.selection.toList())
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(dispatchers.io) {
                ids.forEach { mangaId -> setReadStatus.await(mangaId, read) }
            }
            clearSelection()
        }
    }

    fun askRemoveSelected() {
        val selection = state.value.selection
        if (selection.isEmpty()) return
        val merged = selection.count { it in mergedMembers }
        mutableState.update { it.copy(dialog = Dialog.RemoveSelected(selection.size, merged)) }
    }

    /**
     * Clears the favourite flag, of every member of a merged title; the rows and their read progress
     * stay in the database.
     */
    fun removeSelectedFromLibrary() {
        val ids = withMembers(state.value.selection.toList())
        if (ids.isEmpty()) {
            dismissDialog()
            return
        }
        viewModelScope.launch {
            withContext(dispatchers.io) {
                ids.forEach { mangaId -> updateManga.awaitUpdateFavorite(mangaId, false) }
            }
            mutableState.update { it.copy(selection = emptySet(), dialog = null) }
        }
    }

    /** The merge dialog over the selection, in the order it was selected. */
    fun askMergeSelected() {
        val current = state.value
        if (!current.canMerge) return
        // An entry can leave the library while it is selected, and one entry is nothing to merge.
        val selected = current.selection.mapNotNull { libraryManga[it] }
        if (selected.size < 2) return
        viewModelScope.launch {
            val entries = withContext(dispatchers.io) {
                selected.map { MergeChoice(it.id, it.displayTitle, sourceManager.getOrStub(it.source).name) }
            }
            mutableState.update { it.copy(dialog = Dialog.MergeSelected(entries)) }
        }
    }

    /** The merge dialog's confirm. [MergeEntries] brings a selected merged title whole. */
    fun mergeSelected(mainId: Long) {
        val ids = (state.value.dialog as? Dialog.MergeSelected)?.entries?.map { it.mangaId } ?: return
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) { mergeEntries.await(mainId, ids) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not merge the selected entries into entry $mainId" }
                mutableState.update { it.copy(dialog = null) }
                eventChannel.send(Event.Snackbar(R.string.merged_failed))
                return@launch
            }
            mutableState.update { it.copy(selection = emptySet(), dialog = null) }
        }
    }

    /**
     * Opens the category picker over the current selection.
     *
     * The system category is left out of the rows. It is not a place an entry is put, it is what
     * the library view reports for an entry that is in no category at all, so offering it as a
     * checkbox would let the owner ask for a state the database cannot hold.
     */
    fun askMoveToCategories() {
        val selection = state.value.selection
        if (selection.isEmpty()) return
        viewModelScope.launch {
            val categories = withContext(dispatchers.io) { getCategories.await() }
                .filterNot { it.isSystemCategory }
            val membership = state.value.categoriesByManga
            val counts = categories.associate { category ->
                category.id to selection.count { mangaId -> category.id in membership[mangaId].orEmpty() }
            }
            mutableState.update {
                it.copy(dialog = Dialog.ChangeCategory(categoryChecks(categories, counts, selection.size)))
            }
        }
    }

    /**
     * Applies the picker's answer to every selected entry.
     *
     * Only the rows the owner actually resolved are acted on: [ToggleableState.On] adds a category
     * to all of them, [ToggleableState.Off] removes it from all of them, and an indeterminate row
     * is left exactly as it was found. Writing the checked ids as the entry's whole category list
     * instead, which is the obvious shape, would strip every partially applied category from every
     * entry in the selection without the owner ever seeing that category resolved.
     *
     * A merged title's answer is written to every member, so each holds the categories the title
     * shows and a later unmerge or a removed source lands where the owner put the title.
     */
    fun moveSelectedToCategories(rows: List<CategoryCheck>) {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) {
            dismissDialog()
            return
        }
        val membership = state.value.categoriesByManga
        val add = rows.filter { it.state == ToggleableState.On }.map { it.id }
        val remove = rows.filter { it.state == ToggleableState.Off }.mapTo(mutableSetOf()) { it.id }
        viewModelScope.launch {
            withContext(dispatchers.io) {
                ids.forEach { mangaId ->
                    // The feed reports the system category for an entry that is in none, and that
                    // is a reading of an empty list rather than a row worth writing back.
                    val current = membership[mangaId].orEmpty()
                        .filterNot { it == Category.UNCATEGORIZED_ID }
                    val categories = (current - remove + add).distinct()
                    withMembers(listOf(mangaId)).forEach { setMangaCategories.await(it, categories) }
                }
            }
            mutableState.update { it.copy(selection = emptySet(), dialog = null) }
        }
    }

    /**
     * The selection's download menu (phase 3 unit AD), upstream's `performDownloadAction` as written:
     * for each selected entry, the next N unread chapters in reading order ([GetNextChapters]), or
     * every one for Unread, or its bookmarked chapters for Bookmarked, less those already downloaded
     * or in the queue. They are queued oldest source position first, the order upstream's `Downloader`
     * gives them, and the queue is started. The selection clears at once, as upstream's does. A
     * merged title reads its merged list and queues each chapter under the entry it belongs to.
     */
    fun downloadSelected(action: DownloadAction) {
        val mangas = state.value.selection.mapNotNull { libraryManga[it] }
        val members = mergedMembers
        clearSelection()
        if (mangas.isEmpty()) return
        val amount = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> 1
            DownloadAction.NEXT_5_CHAPTERS -> 5
            DownloadAction.NEXT_10_CHAPTERS -> 10
            DownloadAction.NEXT_25_CHAPTERS -> 25
            DownloadAction.UNREAD_CHAPTERS, DownloadAction.BOOKMARKED_CHAPTERS -> null
        }
        viewModelScope.launch {
            withContext(dispatchers.io) {
                val queued = downloadQueue.subscribe().first().mapTo(mutableSetOf()) { it.chapterId }
                mangas.forEach { manga ->
                    val entries = members[manga.id] ?: listOf(manga)
                    val downloaded = entries.flatMapTo(HashSet()) { downloadedChapters.subscribeByMangaId(it.id).first() }
                    val chapters = if (action == DownloadAction.BOOKMARKED_CHAPTERS) {
                        entries.flatMap { getBookmarkedChaptersByMangaId.await(it.id) }
                    } else {
                        getNextChapters.await(manga.id)
                    }
                        .filterNot { it.id in queued || it.id in downloaded }
                        .let { if (amount != null) it.take(amount) else it }
                    chapters.groupBy { it.mangaId }.forEach { (ownerId, own) ->
                        val owner = entries.firstOrNull { it.id == ownerId } ?: manga
                        if (owner.source != SourceIds.LOCAL) {
                            downloadQueue.enqueue(owner, own.sortedByDescending { it.sourceOrder }, autoStart = true)
                        }
                    }
                }
            }
        }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    /** [ids] with each merged title replaced by its members, for the writes that act on every source. */
    private fun withMembers(ids: List<Long>): List<Long> =
        ids.flatMap { id -> mergedMembers[id]?.map(Manga::id) ?: listOf(id) }

    /** The fields the projection owns. Everything else in [State] belongs to the main thread. */
    private data class Projection(
        val categories: List<Category>,
        val categoryParents: Map<Long, Category>,
        val itemsByCategory: Map<Long, List<LibraryItemUi>>,
        val total: Int,
        val shown: Int,
        val query: String,
        val categoriesByManga: Map<Long, List<Long>>,
        val localManga: Set<Long>,
        val mangaById: Map<Long, Manga>,
        val mergedMembers: Map<Long, List<Manga>>,
        val filters: LibraryFilters,
        val downloadedOnly: Boolean,
        val globalSort: LibrarySort,
        val categorizedSort: Boolean,
        val manualSort: Boolean,
        val displayMode: LibraryDisplayMode,
        val columns: Int,
        val showCategoryTabs: Boolean,
        val showItemCount: Boolean,
        val signedInTrackers: List<SignedInTracker>,
        val customUpdateInterval: Boolean,
        val mergedEntries: Boolean,
    )

    /**
     * What the tracker filter and the tracker score sort read (phase 3 unit T3): every track row by
     * manga id, the signed-in trackers, and each one's filter preference.
     */
    private data class Tracking(
        val tracks: Map<Long, List<Track>> = emptyMap(),
        val trackers: List<Tracker> = emptyList(),
        val filters: Map<Long, TriState> = emptyMap(),
    )

    /**
     * [Tracking] as its three inputs change, as upstream's library re-reads them: a bind or a score
     * stored, a sign-in or sign-out, or a tracker filter set. With no tracker in the graph it reads
     * nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun tracking(): Flow<Tracking> {
        if (trackerManager.trackers.isEmpty()) return flowOf(Tracking())
        val signedIn = trackerManager.loggedInTrackersFlow().flatMapLatest { trackers ->
            if (trackers.isEmpty()) {
                flowOf(trackers to emptyMap())
            } else {
                combine(
                    trackers.map { tracker ->
                        libraryPreferences.filterTracking(tracker.id.toInt()).withCurrent().map { tracker.id to it }
                    },
                ) { trackers to it.toMap() }
            }
        }
        return combine(trackRepository.getTracksAsFlow(), signedIn) { tracks, (trackers, filters) ->
            Tracking(tracks.groupBy { it.mangaId }, trackers, filters)
        }
    }

    /**
     * Upstream's tracker score: the mean of `get10PointScore` over a manga's rows whose tracker is
     * signed in, an unscored row counting as its 0. A manga with none has no entry, which
     * [SortLibrary] orders below every score; null while no tracker is signed in.
     */
    private fun trackerMeans(tracking: Tracking): Map<Long, Double>? {
        if (tracking.trackers.isEmpty()) return null
        val trackers = tracking.trackers.associateBy { it.id }
        return tracking.tracks.mapNotNull { (mangaId, rows) ->
            val scores = rows.mapNotNull { row -> trackers[row.trackerId]?.get10PointScore(row) }
            if (scores.isEmpty()) null else mangaId to scores.average()
        }.toMap()
    }

    private fun project(
        items: List<LibraryItem>,
        categories: List<Category>,
        sources: List<CatalogueSource>,
        query: String,
        tracking: Tracking,
    ): Projection {
        val startedAt = System.nanoTime()
        val liveSources = sources.associate { source ->
            source.id to DomainSource(
                id = source.id,
                lang = source.lang,
                name = source.name,
                supportsLatest = source.supportsLatest,
                isStub = false,
            )
        }
        // A source no live source has is named through getOrStub, its remembered name, as upstream's
        // library names every entry's source, so a search for a removed extension's source name
        // still finds its entries: search it, Select all, Migrate (phase 4 unit MB).
        val notInstalled = items.asSequence()
            .map { it.manga.source }
            .distinct()
            .filterNot { it in liveSources }
            .associateWith { id ->
                val source = sourceManager.getOrStub(id)
                DomainSource(
                    id = id,
                    lang = source.lang,
                    name = source.name,
                    supportsLatest = false,
                    isStub = source is StubSource,
                )
            }
        val sourceMap = liveSources + notInstalled
        val downloadedOnly = basePreferences.downloadedOnly.get()
        val filters = filters(downloadedOnly).copy(tracked = tracking.filters)
        val filterStartedAt = System.nanoTime()
        val filtered = filterLibrary.apply(items, filters, QueryNode.from(query), sourceMap, tracking.tracks)
        val filterNanos = System.nanoTime() - filterStartedAt

        // Upstream's rule, plus the one-category case the contract adds: the default category is a
        // reading of "in no category at all", so it earns a tab only when something is in it, or
        // when hiding it would leave the library with no tab to show.
        val showDefault = categories.size == 1 || items.any { Category.UNCATEGORIZED_ID in it.categoryIds }
        // The tree's order (phase 5 unit CT): a child's tab follows its parent's. Membership is flat
        // (D8), so the pages are grouped exactly as before; only their order and the label move.
        val tree = CategoryTree.of(categories).filter { showDefault || !it.category.isSystemCategory }
        val displayed = tree.map { it.category }

        val grouped = mutableMapOf<Long, MutableList<LibraryItem>>()
        filtered.forEach { item ->
            item.categoryIds.forEach { categoryId -> grouped.getOrPut(categoryId) { mutableListOf() }.add(item) }
        }

        val globalSort = libraryPreferences.sortingMode.get()
        val categorized = libraryPreferences.categorizedDisplaySettings.get()
        val seed = libraryPreferences.randomSortSeed.get()
        // Contract 04: the manual order applies while the toggle is on and the category has no sort
        // of its own, which a per-category sort gives every category (phase 4 unit MS).
        val manualFirst = libraryPreferences.manualSortEnabled.get() && !categorized
        val unreadBadge = libraryPreferences.unreadBadge.get()
        val downloadBadge = libraryPreferences.downloadBadge.get()
        val localBadge = libraryPreferences.localBadge.get()
        val languageBadge = libraryPreferences.languageBadge.get()
        val continueReading = libraryPreferences.showContinueReadingButton.get()
        val trackerMeans = trackerMeans(tracking)

        val itemsByCategory = displayed.associate { category ->
            val sorted = sortLibrary.apply(
                items = grouped[category.id].orEmpty(),
                sort = if (categorized) category.sort else globalSort,
                seed = seed,
                trackerMeans = trackerMeans,
                manualFirst = manualFirst,
            )
            category.id to sorted.map { item ->
                LibraryItemUi(
                    item = item,
                    unreadBadge = item.unreadCount.takeIf { unreadBadge && it > 0 },
                    downloadBadge = item.downloadCount.takeIf { downloadBadge && it > 0 },
                    localBadge = localBadge && item.isLocal,
                    languageBadge = item.sourceLanguage.takeIf { languageBadge && it.isNotBlank() },
                    showContinueReading = continueReading && item.unreadCount > 0,
                )
            }
        }

        val projection = Projection(
            categories = displayed,
            categoryParents = tree.mapNotNull { node -> node.parent?.let { node.category.id to it } }.toMap(),
            itemsByCategory = itemsByCategory,
            total = items.size,
            shown = filtered.size,
            query = query,
            categoriesByManga = items.flatMap { item ->
                val categories = item.libraryManga.categories
                item.members.map { it.id to categories } + (item.id to categories)
            }.toMap(),
            localManga = items.filter { it.isLocal }.mapTo(mutableSetOf()) { it.id },
            mangaById = items.associate { it.id to it.manga },
            mergedMembers = items.filter { it.members.isNotEmpty() }.associate { it.id to it.members },
            filters = filters,
            downloadedOnly = downloadedOnly,
            globalSort = globalSort,
            categorizedSort = categorized,
            manualSort = manualFirst,
            displayMode = libraryPreferences.displayMode.get(),
            columns = columnsPreference().get(),
            showCategoryTabs = libraryPreferences.categoryTabs.get(),
            showItemCount = libraryPreferences.categoryNumberOfItems.get(),
            signedInTrackers = tracking.trackers.map { SignedInTracker(it.id, it.name) },
            customUpdateInterval = libraryPreferences.customUpdateInterval.get(),
            mergedEntries = libraryPreferences.mergedEntries.get(),
        )
        logProjection(items.size, (System.nanoTime() - startedAt) / NANOS_PER_MILLI, filterNanos / NANOS_PER_MILLI)
        return projection
    }

    /**
     * The debug build's timing of one projection (phase 5 unit SC4's item 1), in the uptime clock a
     * device test reads, so `ScaleRowsTest` can split the library rows into the query, the
     * projection (and the filter's share of it) and the frame. Logged at DEBUG, which a release build drops before the message is
     * built; the clock is read inside the message for that reason.
     */
    private fun logProjection(entries: Int, millis: Long, filterMillis: Long) {
        logcat(LogPriority.DEBUG) {
            val now = SystemClock.elapsedRealtime()
            "$PROJECTION_LOG $entries entries, inputs at ${now - millis}, projected in $millis ms, filtered in $filterMillis ms"
        }
    }

    /**
     * `BasePreferences.downloadedOnly` forces the downloaded filter on, which is the whole meaning
     * of the switch: with nothing downloaded the library reads as empty, and that is the truthful
     * answer to "only downloaded" rather than a switch that changes nothing. The custom interval
     * filter's row is hidden while its experimental switch is off, so a stored value does nothing
     * then: a filter nobody can see would leave the library looking half missing.
     */
    private fun filters(downloadedOnly: Boolean) = LibraryFilters(
        downloaded = if (downloadedOnly) TriState.ENABLED_IS else libraryPreferences.filterDownloaded.get(),
        unread = libraryPreferences.filterUnread.get(),
        started = libraryPreferences.filterStarted.get(),
        bookmarked = libraryPreferences.filterBookmarked.get(),
        completed = libraryPreferences.filterCompleted.get(),
        intervalCustom = libraryPreferences.filterIntervalCustom.get()
            .takeIf { libraryPreferences.customUpdateInterval.get() }
            ?: TriState.DISABLED,
    )

    private fun preferenceFor(filter: LibraryFilter) = when (filter) {
        LibraryFilter.DOWNLOADED -> libraryPreferences.filterDownloaded
        LibraryFilter.UNREAD -> libraryPreferences.filterUnread
        LibraryFilter.STARTED -> libraryPreferences.filterStarted
        LibraryFilter.BOOKMARKED -> libraryPreferences.filterBookmarked
        LibraryFilter.COMPLETED -> libraryPreferences.filterCompleted
        LibraryFilter.INTERVAL_CUSTOM -> libraryPreferences.filterIntervalCustom
    }

    private fun columnsPreference() = if (landscapeFlow.value) {
        libraryPreferences.landscapeColumns
    } else {
        libraryPreferences.portraitColumns
    }

    /**
     * An entry in no category at all is shown under the default one, which is what the library feed
     * already reports for it; the fallback only covers a row whose category list arrived empty,
     * which would otherwise be in no group and so invisible.
     */
    private val LibraryItem.categoryIds: List<Long>
        get() = libraryManga.categories.ifEmpty { listOf(Category.UNCATEGORIZED_ID) }

    /**
     * One tick per change to anything the projection reads with `get()`, plus the orientation.
     * Merging the change flows and re-reading is deliberate: `combine` takes at most five flows, and
     * a row's projection depends on twenty preferences.
     */
    private fun settingsChanges(): Flow<Unit> = merge(
        libraryPreferences.filterDownloaded.changes(),
        libraryPreferences.filterUnread.changes(),
        libraryPreferences.filterStarted.changes(),
        libraryPreferences.filterBookmarked.changes(),
        libraryPreferences.filterCompleted.changes(),
        libraryPreferences.filterIntervalCustom.changes(),
        libraryPreferences.customUpdateInterval.changes(),
        libraryPreferences.mergedEntries.changes(),
        libraryPreferences.sortingMode.changes(),
        libraryPreferences.randomSortSeed.changes(),
        libraryPreferences.manualSortEnabled.changes(),
        libraryPreferences.categorizedDisplaySettings.changes(),
        libraryPreferences.displayMode.changes(),
        libraryPreferences.portraitColumns.changes(),
        libraryPreferences.landscapeColumns.changes(),
        libraryPreferences.unreadBadge.changes(),
        libraryPreferences.downloadBadge.changes(),
        libraryPreferences.localBadge.changes(),
        libraryPreferences.languageBadge.changes(),
        libraryPreferences.categoryTabs.changes(),
        libraryPreferences.categoryNumberOfItems.changes(),
        libraryPreferences.showContinueReadingButton.changes(),
        basePreferences.downloadedOnly.changes(),
        landscapeFlow,
    ).map { }.onStart { emit(Unit) }

    /** The six library filter toggles, so a row in the sheet names one instead of a preference. */
    enum class LibraryFilter {
        DOWNLOADED,
        UNREAD,
        STARTED,
        BOOKMARKED,
        COMPLETED,
        INTERVAL_CUSTOM,
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val NANOS_PER_MILLI = 1_000_000L

        /** The prefix `ScaleRowsTest` finds the projection's timing lines by. */
        const val PROJECTION_LOG = "LibraryProjection:"
    }
}
