package com.shinsaikou.libraryupdate

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.domain.source.toChapterInfo
import com.shinsaikou.domain.source.toMangaInfo
import com.shinsaikou.libraryupdate.AdaptiveSourceLimit.Request
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.UpdateStrategy
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The library update pass: a flat queue of single entries, run concurrently (phase 2 unit E).
 *
 * Each site has its own number of requests in flight, found by [AdaptiveSourceLimit] from the
 * site's completion rate and never more than [AdaptiveSourceLimit.PER_SITE_CAP], and the whole run
 * never has more than [GLOBAL_CAP] in flight. Different sites run side by side, which is where the
 * pass's lead over upstream's five sources at a time comes from; the bounds are on what one website
 * sees. The extension's own rate limiter, when it has one, still applies: the queue never reads it,
 * it only notices when asking more stops making the site answer faster.
 *
 * A site is the host of a source's base URL, so the language variants of one website, each its own
 * source with its own id, share one budget instead of each taking the cap. The queue used to key
 * by source id alone, which let ten variants of one site have ten times the cap in flight against
 * one server. A source that names no host (the Local source, a stub, an extension whose base URL
 * throws or does not parse) is a site of its own, keyed by id.
 *
 * A site that pushes back ([AdaptiveSourceLimit.isBackoff]) is asked less, waits out the
 * `Retry-After` it named, and after [AdaptiveSourceLimit.MAX_BACKOFFS] backoffs is not asked again
 * this run: its remaining entries fail with [SiteStoppedException], which the error list names.
 *
 * The pass owns no notification and no worker state. It reports through `progress` and returns an
 * [Outcome]; the worker turns that into notifications and a persisted error list.
 *
 * Each entry's new chapters go to [AutoDownload] as upstream's `LibraryUpdateJob` hands them to
 * `FilterChaptersForDownload`: queued without starting the queue, so no source is downloading
 * from while the update still asks it for chapter lists, and the queue is started once when a run
 * that was not cancelled queued something (phase 3 unit AD).
 *
 * Each entry is asked with its stored chapters, which the extension ABI takes as the entry's
 * existing chapters and upstream's `UpdateMangaFromRemote` reads from the database the same way.
 *
 * An entry whose details were never fetched ([Manga.initialized] false: the target of a migration,
 * an entry another device synced here with none of its details, or one added while its first
 * refresh failed) skips no rule and has its details fetched with its chapters, on every run,
 * as upstream v0.21.0 does. Two choices are Saikou's own and differ from upstream, which applies both to
 * every run: the restrictions apply to automatic runs only (a manual run applies only the
 * fetch-once rule), and [LibraryPreferences.autoUpdateMetadata] asks for details on automatic runs
 * only (a manual run asks through `refreshMetadata`).
 *
 * Each member of a merged entry is an entry here, one unit of work against one site, except one with
 * Check for updates off, which the run leaves out as if it were not in the library. A member whose
 * refresh inserted chapters has its group's read marks reconciled, since a new copy can be of a number
 * already read.
 */
@Inject
class FlatLibraryUpdate(
    private val getLibraryManga: GetLibraryManga,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val sourceManager: SourceManager,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val updateManga: UpdateManga,
    private val fetchInterval: FetchInterval,
    private val libraryPreferences: LibraryPreferences,
    private val autoDownload: AutoDownload,
    private val getMergedGroups: GetMergedGroups,
    private val reconcileMergedRead: ReconcileMergedRead,
    private val dispatchers: Dispatchers,
    private val timeSource: TimeSource,
) {

    /**
     * [current] is the entry about to be refreshed, and [done] the count already finished. With
     * several entries in flight, [done] can stay put across several reports.
     */
    data class Progress(val done: Int, val total: Int, val current: Manga?)

    /** [error] is the source's own throwable, never a wrapper, because the error store keys on it. */
    data class Failure(val manga: Manga, val error: Throwable)

    /**
     * Both collections are in queue order, whatever order the entries finished in. [checked] is
     * every entry the run reached the end of, failed or not: an entry it never started (another
     * category's, a skipped one, the rest of a cancelled run) is not in it, so the error list keeps
     * that entry's row from an earlier run. [skipped] counts the entries the rules kept out of the
     * queue, by reason ([skipReason]'s keys); null when the run ended before it built a queue.
     */
    data class Outcome(
        val updated: Map<Long, List<Chapter>>,
        val failures: List<Failure>,
        val cancelled: Boolean,
        val checked: Set<Long> = emptySet(),
        val skipped: Map<String, Int>? = null,
    )

    /**
     * How one entry is refreshed; the same for every entry of a run. [fetchDetails] is the run's
     * choice; an entry whose details were never fetched is asked for them whatever it says.
     */
    private class Plan(
        val automatic: Boolean,
        val fetchDetails: Boolean,
        val updateTitles: Boolean,
        val window: Pair<Long, Long>,
        /** The groups this run holds, by each member's id; empty while Merged entries is off. */
        val groups: Map<Long, MergedGroup>,
    )

    /**
     * What the run has gathered so far. Entries finish on whichever thread their coroutine resumes
     * on, so both maps are concurrent, and both are keyed by queue position so the outcome keeps the
     * queue's order.
     */
    private class Results {
        val updated = ConcurrentSkipListMap<Int, Pair<Long, List<Chapter>>>()
        val failures = ConcurrentSkipListMap<Int, Failure>()
        val checked: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        /** Whether any entry's new chapters were queued, which is when the queue is started at the end. */
        val queuedDownloads = AtomicBoolean(false)

        fun outcome(cancelled: Boolean, skipped: Map<String, Int>) = Outcome(
            updated = updated.values.associate { it },
            failures = failures.values.toList(),
            cancelled = cancelled,
            checked = checked.toSet() + failures.values.map { it.manga.id },
            skipped = skipped,
        )
    }

    /**
     * One website as the queue budgets it: the host of a source's base URL, or the source alone
     * when it names none.
     */
    private data class Site(val host: String?, val sourceId: Long?) {
        override fun toString(): String = host ?: "source $sourceId"
    }

    /** What one entry's turn did to its site, and the wait a refusal's `Retry-After` named. */
    private data class Turn(val request: Request, val retryAfter: Duration? = null)

    /** One entry's report to the coordinator; [generation] is what the site's limit gave it at the start. */
    private data class Ended(val site: Site, val turn: Turn, val startedAt: TimeMark, val generation: Int)

    /** `newUpdatesCount` is a read then a write, so two entries adding at once would lose one. */
    private val newUpdatesLock = Any()

    /**
     * Refreshes chapters only, unless an automatic run has [LibraryPreferences.autoUpdateMetadata]
     * on or the entry's details were never fetched.
     */
    suspend fun run(categoryId: Long?, automatic: Boolean, progress: (Progress) -> Unit): Outcome =
        run(categoryId, automatic, refreshMetadata = false, progress = progress)

    /**
     * The pass first waits for the source registry. A run the system starts can start the process
     * too, and until every provider has loaded, an installed extension's entries would all be
     * recorded as a source that is not installed. The wait has no timeout: the extension host sets
     * its flag after its first load whether or not that load succeeded.
     *
     * [mangaIds], when given, is the whole queue's choice: the library entries among them, whatever
     * their categories (a retry of failed entries, or the library selection's Update).
     *
     * `progress` is called from one coroutine at a time, once per entry as it starts.
     */
    suspend fun run(
        categoryId: Long?,
        automatic: Boolean,
        refreshMetadata: Boolean,
        mangaIds: Set<Long>? = null,
        progress: (Progress) -> Unit,
    ): Outcome {
        try {
            sourceManager.isInitialized.first { it }
        } catch (_: CancellationException) {
            // Cancelled before anything was queued: nothing fetched, nothing failed.
            return Outcome(updated = emptyMap(), failures = emptyList(), cancelled = true)
        }

        val zone = TimeZone.currentSystemDefault()
        val window = fetchInterval.getWindow(Clock.System.now().toLocalDateTime(zone).date, zone)
        val groups = getMergedGroups.await()
        val library = checked(getLibraryManga.await(), groups)
        val (queue, skipped) = queueOf(library, categoryId, automatic, window, mangaIds)
        val plan = Plan(
            automatic = automatic,
            fetchDetails = refreshMetadata || (automatic && libraryPreferences.autoUpdateMetadata.get()),
            updateTitles = libraryPreferences.updateMangaTitles.get(),
            window = window,
            groups = groups.flatMap { group -> group.members.map { it.mangaId to group } }.toMap(),
        )

        val results = Results()
        // Source calls run in a scope of their own so a cancel never waits on the network: each
        // entry's coroutine returns on cancellation and its orphaned call is dropped. The scope's
        // dispatcher is sized to the global cap, for sources that block the thread they are called on.
        val fetchScope = CoroutineScope(SupervisorJob() + dispatchers.io.limitedParallelism(GLOBAL_CAP))
        var cancelled = false
        try {
            drain(queue, plan, fetchScope, results, progress)
        } catch (_: CancellationException) {
            // The worker treats a cancelled run as a success, so the partial result is the result.
            cancelled = true
        } finally {
            fetchScope.cancel()
        }
        if (!cancelled && results.queuedDownloads.get()) startDownloads()
        return results.outcome(cancelled, skipped)
    }

    /**
     * Starts entries while the caps allow and one of them ends, until the queue is empty. Only this
     * coroutine touches the limits and the counts; the entries report back through a channel.
     *
     * Sites take turns: a site that starts an entry goes to the back of the rotation, so while the
     * global cap is what binds, a free slot goes to the site that waited longest rather than to the
     * first in the queue. A site's own entries keep the queue's order.
     *
     * A site under a `Retry-After` starts nothing until it ends. When every site with entries left
     * is held and nothing is in flight, the coordinator sleeps until the first hold ends; with
     * entries in flight it wakes at whichever comes first.
     */
    private suspend fun drain(
        queue: List<LibraryManga>,
        plan: Plan,
        fetchScope: CoroutineScope,
        results: Results,
        progress: (Progress) -> Unit,
    ) = coroutineScope {
        val sites = HashMap<Long, Site>()
        val pending = LinkedHashMap<Site, ArrayDeque<IndexedValue<LibraryManga>>>()
        queue.withIndex().forEach {
            val site = sites.getOrPut(it.value.manga.source) { siteOf(it.value.manga.source) }
            pending.getOrPut(site) { ArrayDeque() }.addLast(it)
        }
        val limits = pending.keys.associateWith { AdaptiveSourceLimit(timeSource) }
        val rotation = ArrayDeque(pending.keys)
        val ended = Channel<Ended>(Channel.UNLIMITED)
        var inFlight = 0
        var done = 0

        while (true) {
            var passed = 0
            while (inFlight < GLOBAL_CAP && passed < rotation.size) {
                val site = rotation.removeFirst()
                val limit = limits.getValue(site)
                if (!limit.hasRoom) {
                    rotation.addLast(site)
                    passed++
                    continue
                }
                val entries = pending.getValue(site)
                val (index, item) = entries.removeFirst()
                if (entries.isNotEmpty()) rotation.addLast(site)
                passed = 0

                val generation = limit.onStart()
                val startedAt = timeSource.markNow()
                inFlight++
                progress(Progress(done, queue.size, item.manga))
                launch {
                    var turn = Turn(Request.NONE)
                    try {
                        turn = refresh(index, item, plan, fetchScope, results)
                        // Only an entry whose turn ended counts; a cancel throws past this line.
                        results.checked += item.id
                    } finally {
                        // Whatever ends the entry, the coordinator hears of it, or it would wait forever.
                        ended.trySend(Ended(site, turn, startedAt, generation))
                    }
                }
            }
            if (inFlight == 0 && rotation.isEmpty()) break

            // Every site left either has its count in flight or is held; a held one wakes the loop.
            val firstHoldEnds = rotation.mapNotNull { limits.getValue(it).holds() }.minOrNull()
            check(inFlight > 0 || firstHoldEnds != null) { "Library update: entries left, none in flight, none held" }
            val event = if (firstHoldEnds == null) {
                ended.receive()
            } else {
                select<Ended?> {
                    ended.onReceive { it }
                    onTimeout(firstHoldEnds) { null }
                }
            }
            val (site, turn, startedAt, generation) = event ?: continue
            inFlight--
            done++
            val limit = limits.getValue(site)
            val before = limit.limit
            val seconds = startedAt.elapsedNow().toDouble(DurationUnit.SECONDS)
            limit.onEnd(turn.request, seconds, generation, turn.retryAfter)
            if (limit.limit != before) {
                logcat(LogPriority.DEBUG) {
                    val rate = "%.2f".format(Locale.ROOT, limit.lastRate)
                    "Library update: site $site in flight $before -> ${limit.limit} " +
                        "(${turn.request.name.lowercase()}, window rate $rate/s)"
                }
            }
            if (turn.retryAfter != null) {
                logcat(LogPriority.DEBUG) {
                    "Library update: site $site asked for ${turn.retryAfter}, held ${limit.holds()}"
                }
            }
            if (limit.stopped) done += stop(site, pending, rotation, results)
        }
    }

    /**
     * Records every entry [site] has not started as a [SiteStoppedException] failure and takes the
     * site out of the rotation; returns how many. Entries already in flight finish as they will.
     */
    private fun stop(
        site: Site,
        pending: MutableMap<Site, ArrayDeque<IndexedValue<LibraryManga>>>,
        rotation: ArrayDeque<Site>,
        results: Results,
    ): Int {
        val skipped = pending.remove(site).orEmpty()
        if (skipped.isEmpty()) return 0
        rotation.remove(site)
        val error = SiteStoppedException(site.host)
        skipped.forEach { (index, item) -> results.failures[index] = Failure(item.manga, error) }
        logcat(LogPriority.WARN) {
            "Library update: site $site pushed back ${AdaptiveSourceLimit.MAX_BACKOFFS} times; " +
                "${skipped.size} entries not asked this run"
        }
        return skipped.size
    }

    /**
     * The site [sourceId]'s requests go to: the host of its base URL, or the source alone when it
     * names none. Reading the base URL runs extension code, which may throw.
     */
    private fun siteOf(sourceId: Long): Site {
        val baseUrl = runCatching { sourceManager.getOrStub(sourceId).baseUrl }.getOrNull()
        val host = baseUrl?.toHttpUrlOrNull()?.host
        return if (host != null) Site(host, null) else Site(null, sourceId)
    }

    /** Refreshes one entry and says what it asked of its site. */
    private suspend fun refresh(
        index: Int,
        item: LibraryManga,
        plan: Plan,
        fetchScope: CoroutineScope,
        results: Results,
    ): Turn {
        currentCoroutineContext().ensureActive()
        val fresh = getManga.await(item.id)
        if (fresh == null || !fresh.favorite || fresh.deletedAt != null) {
            // Removed from the library between building the queue and reaching it.
            return Turn(Request.NONE)
        }

        val source = sourceManager.getOrStub(fresh.source)
        if (source is StubSource) {
            results.failures[index] = Failure(fresh, SourceNotInstalledException(fresh.source))
            return Turn(Request.NONE)
        }
        val stored = getChaptersByMangaId.await(fresh.id)
            .sortedBy { it.sourceOrder }
            .map { it.toChapterInfo() }

        val fetched = try {
            fetch(source, fresh, stored, plan, fetchScope)
        } catch (e: TimeoutCancellationException) {
            // The watchdog. Any other cancellation is the run's and goes on up: a slow entry is a
            // failure, not a cancelled run.
            results.failures[index] = Failure(fresh, e)
            return Turn(Request.COMPLETED)
        }
        val update = fetched.getOrElse { error ->
            results.failures[index] = Failure(fresh, error)
            return if (AdaptiveSourceLimit.isBackoff(error)) {
                Turn(Request.BACKOFF, AdaptiveSourceLimit.retryAfter(error))
            } else {
                Turn(Request.COMPLETED)
            }
        }

        val newChapters = try {
            update.manga?.let {
                updateManga.awaitUpdateFromSource(
                    localManga = fresh,
                    remote = it,
                    manualFetch = !plan.automatic,
                    updateTitles = plan.updateTitles,
                )
            }
            syncChaptersWithSource.await(
                rawSourceChapters = update.chapters.orEmpty(),
                manga = fresh,
                manualFetch = !plan.automatic,
                fetchWindow = plan.window,
            ).also { newChapters ->
                if (newChapters.isNotEmpty()) {
                    results.updated[index] = fresh.id to newChapters
                    val rows = newRows(fresh, newChapters, plan)
                    synchronized(newUpdatesLock) {
                        libraryPreferences.newUpdatesCount.getAndSet { it + rows }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            results.failures[index] = Failure(fresh, e)
            return Turn(Request.COMPLETED)
        }
        if (newChapters.isNotEmpty()) {
            reconcileMerged(fresh)
            queueNewChapters(fresh, newChapters, results)
        }
        return Turn(Request.COMPLETED)
    }

    /**
     * What [newChapters] add to the Updates badge: the rows they add to the list. A merged title has
     * one row per number, so a number another member already holds adds none, unless the title
     * shows every copy. The chapters are in the database, so a failed read counts every new chapter
     * and is logged: the entry's update did not fail.
     */
    private suspend fun newRows(manga: Manga, newChapters: List<Chapter>, plan: Plan): Int {
        val group = plan.groups[manga.id]?.takeUnless { it.showAll } ?: return newChapters.size
        val held = try {
            group.members.filter { it.mangaId != manga.id }
                .flatMap { getChaptersByMangaId.await(it.mangaId) }
                .mapTo(HashSet()) { it.chapterNumber }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Library update: could not count the new rows of ${manga.id}" }
            return newChapters.size
        }
        return newChapters.count { !it.isRecognizedNumber || it.chapterNumber !in held }
    }

    /** The chapters are in the database, so a failure here is logged: the entry's update did not fail. */
    private suspend fun reconcileMerged(manga: Manga) {
        try {
            reconcileMergedRead.await(manga.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Library update: could not reconcile the read marks of ${manga.id}" }
        }
    }

    /**
     * Upstream's `filterChaptersForDownload` then `downloadChapters(manga, chapters, false)`. The
     * chapters are in the database already, so a failure here is logged rather than recorded as the
     * entry's: its update did not fail, and the error list names update failures only.
     */
    private suspend fun queueNewChapters(manga: Manga, newChapters: List<Chapter>, results: Results) {
        try {
            if (autoDownload.queueNewChapters(manga, newChapters, autoStart = false) > 0) {
                results.queuedDownloads.set(true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Library update: could not queue the new chapters of ${manga.id}" }
        }
    }

    /** Upstream's `downloadManager.startDownloads()` after a run whose new chapters were queued. */
    private suspend fun startDownloads() {
        try {
            autoDownload.startQueue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Library update: could not start the download queue" }
        }
    }

    /**
     * The source call, on the fetch scope. A failure comes back as a value rather than thrown
     * through `await`, because a coroutine that resumes with an exception may rethrow a copy that
     * carries the original as its cause (kotlinx.coroutines' stack trace recovery, on whenever
     * assertions are), and the error store looks only one cause deep. Only the watchdog's timeout
     * and the run's cancellation are thrown.
     */
    private suspend fun fetch(
        source: Source,
        manga: Manga,
        stored: List<ChapterInfo>,
        plan: Plan,
        fetchScope: CoroutineScope,
    ): Result<MangaUpdate> {
        val fetchDetails = plan.fetchDetails || !manga.initialized
        val deferred = fetchScope.async {
            withTimeout(PER_MANGA_TIMEOUT_MS) {
                try {
                    Result.success(source.getMangaUpdate(manga.toMangaInfo(), stored, fetchDetails, fetchChapters = true))
                } catch (e: CancellationException) {
                    // The watchdog's and the run's own are rethrown. One the source raised while this
                    // call was still wanted is the source's failure, like any other it throws.
                    if (isActive) Result.failure(e) else throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel()
            throw e
        }
    }

    /**
     * Entries to refresh, deduplicated, filtered by category (or to [mangaIds] when given) and by
     * the restrictions, and ordered by source and then title. The queue takes each source's entries
     * in this order.
     */
    suspend fun buildQueue(
        categoryId: Long?,
        automatic: Boolean,
        window: Pair<Long, Long>,
        mangaIds: Set<Long>? = null,
    ): List<LibraryManga> = buildQueue(getLibraryManga.await(), categoryId, automatic, window, mangaIds)

    /** [library] less the merged members with Check for updates off. */
    private fun checked(library: List<LibraryManga>, groups: List<MergedGroup>): List<LibraryManga> {
        val resting = groups
            .flatMap { group -> group.members.filterNot { it.checkUpdates } }
            .mapTo(HashSet()) { it.mangaId }
        return if (resting.isEmpty()) library else library.filterNot { it.id in resting }
    }

    /**
     * The queue built from [library], the whole of `GetLibraryManga.await()` as a caller already
     * holds it, so a caller that has read the library does not read it again (the statistics
     * screen's update tile, phase 5 unit SC5).
     */
    suspend fun buildQueue(
        library: List<LibraryManga>,
        categoryId: Long?,
        automatic: Boolean,
        window: Pair<Long, Long>,
        mangaIds: Set<Long>? = null,
    ): List<LibraryManga> =
        queueOf(checked(library, getMergedGroups.await()), categoryId, automatic, window, mangaIds).first

    /** [buildQueue]'s queue, with how many entries each rule kept out of it. */
    private fun queueOf(
        library: List<LibraryManga>,
        categoryId: Long?,
        automatic: Boolean,
        window: Pair<Long, Long>,
        mangaIds: Set<Long>?,
    ): Pair<List<LibraryManga>, Map<String, Int>> {
        val included = libraryPreferences.updateCategories.get().mapNotNull(String::toLongOrNull).toSet()
        val excluded = libraryPreferences.updateCategoriesExclude.get().mapNotNull(String::toLongOrNull).toSet()
        val restrictions = if (automatic) libraryPreferences.autoUpdateMangaRestrictions.get() else emptySet()
        val skipped = mutableMapOf<String, Int>()

        val queue = library
            .filter { item ->
                when {
                    mangaIds != null -> item.id in mangaIds
                    categoryId != null -> categoryId in item.categories
                    item.categories.any { it in excluded } -> false
                    included.isEmpty() -> true
                    else -> item.categories.any { it in included }
                }
            }
            .distinctBy { it.id }
            .filter { item ->
                val reason = skipReason(item, restrictions, window)
                if (reason != null) skipped[reason] = skipped.getOrDefault(reason, 0) + 1
                reason == null
            }
            .sortedWith(
                compareBy<LibraryManga> { it.manga.source }
                    .thenBy(Comparator<String> { a, b -> a.compareToWithCollator(b) }) { it.manga.displayTitle },
            )
        if (skipped.isNotEmpty()) {
            logcat(LogPriority.DEBUG) { "Library update skipped ${skipped.entries.joinToString()}" }
        }
        return queue to skipped
    }

    /**
     * Why [item] is not refreshed, or null when it is. Only the fetch-once rule applies to a
     * manual run; [restrictions] is empty there. An entry whose details were never fetched is
     * always refreshed: the status and update strategy the rules read come with those details, and
     * a rule that skipped it would also keep it from ever getting them. The entry screen's
     * `updateCadenceOf` reads the same rules in the same order, to say why an entry waits.
     */
    private fun skipReason(
        item: LibraryManga,
        restrictions: Set<String>,
        window: Pair<Long, Long>,
    ): String? {
        val manga = item.manga
        return when {
            !manga.initialized -> null
            manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE && item.totalChapters > 0 -> FETCH_ONCE
            LibraryUpdateRestrictions.MANGA_NON_COMPLETED in restrictions &&
                manga.status == MangaStatus.COMPLETED.code.toLong() -> LibraryUpdateRestrictions.MANGA_NON_COMPLETED
            LibraryUpdateRestrictions.MANGA_HAS_UNREAD in restrictions &&
                item.unreadCount != 0L -> LibraryUpdateRestrictions.MANGA_HAS_UNREAD
            LibraryUpdateRestrictions.MANGA_NON_READ in restrictions &&
                item.totalChapters > 0L && !item.hasStarted -> LibraryUpdateRestrictions.MANGA_NON_READ
            LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD in restrictions &&
                manga.nextUpdate > window.second -> LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD
            else -> null
        }
    }

    companion object {
        /** The skip reason of an entry only fetched once that has its chapters already. */
        const val FETCH_ONCE = "fetch_once"

        const val PER_MANGA_TIMEOUT_MS = 90_000L

        /**
         * Requests in flight across all sources. `PLAN.md` sets min(2 × cores, 12) and decision Q3
         * a floor of 32, because twenty sources at their limits need more than twenty in flight
         * (section 4 Q1's arithmetic). The formula never exceeds 12, so the floor is the cap on every
         * device.
         */
        const val GLOBAL_CAP = 32
    }
}
