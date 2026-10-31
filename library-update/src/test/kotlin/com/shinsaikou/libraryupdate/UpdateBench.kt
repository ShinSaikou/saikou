package com.shinsaikou.libraryupdate

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestCoroutineScheduler
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A library of [entriesPerSource] entries on each of [sources], with the domain mocked the way
 * `FlatLibraryUpdateTest` mocks it, and the pass built from it as the graph builds it.
 *
 * Entry `n` of source `s` has id `s * 10_000 + n`, and its url carries the id, so a fake source can
 * tell its entries apart. With [newChapters], every entry's sync reports one new chapter.
 */
internal class UpdateBench(
    val sources: List<Source>,
    entriesPerSource: Int,
    newChapters: Boolean = false,
) {
    val preferences = LibraryPreferences(InMemoryPreferenceStore())

    val library: List<LibraryManga> = sources.flatMap { source ->
        (1..entriesPerSource).map { n -> entry(source.id * ENTRY_ID_STRIDE + n, source.id) }
    }

    private val byId = library.associateBy { it.id }
    private val byIdSource = sources.associateBy { it.id }

    private val getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns library }
    private val getManga = mockk<GetManga> {
        coEvery { await(any<Long>()) } answers { byId[firstArg<Long>()]?.manga }
    }
    private val getChaptersByMangaId = mockk<GetChaptersByMangaId> {
        coEvery { await(any(), any()) } returns emptyList()
    }
    private val sourceManager = mockk<SourceManager> {
        every { isInitialized } returns MutableStateFlow(true)
        every { getOrStub(any()) } answers { byIdSource[firstArg<Long>()] ?: StubSource.unknown(firstArg()) }
    }
    private val syncChapters = mockk<SyncChaptersWithSource> {
        coEvery { await(any(), any(), any(), any()) } answers {
            val manga = secondArg<Manga>()
            if (newChapters) listOf(Chapter.create().copy(id = manga.id, mangaId = manga.id)) else emptyList()
        }
    }
    private val fetchInterval = mockk<FetchInterval> {
        every { getWindow(any(), any()) } returns (0L to 1_000L)
    }

    fun pass(io: CoroutineDispatcher, time: TimeSource) = FlatLibraryUpdate(
        getLibraryManga = getLibraryManga,
        getManga = getManga,
        getChaptersByMangaId = getChaptersByMangaId,
        sourceManager = sourceManager,
        syncChaptersWithSource = syncChapters,
        updateManga = mockk(relaxed = true),
        fetchInterval = fetchInterval,
        libraryPreferences = preferences,
        autoDownload = mockk(relaxed = true),
        dispatchers = Dispatchers(io = io, default = io, main = io),
        timeSource = time,
    )

    companion object {
        const val ENTRY_ID_STRIDE = 10_000L

        fun entry(id: Long, sourceId: Long) = LibraryManga(
            manga = Manga.create().copy(
                id = id,
                source = sourceId,
                url = "/manga/$id",
                title = "Fixture $id",
                favorite = true,
                initialized = true,
            ),
            categories = listOf(1L),
            totalChapters = 0L,
            readCount = 0L,
            bookmarkCount = 0L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 0L,
        )
    }
}

/** Requests in flight across every source of a run, for the global cap. */
internal class Load {
    var inFlight = 0
        private set
    var max = 0
        private set

    fun enter() {
        inFlight++
        max = maxOf(max, inFlight)
    }

    fun leave() {
        inFlight--
    }
}

/**
 * A source on virtual time: each request waits for [latency], and when [permits] is set, at most
 * that many requests are admitted in any [period]. A request over the limit waits, in arrival order,
 * until the oldest admitted one leaves the window, as the extension runtime's `RateLimitInterceptor`
 * makes it wait. The latency starts once a request is admitted, as a server's does.
 *
 * [answer] sees the call's index and either returns or throws, after the latency, as a server's
 * refusal arrives. Single threaded: the tests that use it run the pass on one test scheduler.
 */
internal class PacedSource(
    override val id: Long,
    private val scheduler: TestCoroutineScheduler,
    private val latency: (call: Int) -> Duration,
    private val permits: Int? = null,
    private val period: Duration = 1.seconds,
    private val load: Load? = null,
    /** What [baseUrl] answers; a lambda so a test can make it throw, as an extension's getter can. */
    private val site: () -> String? = { null },
    private val answer: (call: Int) -> MangaUpdate = { MangaUpdate(null, emptyList()) },
) : Source {

    /** One request: when it arrived, how many were in flight with it, when it was admitted and ended. */
    class Call(val index: Int, val arrivedAt: Long, val inFlight: Int) {
        var admittedAt = -1L
        var endedAt = -1L
    }

    val calls = mutableListOf<Call>()

    var inFlight = 0
        private set

    /** Every change of [inFlight], as virtual time to the count after it. */
    private val changes = mutableListOf<Pair<Long, Int>>()

    private val admission = Mutex()
    private val admitted = ArrayDeque<Long>()

    override val name: String = "Fixture Source $id"
    override val lang: String = "en"
    override val baseUrl: String? get() = site()

    override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = manga
    override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = emptyList()
    override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = emptyList()
    override suspend fun getImage(page: PageInfo): ImageResult = error("Unused by the update pass")

    override suspend fun getMangaUpdate(
        manga: MangaInfo,
        chapters: List<ChapterInfo>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): MangaUpdate {
        inFlight++
        changes += scheduler.currentTime to inFlight
        load?.enter()
        val call = Call(calls.size, scheduler.currentTime, inFlight)
        calls += call
        try {
            admit()
            call.admittedAt = scheduler.currentTime
            delay(latency(call.index))
            return answer(call.index)
        } finally {
            call.endedAt = scheduler.currentTime
            inFlight--
            changes += scheduler.currentTime to inFlight
            load?.leave()
        }
    }

    private suspend fun admit() {
        val permits = permits ?: return
        val window = period.inWholeMilliseconds
        admission.withLock {
            while (admitted.size >= permits) {
                val windowStart = scheduler.currentTime - window
                while (admitted.isNotEmpty() && admitted.first() <= windowStart) admitted.removeFirst()
                if (admitted.size >= permits) delay(admitted.first() - windowStart)
            }
            admitted.addLast(scheduler.currentTime)
        }
    }

    /**
     * The count in flight once everything that happened at [time] has happened. Requests that end
     * and start at one virtual instant pass through counts no one could see; this is the count the
     * source had from [time] on.
     */
    fun levelAt(time: Long): Int = changes.lastOrNull { it.first <= time }?.second ?: 0

    /** [levelAt] for every instant something changed, in order. */
    fun levels(): List<Int> = changes.map { it.first }.distinct().map(::levelAt)

    /**
     * Requests per second over this source's own window, from the first admission to the last end,
     * divided by [permits]: the harness's `min_rate_ratio` for one source (ruling R66).
     */
    fun rateRatio(): Double {
        val first = calls.minOf { it.admittedAt }
        val last = calls.maxOf { it.endedAt }
        return calls.size / ((last - first) / 1_000.0) / permits!!
    }

    companion object {
        /** Uniform between 400 and 800 ms, drawn in call order from [seed]: ruling R66's latency. */
        fun fixtureLatency(seed: Long): (Int) -> Duration {
            val random = Random(seed)
            return { (400 + random.nextInt(401)).milliseconds }
        }
    }
}
