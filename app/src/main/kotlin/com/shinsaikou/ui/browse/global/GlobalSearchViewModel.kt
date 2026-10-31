package com.shinsaikou.ui.browse.global

import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.BrowseMode
import com.shinsaikou.domain.source.interactor.GetSourceMangaPage
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.ui.browse.isInEnabledLanguage
import com.shinsaikou.ui.browse.matureSourceIds
import com.shinsaikou.ui.util.languageName
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * One query against every source the Browse tab would list, with each source's answer kept apart
 * from the others.
 *
 * The shape of the state is what the failure modes dictate. Twenty sources are twenty remote
 * servers with twenty independent ways to be slow, rate limited or simply gone, so a single
 * loading flag and a single error field would let the slowest source decide when anything at all
 * is shown, and the first broken one decide that nothing is. Each source therefore carries its own
 * [Status] and the screen renders whatever has arrived so far.
 *
 * With [migratingMangaId] it is manual search for a migration (phase 4 unit MB), upstream's
 * `MigrateSearchViewModel` over this screen's own search: the entry's title is searched at once,
 * the sources are the migration's target sources in their saved order, and the groups holding
 * results come first. Saikou's limits stay: four sources in flight, page one, ten results kept and
 * the empty filter list. Null keeps Global Search as it was.
 *
 * Upstream's two chips are back: Pinned / All picks the sources a search asks (Global Search only;
 * a migration asks its own), and Has results hides the groups with nothing to show. Both are
 * remembered on this device. A result already in the library is marked from the live favourites,
 * so an entry added from its details is marked on return, as Browse marks it (R108 item 7).
 */
@AssistedInject
class GlobalSearchViewModel(
    @Assisted private val initialQuery: String?,
    @Assisted private val migratingMangaId: Long?,
    private val getManga: GetManga,
    private val getSourceMangaPage: GetSourceMangaPage,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    mangaRepository: MangaRepository,
    private val dispatchers: Dispatchers,
) : StateViewModel<GlobalSearchViewModel.State>(State()) {

    /**
     * [query] is the live text in the field, [submittedQuery] the text a search actually ran with.
     * They are separate because typing must not touch the results already on screen, and because
     * the "more from this source" link has to carry the query that produced the row rather than
     * whatever has since been typed over it.
     */
    /**
     * In manual search for a migration, [migratingFrom] is the entry being migrated, read once, and
     * [migrateTarget] the result picked with no migration list below, which MA's dialog asks about.
     */
    /**
     * [pinnedOnly] and [onlyShowHasResults] are the two chips, as stored; a migration, which has no
     * Pinned chip, keeps [pinnedOnly] false. [favoriteIds] are the live favourites, null until the
     * first read, when a row's own `favorite` stands in.
     */
    data class State(
        val query: String = "",
        val submittedQuery: String? = null,
        val searching: Boolean = false,
        val groups: List<SourceGroup> = emptyList(),
        val migratingFrom: Manga? = null,
        val migrateTarget: Manga? = null,
        val pinnedOnly: Boolean = false,
        val onlyShowHasResults: Boolean = false,
        val favoriteIds: Set<Long>? = null,
    ) {
        val hasResults: Boolean get() = groups.any { it.status is Status.Results }

        /** A finished search that no source answered. Never true before the first submit. */
        val noResults: Boolean get() = submittedQuery != null && !searching && !hasResults

        /** Upstream's Has results: a group still loading, failed or empty is hidden while it is on. */
        val visibleGroups: List<SourceGroup>
            get() = if (onlyShowHasResults) groups.filter { it.status is Status.Results } else groups

        /** A search over Pinned found no pinned source to ask. */
        val noPinnedSources: Boolean get() = pinnedOnly && submittedQuery != null && !searching && groups.isEmpty()

        fun inLibrary(manga: Manga): Boolean = favoriteIds?.contains(manga.id) ?: manga.favorite
    }

    /**
     * The source is held as an id and a name rather than as a live [CatalogueSource]: a state
     * object outlives the extension it came from, and a reference to one of its classes keeps that
     * extension's class loader alive after it is uninstalled.
     */
    data class SourceGroup(val sourceId: Long, val sourceName: String, val status: Status)

    /**
     * [Empty] is deliberately not [Results] with an empty list: "this source has nothing for you"
     * and "this source is broken" are different sentences, and a source that simply does not carry
     * the title is the common case.
     */
    sealed interface Status {
        data object Loading : Status

        data class Results(val mangas: List<Manga>) : Status

        data object Empty : Status

        data class Error(val error: Throwable) : Status
    }

    private var searchJob: Job? = null

    /**
     * Incremented per search. A source's reply can land after a newer search has already replaced
     * the groups, because cancellation only takes effect at the next suspension point, and writing
     * that reply into the new search's state would show a result from the old query under a row
     * that no longer means the same thing.
     */
    private var generation = 0L

    /** The saved order of the migration's target sources, for [ordered]. Empty outside a migration. */
    private var migrationOrder: List<Long> = emptyList()

    init {
        viewModelScope.launch {
            combine(
                sourcePreferences.globalSearchPinnedOnly.withCurrent(),
                sourcePreferences.globalSearchFilterState.withCurrent(),
            ) { pinnedOnly, hasResults -> pinnedOnly to hasResults }
                .collect { (pinnedOnly, hasResults) ->
                    mutableState.update {
                        it.copy(pinnedOnly = pinnedOnly && migratingMangaId == null, onlyShowHasResults = hasResults)
                    }
                }
        }
        mangaRepository.getFavoriteIdsAsFlow()
            .onEach { ids -> mutableState.update { it.copy(favoriteIds = ids) } }
            .flowOn(dispatchers.io)
            .launchIn(viewModelScope)
        if (migratingMangaId != null) {
            // Upstream's: the entry's title fills the field and is searched at once.
            viewModelScope.launch {
                val manga = withContext(dispatchers.io) { getManga.await(migratingMangaId) } ?: return@launch
                mutableState.update { it.copy(migratingFrom = manga) }
                search(manga.title)
            }
        } else if (!initialQuery.isNullOrBlank()) {
            mutableState.update { it.copy(query = initialQuery) }
            search(initialQuery)
        }
    }

    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    /**
     * Search runs on submit rather than on every keystroke, which matters more here than it does on
     * a single source: one keystroke would be one request per installed source, and every one of
     * them has its own rate limiter.
     */
    fun submitSearch() {
        val query = state.value.query
        if (query.isBlank()) return
        search(query)
    }

    /**
     * Re-runs the whole search rather than the one source whose row was tapped. When one source
     * fails here the usual cause is the connection every other source is sharing, so retrying all
     * of them is what the owner meant.
     */
    fun retry() {
        val query = state.value.submittedQuery ?: return
        search(query)
    }

    /**
     * The Pinned / All chip. A search already shown runs again over the new set, keeping what the
     * sources it shares with the old one answered, as upstream's does: going to Pinned asks nothing,
     * going to All asks only the sources it adds.
     */
    fun setPinnedOnly(pinnedOnly: Boolean) {
        if (migratingMangaId != null || pinnedOnly == sourcePreferences.globalSearchPinnedOnly.get()) return
        sourcePreferences.globalSearchPinnedOnly.set(pinnedOnly)
        mutableState.update { it.copy(pinnedOnly = pinnedOnly) }
        state.value.submittedQuery?.let { search(it, reuse = true) }
    }

    /** The Has results chip, which only hides groups, so nothing is asked again. */
    fun toggleHasResults() {
        val onlyShowHasResults = !sourcePreferences.globalSearchFilterState.get()
        sourcePreferences.globalSearchFilterState.set(onlyShowHasResults)
        mutableState.update { it.copy(onlyShowHasResults = onlyShowHasResults) }
    }

    /** A result picked with no migration list below: MA's dialog asks what to migrate onto it. */
    fun showMigrateDialog(target: Manga) {
        mutableState.update { it.copy(migrateTarget = target) }
    }

    fun dismissMigrateDialog() {
        mutableState.update { it.copy(migrateTarget = null) }
    }

    /**
     * With [reuse], the same query over a new set of sources: a source that already answered with
     * results or with none keeps its answer, and the field keeps whatever has been typed since.
     */
    private fun search(query: String, reuse: Boolean = false) {
        searchJob?.cancel()
        val id = ++generation
        val answered = if (reuse) {
            state.value.groups.filter { it.status is Status.Results || it.status == Status.Empty }.associateBy { it.sourceId }
        } else {
            emptyMap()
        }
        mutableState.update {
            if (reuse) {
                it.copy(searching = true)
            } else {
                it.copy(query = query, submittedQuery = query, searching = true, groups = emptyList())
            }
        }
        searchJob = viewModelScope.launch {
            val sources = selectedSources()
            mutableState.update { current ->
                current.copy(
                    groups = ordered(sources.map { answered[it.id] ?: SourceGroup(it.id, it.name, Status.Loading) }),
                )
            }
            val gate = Semaphore(MAX_CONCURRENT_SOURCES)
            // supervisorScope, so a source whose failure escapes the catch below still cannot take
            // the other nineteen down with it.
            supervisorScope {
                sources.filter { it.id !in answered }.forEach { source -> launch { searchOne(source, query, gate, id) } }
            }
            mutableState.update { it.copy(searching = false) }
        }
    }

    private suspend fun searchOne(source: CatalogueSource, query: String, gate: Semaphore, id: Long) {
        try {
            val page = gate.withPermit {
                withContext(dispatchers.io) {
                    getSourceMangaPage.await(source.id, BrowseMode.Search(query, FilterList.EMPTY), FIRST_PAGE)
                }
            }
            val mangas = page.mangas.take(MAX_RESULTS_PER_SOURCE)
            updateStatus(id, source.id, if (mangas.isEmpty()) Status.Empty else Status.Results(mangas))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            updateStatus(id, source.id, Status.Error(e))
        }
    }

    private fun updateStatus(id: Long, sourceId: Long, status: Status) {
        if (id != generation) return
        mutableState.update { current ->
            current.copy(
                groups = ordered(current.groups.map { if (it.sourceId == sourceId) it.copy(status = status) else it }),
            )
        }
    }

    /**
     * The same set the Browse tab's sources list shows, read at submit time rather than collected:
     * a global search is a single shot, and a source that is hidden halfway through one should not
     * make its half-filled row vanish.
     *
     * Contract 08 section 5.3 is the rule being mirrored here: a language the owner enabled (with
     * `"all"` a literal code since ruling R57), an id that is not hidden, and no NSFW source while
     * that switch is off. [isInEnabledLanguage] and [matureSourceIds] are the Sources page's own
     * rules, so the two cannot drift apart.
     *
     * It waits for the source registry first. A search restored after process death runs as the
     * process starts, and until the extension host's first load has ended the registry holds the
     * Local source alone. The screen shows the search as running meanwhile; a failed load ends the
     * wait too, and the search runs over what did load.
     */
    private suspend fun selectedSources(): List<CatalogueSource> = withContext(dispatchers.io) {
        sourceManager.isInitialized.first { it }
        if (migratingMangaId != null) return@withContext migrationSources()
        val sources = sourceManager.getCatalogueSources().first()
        val languages = sourcePreferences.enabledLanguages.get()
        val hidden = sourcePreferences.disabledSources.get()
        val pinned = sourcePreferences.pinnedSources.get()
        val pinnedOnly = sourcePreferences.globalSearchPinnedOnly.get()
        val lastUsed = sourcePreferences.lastUsedSource.get()
        val nsfw = if (sourcePreferences.showNsfwSource.get()) {
            emptySet()
        } else {
            matureSourceIds(extensionManager.installedExtensions.value)
        }
        sources
            .filter { it.isInEnabledLanguage(languages) }
            .filterNot { it.id.toString() in hidden }
            .filterNot { it.id in nsfw }
            .filter { !pinnedOnly || it.id.toString() in pinned }
            .sortedWith(sourceOrder(pinned, lastUsed))
    }

    /**
     * Manual search for a migration searches the migration's target sources, in their saved order,
     * as upstream's `getEnabledSources` does; a source no longer loaded is left out.
     */
    private fun migrationSources(): List<CatalogueSource> {
        val ids = sourcePreferences.migrationSources.get()
        migrationOrder = ids
        return ids.mapNotNull { sourceManager.get(it) as? CatalogueSource }
    }

    /**
     * Upstream's `sortComparator` in manual search for a migration: a group holding results first, then
     * the saved order, applied again as each source answers. Outside a migration the groups keep the
     * order they were built in.
     */
    private fun ordered(groups: List<SourceGroup>): List<SourceGroup> {
        if (migratingMangaId == null) return groups
        return groups.sortedWith(
            compareBy<SourceGroup>({ it.status !is Status.Results }, { migrationOrder.indexOf(it.sourceId) }),
        )
    }

    /**
     * The sources list's own order: pinned first, then the last used one, then by language with
     * English first, then by name. Groups read in the order the owner already knows from Browse,
     * which is the whole point of not inventing a second ordering here.
     */
    private fun sourceOrder(pinned: Set<String>, lastUsed: Long): Comparator<CatalogueSource> =
        compareBy<CatalogueSource>(
            { if (it.id.toString() in pinned) 0 else 1 },
            { if (it.id == lastUsed) 0 else 1 },
            { if (it.lang == LANGUAGE_EN) 0 else 1 },
            { languageName(it.lang) },
            { it.name.lowercase() },
        )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String?, migratingMangaId: Long?): GlobalSearchViewModel
    }

    companion object {
        /**
         * How many sources may have a request in the air at once. A fan-out with no gate opens one
         * connection per installed source, which on a phone with two dozen of them is two dozen
         * simultaneous TLS handshakes over whatever the radio is managing.
         */
        const val MAX_CONCURRENT_SOURCES = 4

        /**
         * How many results are kept per source. The screen shows one scrolling row per source, and
         * a global search over twenty sources that kept whole pages would hold twenty pages of
         * covers in memory for a row that is never scrolled past its first handful.
         */
        const val MAX_RESULTS_PER_SOURCE = 10

        /** Global search asks each source for page one only; depth is what the source screen is for. */
        private const val FIRST_PAGE = 1

        private const val LANGUAGE_EN = "en"
    }
}
