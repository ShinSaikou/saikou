// Vendored from upstream v0.20.4: app/src/main/java/tachiyomi/feature/migration/list/MigrationListViewModel.kt (modified: yes) departures in the class comment, the latest a deadline on each source search
package com.shinsaikou.ui.migration.list

import androidx.annotation.FloatRange
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.migration.MigrateManga
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.ui.migration.list.MigratingManga.SearchResult
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.domain.migration.models.MigrationFlag
import tachiyomi.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Upstream's migration list (phase 4 unit MB): each entry searched on the selected sources in turn with
 * upstream's smart search, then Migrate all, Copy all, or a choice per entry, every write through
 * [MigrateManga].
 *
 * Saikou: Metro, the graph's dispatchers and Saikou's interactors for Injekt, `launchIO` and
 * `UpdateMangaFromRemote`, and these departures from upstream (design g7 final section 2):
 *
 * - M1: a list restored after a process death rebuilds each entry saved with a match from the
 *   database with no request, brings back "No alternatives found" for one saved with none, leaves
 *   out the entries saved as removed, and searches only the rest ([RestoredOutcomes]).
 * - M2: an entry no longer in the library (no row, `favorite` off or `deletedAt` set) is left out
 *   when the list is built, and is read again just before each write, which gets the row read then.
 * - M3: when a write loop ends, the entries it wrote leave the list, and the screen closes only when
 *   every attempted entry was written and none was held; a failed entry stays with its match.
 * - M4: no entry of the batch is ever a target: a search hit on one is discarded before any chapter
 *   request, and a hand-picked one is refused.
 * - M5: [State.favoriteIds] follows the library, so the screen marks a match already in it the
 *   moment a write puts it there. Display only.
 * - M6: in Migrate all and Copy all, an entry whose match another entry was written onto earlier in
 *   the same run is held, not written.
 * - M7: one write at a time, and a row being written by Migrate now is never written twice.
 * - M8: `migration_flags` is read once when the list starts and passed to every write.
 * - M9: [State.keepAwake] while any entry searches, a hand-picked target's chapters are fetched, or a
 *   write runs.
 * - M11: a match that had no cover is built from its row as read back after the details fetch.
 * - Each source's search for an entry has a deadline ([searchWithDeadline]), so a source that does
 *   not answer cannot hold every entry for OkHttp's two-minute call timeout.
 * - The downloaded chapters a Migrate left behind (unit migrate-downloads) are counted in one
 *   [Event.DownloadsLeftBehind] per write loop or Migrate now, whose snackbar offers Delete them; the
 *   list does not close until every such event is answered ([resolveLeftBehind]). The event says
 *   whether the run carried the downloads, so the snackbar says "could not be moved" or, with
 *   Downloaded chapters unticked, "stay on this device" (unit migrate-kept-downloads).
 *
 * Swallowed failures are logged at WARN with the entry and source ids and the exception's class,
 * never a name, a URL or a message. The close event is sent once at most.
 */
@AssistedInject
class MigrationListViewModel(
    @Assisted mangaIds: List<Long>,
    @Assisted extraSearchQuery: String?,
    @Assisted restored: RestoredOutcomes?,
    private val preferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val networkToLocalManga: NetworkToLocalManga,
    private val refreshMangaFromSource: RefreshMangaFromSource,
    private val migrateManga: MigrateManga,
    mangaRepository: MangaRepository,
    private val dispatchers: Dispatchers,
) : StateViewModel<MigrationListViewModel.State>(State()) {

    /** The route's ids: the batch, none of which may be a target (M4). */
    private val batchIds: Set<Long> = mangaIds.toSet()

    private val smartSearchEngine = SmartSourceSearchEngine(extraSearchQuery, dispatchers.default)

    val items
        inline get() = state.value.items

    private val hideUnmatched = preferences.migrationHideUnmatched.get()
    private val hideWithoutUpdates = preferences.migrationHideWithoutUpdates.get()

    /** M8: read once, from the saved run after a restore, and passed to every write. */
    private val runFlags: Set<MigrationFlag> =
        restored?.let { MigrationFlag.fromBit(it.flags) } ?: preferences.migrationFlags.get()

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** Once set, no close event is sent again: a list emptied during a run would otherwise send two. */
    private val closed = AtomicBoolean(false)

    /** M7: one write at a time, whether from the loop or from Migrate now. */
    private val writeMutex = Mutex()

    /**
     * The left-behind downloads sent in an event and not answered yet, and whether a close waits for
     * them; both under [leftBehindLock], since writes end on IO and answers come from the screen.
     */
    private val leftBehindLock = Any()
    private val unanswered = mutableSetOf<Long>()
    private var closeWhenAnswered = false

    private var migrateJob: Job? = null

    init {
        mutableState.update { it.copy(runFlags = MigrationFlag.toBit(runFlags)) }
        mangaRepository.getFavoriteIdsAsFlow()
            .onEach { ids -> mutableState.update { it.copy(favoriteIds = ids) } }
            .flowOn(dispatchers.io)
            .launchIn(viewModelScope)
        viewModelScope.launch(dispatchers.io) {
            val savedOutcomes = restored?.outcomes().orEmpty()
            val savedRemoved = restored?.removedIds?.toSet().orEmpty()
            val built = mangaIds
                .filter { it !in savedRemoved }
                .map { id ->
                    async {
                        val manga = getManga.await(id) ?: return@async null
                        // M2: a replaced entry keeps its row with favourite off, and another list
                        // or a pull may have taken one out since the ids were chosen.
                        if (!manga.isLiveFavorite()) return@async null
                        val chapterInfo = getChapterInfo(id)
                        MigratingManga(
                            manga = manga,
                            chapterCount = chapterInfo.chapterCount,
                            latestChapter = chapterInfo.latestChapter,
                            source = sourceLabel(manga.source),
                            parentContext = viewModelScope.coroutineContext,
                            dispatcher = dispatchers.default,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
            built.forEach { item -> restoreOutcome(item, savedOutcomes[item.manga.id]) }
            val dropped = mangaIds.toSet() - built.map { it.manga.id }.toSet()
            mutableState.update { it.copy(items = built, removedIds = it.removedIds + dropped) }
            updateMigrationProgress()
            runMigrations(built)
        }
    }

    /**
     * M1: a saved match is rebuilt from its row with no request, unless that row is gone or is one
     * of the batch (M4), when the entry is searched again; a saved 0 is "No alternatives found".
     */
    private suspend fun restoreOutcome(item: MigratingManga, targetId: Long?) {
        when (targetId) {
            null -> Unit
            NO_MATCH -> item.searchResult.value = SearchResult.NotFound
            else -> {
                if (targetId in batchIds) return
                val target = getManga.await(targetId) ?: return
                item.searchResult.value = target.toSuccessSearchResult()
            }
        }
    }

    private suspend fun getChapterInfo(id: Long) = getChaptersByMangaId.await(id).let { chapters ->
        ChapterInfo(
            latestChapter = chapters.maxOfOrNull { it.chapterNumber },
            chapterCount = chapters.size,
        )
    }

    private suspend fun Manga.toSuccessSearchResult(): SearchResult.Success {
        val chapterInfo = getChapterInfo(id)
        val source = sourceLabel(source)
        return SearchResult.Success(
            manga = this,
            chapterCount = chapterInfo.chapterCount,
            latestChapter = chapterInfo.latestChapter,
            source = source,
        )
    }

    /**
     * Upstream's `getNameForMangaInfo`: the name alone when exactly one language other than "all" and
     * "other" is enabled and it is the source's, else the name with its language.
     */
    private fun sourceLabel(sourceId: Long): String {
        val source = sourceManager.getOrStub(sourceId)
        val enabledLanguages = preferences.enabledLanguages.get()
            .filterNot { it in listOf("all", "other") }
        val hasOneActiveLanguages = enabledLanguages.size == 1
        val isInEnabledLanguages = source.lang in enabledLanguages
        return when {
            hasOneActiveLanguages && isInEnabledLanguages -> source.name
            source is StubSource -> source.toString()
            source.id == SourceIds.LOCAL || source.lang.isBlank() -> source.name
            else -> "${source.name} (${source.lang.uppercase()})"
        }
    }

    private suspend fun runMigrations(mangas: List<MigratingManga>) {
        val prioritizeByChapters = preferences.migrationPrioritizeByChapters.get()
        val deepSearchMode = preferences.migrationDeepSearchMode.get()

        // A restored list runs as the process starts, when the registry may hold Local alone.
        sourceManager.isInitialized.first { it }
        val sources = preferences.migrationSources.get()
            .mapNotNull { sourceManager.get(it) as? CatalogueSource }

        for (manga in mangas) {
            if (!currentCoroutineContext().isActive) break
            if (manga.manga.id !in state.value.mangaIds) continue
            if (manga.searchResult.value != SearchResult.Searching) continue
            if (!manga.migrationScope.isActive) continue
            // A hand-picked target is being fetched for this entry: that fetch decides it.
            if (manga.manga.id in state.value.picking) continue

            val result = try {
                manga.migrationScope.async {
                    if (prioritizeByChapters) {
                        val sourceSemaphore = Semaphore(MAX_SOURCES_IN_FLIGHT)
                        sources.map { source ->
                            async innerAsync@{
                                sourceSemaphore.withPermit {
                                    val result = searchSource(manga.manga, source, deepSearchMode)
                                    if (result == null || result.chapterInfo.chapterCount == 0) return@innerAsync null
                                    result
                                }
                            }
                        }
                            .mapNotNull { it.await() }
                            .maxByOrNull { it.chapterInfo.latestChapter ?: 0.0 }
                    } else {
                        sources.forEach { source ->
                            val result = searchSource(manga.manga, source, deepSearchMode)
                            if (result != null) return@async result
                        }
                        null
                    }
                }
                    .await()
            } catch (_: CancellationException) {
                continue
            }

            var match = result?.manga
            if (result != null && result.manga.thumbnailUrl == null) {
                try {
                    refreshMangaFromSource(result.source, result.manga, fetchDetails = true, manualFetch = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) {
                        "Migration: the details of entry ${manga.manga.id}'s match on source ${result.source.id} " +
                            "failed: ${e::class.simpleName}"
                    }
                }
                // M11: drawn from the row the fetch left, as a restored list draws it.
                match = getManga.await(result.manga.id) ?: result.manga
            }

            manga.searchResult.value = match?.toSuccessSearchResult() ?: SearchResult.NotFound

            if (result == null && hideUnmatched) {
                removeManga(manga)
            }
            if (result != null &&
                hideWithoutUpdates &&
                (result.chapterInfo.latestChapter ?: 0.0) <= (manga.latestChapter ?: 0.0)
            ) {
                removeManga(manga)
            }

            updateMigrationProgress()
        }
    }

    private suspend fun searchSource(
        manga: Manga,
        source: CatalogueSource,
        deepSearchMode: Boolean,
    ): Hit? {
        return try {
            val searchResult = searchWithDeadline(manga.id, source.id) {
                if (deepSearchMode) {
                    smartSearchEngine.deepSearch(source, manga.title)
                } else {
                    smartSearchEngine.regularSearch(source, manga.title)
                }
            }

            if (searchResult == null || (searchResult.url == manga.url && source.id == manga.source)) return null

            val localManga = networkToLocalManga(searchResult)
            // M4: another entry of the batch, or the entry itself, is never a target.
            if (localManga.id in batchIds) return null
            try {
                refreshMangaFromSource(source, localManga, fetchChapters = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) {
                    "Migration: the chapters of entry ${manga.id}'s match on source ${source.id} failed: " +
                        "${e::class.simpleName}"
                }
            }
            Hit(localManga, source, getChapterInfo(localManga.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) {
                "Migration: the search for entry ${manga.id} on source ${source.id} failed: ${e::class.simpleName}"
            }
            null
        }
    }

    /**
     * One source's search for one entry, or null once it runs past [SOURCE_SEARCH_DEADLINE], so a
     * source that does not answer costs each entry seconds rather than OkHttp's two-minute call
     * timeout. An extension that blocks inside its HTTP call ignores cancellation until that call
     * ends, and a `withTimeoutOrNull` around it would wait for it; so the search runs outside this
     * coroutine's job, only the wait for it times out, and the abandoned search is cancelled and
     * left to end on its own thread. A failure or a cancellation of the search itself is rethrown.
     */
    private suspend fun <T : Any> searchWithDeadline(mangaId: Long, sourceId: Long, search: suspend () -> T?): T? {
        val running = viewModelScope.async(dispatchers.default) { search() }
        try {
            if (withTimeoutOrNull(SOURCE_SEARCH_DEADLINE) { running.join() } == null) {
                logcat(LogPriority.WARN) {
                    "Migration: the search for entry $mangaId on source $sourceId ran past its deadline"
                }
                return null
            }
            return running.await()
        } finally {
            running.cancel()
        }
    }

    /** The derived fields, after any change to a result, the list, the writes in flight or the loop. */
    private fun publish() {
        mutableState.update { state ->
            val results = state.items.map { it.manga.id to it.searchResult.value }
            state.copy(
                finishedCount = results.count { (_, result) -> result != SearchResult.Searching },
                migrationComplete = results.all { (_, result) -> result != SearchResult.Searching } &&
                    results.any { (_, result) -> result is SearchResult.Success },
                keepAwake = results.any { (_, result) -> result == SearchResult.Searching } ||
                    state.inFlight.isNotEmpty() ||
                    state.migrating,
                outcomes = results.mapNotNull { (id, result) ->
                    when (result) {
                        SearchResult.Searching -> null
                        SearchResult.NotFound -> id to NO_MATCH
                        is SearchResult.Success -> id to result.manga.id
                    }
                }.toMap(),
            )
        }
    }

    private suspend fun updateMigrationProgress() {
        publish()
        if (items.isEmpty()) {
            closeOnce()
        }
    }

    /**
     * Upstream's pick of a target by hand (manual search). A target that is one of the batch is refused
     * (M4). The target's chapters are fetched in the entry's scope; a missing row, a source that is
     * not loaded or a failed fetch ends as "No alternatives found" with upstream's toast, and the
     * progress is updated either way (upstream skips it on failure).
     */
    fun useMangaForMigration(current: Long, target: Long) {
        if (target in batchIds) return
        val migratingManga = items.find { it.manga.id == current } ?: return
        migratingManga.searchResult.value = SearchResult.Searching
        mutableState.update { it.copy(picking = it.picking + current) }
        publish()
        viewModelScope.launch(dispatchers.io) {
            try {
                val result = migratingManga.migrationScope.async {
                    val manga = getManga.await(target) ?: return@async null
                    val source = sourceManager.get(manga.source) ?: return@async null
                    try {
                        refreshMangaFromSource(source, manga, fetchChapters = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN) {
                            "Migration: the chapters of the target picked for entry $current on source ${source.id} " +
                                "failed: ${e::class.simpleName}"
                        }
                        return@async null
                    }
                    getManga.await(target) ?: manga
                }
                    .await()

                if (result == null) {
                    migratingManga.searchResult.value = SearchResult.NotFound
                    sendEvent(Event.MatchWithoutChapters)
                } else {
                    migratingManga.searchResult.value = result.toSuccessSearchResult()
                }
            } finally {
                withContext(NonCancellable) {
                    mutableState.update { it.copy(picking = it.picking - current) }
                    updateMigrationProgress()
                }
            }
        }
    }

    fun migrateMangas() {
        migrateMangas(replace = true)
    }

    fun copyMangas() {
        migrateMangas(replace = false)
    }

    /**
     * Migrate all or Copy all. The entries are written one at a time in list order, each from its
     * row read just before (M2), with the run's flags (M8). An entry whose match was written onto
     * earlier in this run is held (M6); a failed one stays listed with its match. When the loop ends,
     * however it ends, the entries written and those no longer in the library leave the list in one
     * update, and the screen closes only if the list is empty or the loop finished with nothing
     * failed or held (M3) and no Migrate now is still writing; otherwise a snackbar counts each kind.
     */
    private fun migrateMangas(replace: Boolean) {
        if (state.value.migrating) return
        val inFlight = state.value.inFlight
        // M7: a row Migrate now is writing is not taken again.
        val snapshot = items.filter { it.manga.id !in inFlight }
        mutableState.update { it.copy(dialog = Dialog.Progress(0f), migrating = true) }
        publish()
        val job = viewModelScope.launch(dispatchers.io, start = CoroutineStart.LAZY) {
            val written = mutableSetOf<Long>()
            val leftBehind = mutableListOf<Long>()
            // M8: every write of the loop has the run's flags, so every result agrees on it.
            var carried = MigrationFlag.DOWNLOADS in runFlags
            val failed = mutableSetOf<Long>()
            val held = mutableSetOf<Long>()
            val dropped = mutableSetOf<Long>()
            val targetsThisRun = mutableSetOf<Long>()
            var finishedAll = false
            try {
                snapshot.forEachIndexed { index, item ->
                    ensureActive()
                    val target = (item.searchResult.value as? SearchResult.Success)?.manga
                    if (target != null) {
                        if (target.id in targetsThisRun) {
                            held += item.manga.id
                        } else {
                            val fresh = getManga.await(item.manga.id)
                            if (fresh == null || !fresh.isLiveFavorite()) {
                                dropped += item.manga.id
                            } else {
                                try {
                                    val result = writeMutex.withLock { migrateManga(fresh, target, replace, runFlags) }
                                    leftBehind += result.leftBehindDownloads
                                    carried = result.carried
                                    written += item.manga.id
                                    targetsThisRun += target.id
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    logcat(LogPriority.WARN) {
                                        "Migration: entry ${item.manga.id} onto ${target.id} failed: ${e::class.simpleName}"
                                    }
                                    // M2: a throw writes nothing, and a row that stopped being live
                                    // meanwhile (MigrateManga's own guard) leaves unwritten, uncounted.
                                    if (stillLive(item.manga.id)) failed += item.manga.id else dropped += item.manga.id
                                }
                            }
                        }
                    }
                    mutableState.update {
                        it.copy(dialog = Dialog.Progress((index.toFloat() / snapshot.size).coerceAtMost(1f)))
                    }
                }
                finishedAll = true
            } finally {
                withContext(NonCancellable) {
                    val gone = items
                        .map { it.manga.id }
                        .filter { it !in written && it !in dropped }
                        .filterNot { stillLive(it) }
                    val leaving = written + dropped + gone
                    items.filter { it.manga.id in leaving }.forEach { it.migrationScope.cancel() }
                    mutableState.update { state ->
                        state.copy(
                            items = state.items.filterNot { it.manga.id in leaving },
                            removedIds = state.removedIds + leaving,
                            dialog = null,
                            migrating = false,
                        )
                    }
                    migrateJob = null
                    logcat(LogPriority.INFO) {
                        "Migration: ${if (replace) "migrate" else "copy"} all wrote ${written.size}, failed " +
                            "${failed.size}, held ${held.size}, dropped ${dropped.size + gone.size}" +
                            if (finishedAll) "" else ", cancelled"
                    }
                    publish()
                    reportLeftBehind(leftBehind, carried)
                    // A Migrate now still writing would be cancelled with the screen: its own end
                    // closes the list if it empties it.
                    val writing = state.value.inFlight.isNotEmpty()
                    if (items.isEmpty() || (finishedAll && failed.isEmpty() && held.isEmpty() && !writing)) {
                        closeOnce()
                    } else {
                        if (failed.isNotEmpty()) sendEvent(Event.Failed(failed.size))
                        if (held.isNotEmpty()) sendEvent(Event.Held(held.size))
                    }
                }
            }
        }
        migrateJob = job
        job.start()
    }

    fun cancelMigrate() {
        migrateJob?.cancel()
    }

    /** A close while a left-behind event waits for its answer waits with it. */
    private suspend fun closeOnce() {
        val waiting = synchronized(leftBehindLock) {
            if (unanswered.isNotEmpty()) closeWhenAnswered = true
            unanswered.isNotEmpty()
        }
        if (!waiting && closed.compareAndSet(false, true)) sendEvent(Event.Close)
    }

    private suspend fun reportLeftBehind(chapterIds: List<Long>, carried: Boolean) {
        if (chapterIds.isEmpty()) return
        synchronized(leftBehindLock) { unanswered += chapterIds }
        sendEvent(Event.DownloadsLeftBehind(chapterIds, carried))
    }

    /**
     * The answer to one [Event.DownloadsLeftBehind]: Delete them deletes those chapters' files and
     * rows, and anything else keeps them, listed under Downloads not in library. A close that waited
     * for the answer is sent once nothing else waits. A delete that fails is logged.
     */
    fun resolveLeftBehind(chapterIds: List<Long>, delete: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            if (delete) {
                try {
                    migrateManga.deleteLeftBehindDownloads(chapterIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) {
                        "Migration: deleting ${chapterIds.size} downloads left behind failed: ${e::class.simpleName}"
                    }
                }
            }
            val close = synchronized(leftBehindLock) {
                unanswered -= chapterIds.toSet()
                (unanswered.isEmpty() && closeWhenAnswered).also { if (it) closeWhenAnswered = false }
            }
            if (close) closeOnce()
        }
    }

    /**
     * Sent from the main dispatcher: the loops run on IO, and a collector whose context does not
     * dispatch (the compose test's effect context does not) would otherwise navigate from an IO
     * thread, which Navigation refuses.
     */
    private suspend fun sendEvent(event: Event) {
        withContext(dispatchers.main) { eventChannel.send(event) }
    }

    /**
     * Migrate now or Copy now, the owner's choice for one row, never held (M6). Refused while that
     * row is being written or a loop runs (M7). The row is read again first (M2): an entry no longer
     * in the library leaves the list with nothing written, before the write or after one that
     * failed. A failed write on a row still in the library leaves it with its match and a snackbar
     * counting one.
     */
    fun migrateNow(mangaId: Long, replace: Boolean) {
        if (mangaId in state.value.inFlight || state.value.migrating) return
        val manga = items.find { it.manga.id == mangaId } ?: return
        val target = (manga.searchResult.value as? SearchResult.Success)?.manga ?: return
        mutableState.update { it.copy(inFlight = it.inFlight + mangaId) }
        publish()
        viewModelScope.launch(dispatchers.io) {
            try {
                val fresh = getManga.await(mangaId)
                if (fresh == null || !fresh.isLiveFavorite()) {
                    removeFromList(manga)
                    return@launch
                }
                val result = writeMutex.withLock { migrateManga(fresh, target, replace, runFlags) }
                reportLeftBehind(result.leftBehindDownloads, result.carried)
                removeFromList(manga)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) {
                    "Migration: entry $mangaId onto ${target.id} failed: ${e::class.simpleName}"
                }
                if (stillLive(mangaId)) sendEvent(Event.Failed(1)) else removeFromList(manga)
            } finally {
                withContext(NonCancellable) {
                    mutableState.update { it.copy(inFlight = it.inFlight - mangaId) }
                    publish()
                }
            }
        }
    }

    fun removeManga(mangaId: Long) {
        viewModelScope.launch(dispatchers.io) {
            val item = items.find { it.manga.id == mangaId } ?: return@launch
            removeFromList(item)
        }
    }

    private suspend fun removeFromList(item: MigratingManga) {
        removeManga(item)
        item.migrationScope.cancel()
        updateMigrationProgress()
    }

    private fun removeManga(item: MigratingManga) {
        mutableState.update {
            it.copy(
                items = it.items.toMutableList().apply { remove(item) },
                removedIds = it.removedIds + item.manga.id,
            )
        }
    }

    override fun onCleared() {
        items.forEach {
            it.migrationScope.cancel()
        }
    }

    fun showMigrateDialog(copy: Boolean) {
        if (!state.value.canMigrate) return
        mutableState.update { state ->
            state.copy(
                dialog = Dialog.Migrate(
                    copy = copy,
                    totalCount = items.size,
                    skippedCount = items.count { it.searchResult.value == SearchResult.NotFound },
                ),
            )
        }
    }

    /** Back while the progress dialog shows goes to it and is ignored, so no exit opens over a run. */
    fun showExitDialog() {
        if (state.value.dialog is Dialog.Progress) return
        mutableState.update {
            it.copy(dialog = Dialog.Exit)
        }
    }

    fun dismissDialog() {
        if (state.value.dialog is Dialog.Progress) return
        mutableState.update { it.copy(dialog = null) }
    }

    private fun Manga.isLiveFavorite() = favorite && deletedAt == null

    /** The entry's row read again: still in the library, not soft-deleted (M2). */
    private suspend fun stillLive(mangaId: Long) = getManga.await(mangaId)?.isLiveFavorite() == true

    data class ChapterInfo(
        val latestChapter: Double?,
        val chapterCount: Int,
    )

    /** One source's hit: the row it became, the source, and its chapters as read back. */
    private class Hit(val manga: Manga, val source: CatalogueSource, val chapterInfo: ChapterInfo)

    sealed interface Dialog {
        data class Migrate(val copy: Boolean, val totalCount: Int, val skippedCount: Int) : Dialog
        data class Progress(@FloatRange(0.0, 1.0) val progress: Float) : Dialog
        data object Exit : Dialog
    }

    sealed interface Event {
        /** The list is done: pop it. Sent once at most. */
        data object Close : Event

        /** Upstream's toast after a hand-picked target could not be used. */
        data object MatchWithoutChapters : Event

        /** Entries a write failed for, which stay listed. */
        data class Failed(val count: Int) : Event

        /** Entries held because another entry was just written onto their match (M6). */
        data class Held(val count: Int) : Event

        /**
         * The old entries' downloaded chapters a Migrate left on this device: not moved, or with
         * [carried] false not carried at all ([MigrateManga.Result.carried]). The screen answers
         * through [resolveLeftBehind] with these ids.
         */
        data class DownloadsLeftBehind(val chapterIds: List<Long>, val carried: Boolean) : Event
    }

    /**
     * [inFlight] are the rows Migrate now or Copy now is writing, [picking] the rows whose
     * hand-picked target is being fetched, and [migrating] is true while a write loop runs.
     * [outcomes], [removedIds] and [runFlags] are what the screen saves for a restore (M1).
     * [favoriteIds] are the live favourites, for the in-library mark (M5).
     */
    data class State(
        val items: List<MigratingManga> = listOf(),
        val finishedCount: Int = 0,
        val migrationComplete: Boolean = false,
        val dialog: Dialog? = null,
        val inFlight: Set<Long> = emptySet(),
        val picking: Set<Long> = emptySet(),
        val migrating: Boolean = false,
        val keepAwake: Boolean = false,
        val outcomes: Map<Long, Long> = emptyMap(),
        val removedIds: Set<Long> = emptySet(),
        val runFlags: Int = 0,
        val favoriteIds: Set<Long> = emptySet(),
    ) {
        val mangaIds: List<Long> = items.map { it.manga.id }

        /** Migrate and Copy: every entry resolved, one with a match, and no write under way (M7). */
        val canMigrate: Boolean
            get() = migrationComplete && inFlight.isEmpty() && !migrating

        fun toRestoredOutcomes(): RestoredOutcomes = RestoredOutcomes.of(outcomes, removedIds, runFlags)
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaIds: List<Long>, extraSearchQuery: String?, restored: RestoredOutcomes?): MigrationListViewModel
    }

    private companion object {
        /** Upstream's cap on the sources searched at once in "Match based on chapter number". */
        const val MAX_SOURCES_IN_FLIGHT = 5

        /** A saved outcome of "No alternatives found". */
        const val NO_MATCH = 0L

        /** How long one source may take to answer one entry's search, every deep query included. */
        val SOURCE_SEARCH_DEADLINE = 25.seconds
    }
}
