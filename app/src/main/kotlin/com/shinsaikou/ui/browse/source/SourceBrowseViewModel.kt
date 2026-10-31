package com.shinsaikou.ui.browse.source

import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.BrowseMode
import com.shinsaikou.domain.source.interactor.GetSourceMangaPage
import com.shinsaikou.navigation.SourceBrowseMode
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.reader.state.IncognitoPolicy
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.ui.components.CategoryCheck
import com.shinsaikou.ui.components.categoryChecks
import com.shinsaikou.ui.longPressSelection
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * One source's listing. Pages are appended by hand: contract 08 decision 3 keeps androidx.paging
 * out of the app, and the only state a listing needs is the last page number and whether the source
 * says there is another one.
 *
 * The mode is [SourceBrowseMode], the enum the route itself carries, rather than a second one
 * declared here. A deep link asking for "latest" and the chips on screen then cannot mean two
 * different listings, and the screen needs no translation layer between them.
 *
 * The source's own filter tree lives on [State.filters]. Until it did, every search went out with
 * `FilterList.EMPTY`, which made genre, status and sort unreachable for every source in the app.
 *
 * The display mode is upstream's `pref_display_mode_catalogue`, one for every source, and the grid's
 * columns are the library's portrait and landscape keys, as upstream's browse grid reads them. Both
 * orientations' counts are in state because the screen, not the view model, knows which one it is.
 *
 * Whether an entry is in the library is read live from the source's favourites, as upstream's browse
 * follows each entry, so one added from its details and brought back to shows its badge and faded
 * cover without the listing being fetched again.
 *
 * With [migratingMangaId] the listing is in pick mode (phase 4 unit MB, upstream's
 * `MigrateSourceSearchScreen`), opened from manual search for a migration: the entry is read once, for
 * MA's dialog when no migration list is below. Null keeps the listing as it was.
 *
 * Opening a listing, in any mode and in pick mode too, makes its source the last used one
 * (`lastUsedSource`) unless the source is incognito, as upstream's `BrowseSourceViewModel` init does
 * (`:108-110`); the Sources page lists it under "Last used" (phase 4 unit MP2).
 *
 * Outside pick mode a long press selects entries, as on the entry screen and Updates (the owner's
 * request of 2026-10-24, for adding many entries at once): [longPressSelection] over the listing's
 * order, so a second long press selects every entry between, and the bar adds the selection to the
 * library. Upstream's browse has no selection; its long press adds the one entry.
 */
@AssistedInject
class SourceBrowseViewModel(
    @Assisted private val sourceId: Long,
    @Assisted query: String?,
    @Assisted mode: SourceBrowseMode,
    @Assisted private val migratingMangaId: Long?,
    private val getSourceMangaPage: GetSourceMangaPage,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val getFavorites: GetFavorites,
    private val getManga: GetManga,
    private val incognitoPolicy: IncognitoPolicy,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val dispatchers: Dispatchers,
) : StateViewModel<SourceBrowseViewModel.State>(State()) {

    data class State(
        val sourceName: String? = null,
        val supportsLatest: Boolean = false,
        val installed: Boolean = true,
        /** The source's own front page, when it has one. Null for a source with nothing to open. */
        val homeUrl: String? = null,
        val mode: SourceBrowseMode = SourceBrowseMode.POPULAR,
        val query: String = "",
        /** The live tree the sheet edits in place. Empty when the source declares no filters. */
        val filters: FilterList = FilterList.EMPTY,
        val filtersApplied: Boolean = false,
        val items: List<Manga> = emptyList(),
        val page: Int = 0,
        val hasNextPage: Boolean = false,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        /** The first page's failure; the listing shows it in place of the entries. */
        val error: Throwable? = null,
        /**
         * A later page's failure, with the entries already loaded kept: [page] is still the last page
         * that loaded, so [retryNextPage] asks for the one that failed (phase 4 unit MP1).
         */
        val nextPageError: Throwable? = null,
        val displayMode: LibraryDisplayMode = LibraryDisplayMode.default,
        /** 0 is the adaptive count, as in the library. */
        val portraitColumns: Int = 0,
        val landscapeColumns: Int = 0,
        /** The source's entries in the library, live. Null until the first read. */
        val favoriteIds: Set<Long>? = null,
        /** In pick mode, the entry being migrated, and the result picked with no migration list below. */
        val migratingFrom: Manga? = null,
        val migrateTarget: Manga? = null,
        /** The selected entries' ids. Empty outside selection mode; a new listing clears it. */
        val selection: Set<Long> = emptySet(),
        /** The category picker for adding the selection, when no default category answers it. */
        val addCategories: List<CategoryCheck>? = null,
    ) {
        /** Until the live set has arrived, the flag the page was fetched with. */
        fun inLibrary(manga: Manga): Boolean = favoriteIds?.contains(manga.id) ?: manga.favorite

        val selectionMode: Boolean get() = selection.isNotEmpty()

        /** The selected entries not yet in the library, in the listing's order: what Add would add. */
        fun selectedToAdd(): List<Long> = items.filter { it.id in selection && !inLibrary(it) }.map { it.id }
    }

    sealed interface Event {
        data class Added(val count: Int) : Event

        /** Add was pressed on a selection that is all in the library already. */
        data object AllInLibrary : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** Null until the registry has loaded, and afterwards when the source is not installed. */
    private var source: CatalogueSource? = null

    /**
     * The tree as the source declared it, snapshotted before anything could touch it. It is the
     * yardstick for [State.filtersApplied], and a snapshot rather than a second `getFilterList()`
     * call because that call has a side effect (see [open]).
     */
    private var defaultFilters: FilterList = FilterList.EMPTY

    private var pageJob: Job? = null

    init {
        mutableState.update { it.copy(query = query.orEmpty()) }
        viewModelScope.launch {
            // Ruling R69 item 4. A listing restored after process death comes back as the process
            // starts, when an installed extension's source still reads as missing, and the source
            // is looked up once for the screen's life. So the lookup waits for the registry, with
            // the listing's loading row on screen meanwhile.
            sourceManager.isInitialized.first { it }
            // After the wait, where upstream's screen waits for its sources before building the view
            // model: an extension's incognito switch is read off the loaded extensions.
            if (!incognitoPolicy.isIncognito(sourceId)) sourcePreferences.lastUsedSource.set(sourceId)
            open(sourceManager.get(sourceId) as? CatalogueSource, query, mode)
        }
        viewModelScope.launch {
            displayChanges().collect {
                mutableState.update {
                    it.copy(
                        displayMode = sourcePreferences.sourceDisplayMode.get(),
                        portraitColumns = libraryPreferences.portraitColumns.get(),
                        landscapeColumns = libraryPreferences.landscapeColumns.get(),
                    )
                }
            }
        }
        if (migratingMangaId != null) {
            viewModelScope.launch {
                val manga = withContext(dispatchers.io) { getManga.await(migratingMangaId) }
                mutableState.update { it.copy(migratingFrom = manga) }
            }
        }
        viewModelScope.launch {
            getFavorites.subscribe(sourceId)
                .map { favorites -> favorites.mapTo(HashSet()) { it.id } }
                .flowOn(dispatchers.io)
                .collect { ids -> mutableState.update { it.copy(favoriteIds = ids) } }
        }
    }

    /** The toolbar's menu. Every source's listing follows, as upstream's one key makes it. */
    fun setDisplayMode(mode: LibraryDisplayMode) {
        sourcePreferences.sourceDisplayMode.set(mode)
    }

    /** One tick now and one per change to the mode or either column count, from here or the library. */
    private fun displayChanges() = merge(
        sourcePreferences.sourceDisplayMode.changes(),
        libraryPreferences.portraitColumns.changes(),
        libraryPreferences.landscapeColumns.changes(),
    ).map { }.onStart { emit(Unit) }

    private fun open(source: CatalogueSource?, query: String?, mode: SourceBrowseMode) {
        this.source = source
        // getFilterList() builds a fresh default tree on every call and, for an extension source,
        // replaces the adapter's cache of the extension's own filter objects along the way. Ask
        // once and hold what came back: a second call mid-edit would throw the edits away and leave
        // the adapter mapping states onto instances nobody is looking at.
        val filters = source?.getFilterList() ?: FilterList.EMPTY
        defaultFilters = filters.deepCopy()
        val openingMode = when {
            source == null -> SourceBrowseMode.POPULAR
            mode == SourceBrowseMode.LATEST && !source.supportsLatest -> SourceBrowseMode.POPULAR
            // A search route with nothing to search for is a popular listing, not an empty search.
            mode == SourceBrowseMode.SEARCH && query.isNullOrBlank() -> SourceBrowseMode.POPULAR
            else -> mode
        }
        mutableState.update {
            it.copy(
                sourceName = source?.name,
                supportsLatest = source?.supportsLatest == true,
                installed = source != null,
                homeUrl = source?.homeUrl,
                mode = openingMode,
                filters = filters,
            )
        }
        if (source == null) {
            mutableState.update { it.copy(loading = false) }
        } else {
            loadFirstPage()
        }
    }

    fun setMode(mode: SourceBrowseMode) {
        if (mode == state.value.mode) return
        if (mode == SourceBrowseMode.LATEST && !state.value.supportsLatest) return
        mutableState.update { it.copy(mode = mode) }
        if (mode != SourceBrowseMode.SEARCH || state.value.query.isNotBlank()) loadFirstPage()
    }

    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    /**
     * Search runs on submit rather than on every keystroke. A source is a remote server with its
     * own rate limiter, and a query per character is the fastest way to be throttled.
     *
     * Blank text is allowed once filters are set, because a filter-only search is a real search:
     * "everything tagged comedy" needs no words.
     */
    fun submitSearch() {
        val current = state.value
        if (current.query.isBlank() && !current.filtersApplied) return
        mutableState.update { it.copy(mode = SourceBrowseMode.SEARCH) }
        loadFirstPage()
    }

    /**
     * Take the search off, text and filter tree together.
     *
     * The tree goes with it because only a SEARCH request carries filters at all: dropping the mode
     * and keeping the edits would leave the bar reporting filters in force, and the sheet showing
     * them ticked, over a popular listing whose request carries none. The listing on screen is what
     * the indicator has to be true about, so the flag, the tree and the request are cleared in one
     * step rather than left to disagree.
     *
     * The tree comes back from the source rather than from the snapshot, for the reason
     * [resetFilters] gives.
     */
    fun clearSearch() {
        val fresh = source?.getFilterList() ?: FilterList.EMPTY
        mutableState.update {
            it.copy(query = "", mode = SourceBrowseMode.POPULAR, filters = fresh, filtersApplied = false)
        }
        loadFirstPage()
    }

    /**
     * Take the tree back from the sheet. The sheet hands over the very instances it was given: the
     * extension adapter maps states back onto the extension's own filter objects positionally, and
     * a rebuilt tree would arrive with the wrong shape or lose the subclass the extension reads its
     * request parameters off.
     *
     * Re-emitting is what makes an in-place edit visible downstream, and it works precisely because
     * `FilterList.equals` is unconditionally false: `MutableStateFlow` would otherwise compare the
     * new state to the old, find them equal, and swallow the update.
     */
    fun setFilters(filters: FilterList) {
        mutableState.update {
            it.copy(filters = filters, filtersApplied = filters.differsFrom(defaultFilters))
        }
    }

    /**
     * Back to the source's own defaults. This asks the source for a new tree instead of restoring
     * the snapshot, because for an extension source the call also refreshes the adapter's cache, so
     * the instances the next search writes onto are the ones now on screen.
     */
    fun resetFilters() {
        val fresh = source?.getFilterList() ?: FilterList.EMPTY
        mutableState.update { it.copy(filters = fresh, filtersApplied = false) }
    }

    /**
     * Run the current text against the tree as it stands, from page one. Blank text with untouched
     * filters is not a search at all, so it falls back to the popular listing rather than asking
     * the source to match the empty string.
     */
    fun applyFilters() {
        val current = state.value
        val mode = if (current.query.isBlank() && !current.filtersApplied) {
            SourceBrowseMode.POPULAR
        } else {
            SourceBrowseMode.SEARCH
        }
        mutableState.update { it.copy(mode = mode) }
        loadFirstPage()
    }

    fun retry() = loadFirstPage()

    /**
     * The retry of a later page that failed: the failed page is asked for again and every entry
     * already listed stays, as upstream's listing retries the page its paging source could not append.
     */
    fun retryNextPage() = loadNextPage()

    /** A result picked with no migration list below: MA's dialog asks what to migrate onto it. */
    fun showMigrateDialog(target: Manga) {
        mutableState.update { it.copy(migrateTarget = target) }
    }

    fun dismissMigrateDialog() {
        mutableState.update { it.copy(migrateTarget = null) }
    }

    /** A tap on an entry while entries are selected. */
    fun toggleSelection(mangaId: Long) {
        mutableState.update { current ->
            val selection = if (mangaId in current.selection) current.selection - mangaId else current.selection + mangaId
            current.copy(selection = selection)
        }
    }

    /**
     * A long press on [mangaId]'s entry: see [longPressSelection], over the entries in the order the
     * listing shows them. Pick mode has no selection; its long press opens the entry.
     */
    fun longPressEntry(mangaId: Long) {
        if (migratingMangaId != null) return
        mutableState.update { current ->
            current.copy(selection = longPressSelection(current.items.map { it.id }, current.selection, mangaId))
        }
    }

    /** Every entry loaded so far; later pages are not fetched for it. */
    fun selectAll() {
        mutableState.update { current -> current.copy(selection = current.items.mapTo(mutableSetOf()) { it.id }) }
    }

    fun invertSelection() {
        mutableState.update { current ->
            current.copy(selection = current.items.mapTo(mutableSetOf()) { it.id } - current.selection)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    /**
     * Adds the selected entries that are not in the library yet; those that are keep their
     * categories. The category follows upstream's add from browse (`BrowseSourceScreen`'s long press):
     * the default category when one is set, none when the default is the built-in one or there are
     * no categories, and otherwise the picker, asked once for the whole selection.
     *
     * No duplicate question is asked here. The entry screen asks one per entry, and a question per
     * entry would stop a bulk add at every title the library already holds from another source.
     */
    fun addSelectedToLibrary() {
        val ids = state.value.selectedToAdd()
        if (ids.isEmpty()) {
            clearSelection()
            viewModelScope.launch { eventChannel.send(Event.AllInLibrary) }
            return
        }
        viewModelScope.launch {
            val categories = withContext(dispatchers.io) { getCategories.await() }.filterNot { it.isSystemCategory }
            val default = libraryPreferences.defaultCategory.get().toLong()
            when {
                categories.any { it.id == default } -> add(ids, listOf(default))
                default == Category.UNCATEGORIZED_ID || categories.isEmpty() -> add(ids, emptyList())
                else -> mutableState.update { it.copy(addCategories = categoryChecks(categories, emptyMap(), ids.size)) }
            }
        }
    }

    /** The picker's answer: the checked categories, for every entry being added. */
    fun confirmAddCategories(rows: List<CategoryCheck>) {
        val ids = state.value.selectedToAdd()
        mutableState.update { it.copy(addCategories = null) }
        if (ids.isEmpty()) return
        viewModelScope.launch { add(ids, rows.filter { it.state == ToggleableState.On }.map { it.id }) }
    }

    /** The picker dismissed: nothing is added and the selection stays, for another try. */
    fun dismissAddCategories() {
        mutableState.update { it.copy(addCategories = null) }
    }

    /**
     * The selection is cleared before the write, as the entry screen's bulk actions do, so the bar
     * is gone by the time the badges appear. Each entry gets the chapter defaults first, as upstream's
     * add from browse does (`BrowseSourceViewModel.changeMangaFavorite`).
     */
    private suspend fun add(ids: List<Long>, categoryIds: List<Long>) {
        val idSet = ids.toSet()
        val entries = state.value.items.filter { it.id in idSet }
        clearSelection()
        withContext(dispatchers.io) {
            entries.forEach { setMangaDefaultChapterFlags.await(it) }
            updateManga.awaitUpdateFavorites(ids, favorite = true)
            ids.forEach { setMangaCategories.await(it, categoryIds) }
        }
        eventChannel.send(Event.Added(ids.size))
    }

    fun loadFirstPage() {
        pageJob?.cancel()
        mutableState.update {
            it.copy(
                items = emptyList(),
                page = 0,
                hasNextPage = false,
                loading = true,
                error = null,
                nextPageError = null,
                // The entries it held are leaving the screen, and a selection of rows nobody can see
                // would be added by a bar that names only a count.
                selection = emptySet(),
            )
        }
        pageJob = fetch(1)
    }

    /**
     * The page after the last one that loaded. After a later page failed that is the failed page
     * again, whether [retryNextPage] or the scroll asked for it.
     */
    fun loadNextPage() {
        val current = state.value
        if (current.loading || current.loadingMore || !current.hasNextPage) return
        mutableState.update { it.copy(loadingMore = true, nextPageError = null) }
        pageJob = fetch(current.page + 1)
    }

    private fun fetch(page: Int): Job = viewModelScope.launch {
        try {
            val result = withContext(dispatchers.io) {
                getSourceMangaPage.await(sourceId, modeOf(state.value), page)
            }
            val hideInLibrary = sourcePreferences.hideInLibraryItems.get()
            val fresh = if (hideInLibrary) result.mangas.filterNot { it.favorite } else result.mangas
            mutableState.update { current ->
                // Deduplicated by id: a source that repeats an entry across pages would otherwise
                // give LazyColumn two rows with the same key and crash the list.
                val seen = current.items.mapTo(mutableSetOf()) { it.id }
                current.copy(
                    items = current.items + fresh.filter { seen.add(it.id) },
                    page = result.page,
                    hasNextPage = result.hasNextPage,
                    loading = false,
                    loadingMore = false,
                    error = null,
                    nextPageError = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Only the first page's failure replaces the listing; a later page's leaves the entries
            // and the page count as they were, for its retry to continue from.
            mutableState.update {
                if (page == 1) {
                    it.copy(loading = false, loadingMore = false, error = e)
                } else {
                    it.copy(loading = false, loadingMore = false, nextPageError = e)
                }
            }
        }
    }

    private fun modeOf(state: State): BrowseMode = when (state.mode) {
        SourceBrowseMode.POPULAR -> BrowseMode.Popular
        SourceBrowseMode.LATEST -> BrowseMode.Latest
        SourceBrowseMode.SEARCH -> BrowseMode.Search(state.query, state.filters)
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long, query: String?, mode: SourceBrowseMode, migratingMangaId: Long?): SourceBrowseViewModel
    }
}

/**
 * Whether [this] tree carries an edit against [defaults].
 *
 * The obvious `this == defaults` is the wrong tool: `FilterList.equals` returns false
 * unconditionally by design, so it would report "the user changed something" for a tree straight
 * out of `getFilterList()` that nobody has opened. Walking the two lists in step is correct instead
 * because `Filter.equals` is structural on name and state, and a Group's state is its child list,
 * so nested groups compare themselves without a recursive helper here.
 */
internal fun FilterList.differsFrom(defaults: FilterList): Boolean =
    list.size != defaults.list.size || list.indices.any { index -> list[index] != defaults.list[index] }
