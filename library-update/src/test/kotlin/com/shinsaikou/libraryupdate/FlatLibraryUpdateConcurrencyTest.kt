package com.shinsaikou.libraryupdate

import com.shinsaikou.domain.source.toMangaInfo
import com.shinsaikou.source.Source
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * The seven checks of the phase 2 plan's unit E Done list that are about the queue. The first four
 * run on virtual time with [PacedSource]s whose latency and permit-based rate limit each test sets;
 * the cancel and the accumulators need real threads, because what they guard against is a thread
 * that is blocked and threads that race. The seventh is `LibraryUpdateErrorsUnchangedTest`.
 */
class FlatLibraryUpdateConcurrencyTest {

    // region 1. the in-flight count follows the completion rate, under both caps

    /**
     * 900 ms per request and 1 request a second. One in flight already answers 1.1 a second, which
     * the limiter holds to 1: the first window always gains (it has nothing to compare with), and
     * the second, at two in flight, only makes the second request wait in the limiter, so the rate
     * does not rise. The count stops at two, below [AdaptiveSourceLimit.PER_SITE_CAP], and stays
     * there for the rest of the run. (A rate that stops rising only at the cap or later cannot be
     * told from the cap itself, which 1b covers.)
     */
    @Test
    fun `1a a source's in-flight count rises with its rate and stops where the rate stops rising`() = runTest {
        val source = PacedSource(1L, testScheduler, latency = { 900.milliseconds }, permits = 1)
        val bench = UpdateBench(listOf(source), entriesPerSource = 100)

        bench.pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource).run(null, automatic = false) {}

        val levels = source.levels()
        println("1a in flight: ${levels.runLengths()}; rate over limit ${source.rateRatio()}")
        levels.runningReduce(::maxOf).distinct() shouldContainExactly listOf(1, 2)
        levels.max() shouldBeLessThan AdaptiveSourceLimit.PER_SITE_CAP
        source.rateRatio() shouldBeGreaterThanOrEqualTo 0.9
    }

    /** With no limit, every step raises the rate, so the count climbs to the per-source cap and stops. */
    @Test
    fun `1b a source that keeps answering faster stops at the per-source cap`() = runTest {
        val source = PacedSource(1L, testScheduler, latency = { 600.milliseconds })
        val bench = UpdateBench(listOf(source), entriesPerSource = 300)

        bench.pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource).run(null, automatic = false) {}

        val levels = source.levels()
        println("1b in flight: ${levels.runLengths()}")
        levels.runningReduce(::maxOf).distinct() shouldContainExactly (1..AdaptiveSourceLimit.PER_SITE_CAP).toList()
        source.calls.maxOf { it.inFlight } shouldBe AdaptiveSourceLimit.PER_SITE_CAP
    }

    /**
     * 1a with ruling R66's latency, 400 to 800 ms drawn per call, where latency times limit is 3:
     * the rate stops rising at about three in flight, and eight completions time it only to within about
     * ten percent, so windows differ by that much on noise alone. Each source stays within the
     * per-site cap, which sits just above where its rate stops rising, so at most one request waits
     * in its limiter. A count that compared each window with the previous one kept gaining on that noise
     * and reached seven and eight here (`evidence/phase2-flat-queue/previous-window-comparison.txt`,
     * measured when the cap was eight); with the cap at four that failure shows only as a source
     * pinned at the cap, which is why the bound is the cap and not a distance from three.
     */
    @Test
    fun `1d a source whose latency varies never passes the per-site cap`() = runTest {
        val sources = (1L..NOISY_SOURCES).map {
            PacedSource(it, testScheduler, latency = PacedSource.fixtureLatency(it), permits = FIXTURE_PERMITS)
        }
        val bench = UpdateBench(sources, entriesPerSource = 250)

        bench.pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource).run(null, automatic = false) {}

        sources.forEach { println("1d source ${it.id} in flight: ${it.levels().runLengths()}") }
        sources.forEach { it.levels().max() shouldBeLessThanOrEqualTo AdaptiveSourceLimit.PER_SITE_CAP }
    }

    /** Twenty sources that could each take the per-site cap: the run as a whole stops at the global cap. */
    @Test
    fun `1c the run never has more than the global cap in flight`() = runTest {
        val load = Load()
        val sources = (1L..20L).map { PacedSource(it, testScheduler, latency = { 600.milliseconds }, load = load) }
        val bench = UpdateBench(sources, entriesPerSource = 100)

        bench.pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource).run(null, automatic = false) {}

        println("1c most in flight at once: ${load.max}, per source ${sources.map { it.levels().max() }}")
        load.max shouldBe FlatLibraryUpdate.GLOBAL_CAP
        sources.forEach { it.calls shouldHaveSize 100 }
    }

    // endregion

    // region 2. a backoff halves that source and no other

    @Test
    fun `2a an HTTP 429 halves that source's in-flight count and leaves the other alone`() =
        backoffHalves(HttpException(429))

    @Test
    fun `2b an HTTP 503 halves that source's in-flight count and leaves the other alone`() =
        backoffHalves(HttpException(503))

    @Test
    fun `2c a socket timeout halves that source's in-flight count and leaves the other alone`() =
        backoffHalves(SocketTimeoutException("fixture: read timed out"))

    /**
     * Both sources climb to the cap (1b). Source 1's call [FAILING_CALL] then answers [error], in a
     * batch with the others started beside it. The site's requests are spaced by its rate, so once
     * the requests already in flight have ended (one latency later), source 1 has half the cap in
     * flight where it had the cap, and source 2 still has the cap. Source 1 then climbs again by
     * one a window, because nothing limits it (1b), which is the additive half.
     */
    private fun backoffHalves(error: Throwable) = runTest {
        val refused = PacedSource(1L, testScheduler, latency = { LATENCY }) { call ->
            if (call == FAILING_CALL) throw error
            MangaUpdate(null, emptyList())
        }
        val other = PacedSource(2L, testScheduler, latency = { LATENCY })
        val bench = UpdateBench(listOf(refused, other), entriesPerSource = 200)

        val outcome = bench.pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
            .run(null, automatic = false) {}

        val refusedAt = refused.calls[FAILING_CALL].endedAt
        val settled = refusedAt + LATENCY.inWholeMilliseconds
        val around = (-2L..4L).map { refusedAt + it * LATENCY.inWholeMilliseconds / 2 }
        println("2 ${error::class.simpleName} at ${refusedAt}ms, in flight refused/other: " + around.map { "$it:${refused.levelAt(it)}/${other.levelAt(it)}" })
        refused.levelAt(refusedAt - 1) shouldBe AdaptiveSourceLimit.PER_SITE_CAP
        refused.levelAt(settled) shouldBe AdaptiveSourceLimit.PER_SITE_CAP / 2
        other.levelAt(refusedAt - 1) shouldBe AdaptiveSourceLimit.PER_SITE_CAP
        other.levelAt(settled) shouldBe AdaptiveSourceLimit.PER_SITE_CAP
        outcome.failures.single().error shouldBeSameInstanceAs error
    }

    // endregion

    // region 3 and 4. throughput

    /**
     * The throughput row's scenario at the JVM level (ruling R66): two sources of 250 entries at 400
     * to 800 ms and 5 requests a second, so latency times limit is 3. The rate needs about three
     * requests in flight on average (Little's law), and the cap of four leaves the count room above
     * that average, so R66's 0.9 throughput row holds (a cap of exactly three reached only 0.856).
     * Each source never has more than [AdaptiveSourceLimit.PER_SITE_CAP] in flight and its requests
     * over its own window reach at least 0.9 of its limit.
     */
    @Test
    fun `3 with latency times limit at 3 each source stays within the cap and reaches 0_9 of its limit`() = runTest {
        val sources = (1L..2L).map {
            PacedSource(it, testScheduler, latency = PacedSource.fixtureLatency(it), permits = FIXTURE_PERMITS)
        }
        val bench = UpdateBench(sources, entriesPerSource = 250)

        bench.pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource).run(null, automatic = false) {}

        val ratios = sources.map { it.rateRatio() }
        println("3 rate over limit per source: $ratios")
        sources.forEach { println("3 source ${it.id} in flight: ${it.levels().runLengths()}") }
        sources.forEach { it.levels().max() shouldBeLessThanOrEqualTo AdaptiveSourceLimit.PER_SITE_CAP }
        ratios.forEach { it shouldBeGreaterThanOrEqualTo 0.9 }
    }

    /**
     * 2,000 entries over 20 sources at the same parameters, against the same fakes driven the way
     * upstream v0.20.4 drives them: a semaphore of five over the sources grouped by id, each source's
     * entries one after another. A sanity check; the gate row is unit F's.
     */
    @Test
    fun `4 two thousand entries over twenty sources take at most a quarter of a five-source semaphore's time`() = runTest {
        val flat = virtualTime {
            UpdateBench(fixtureSources(), entriesPerSource = 100)
                .pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource)
                .run(null, automatic = false) {}
        }
        val grouped = virtualTime {
            val sources = fixtureSources()
            val bench = UpdateBench(sources, entriesPerSource = 100)
            val semaphore = Semaphore(UPSTREAM_SOURCES_AT_ONCE)
            coroutineScope {
                bench.library.groupBy { it.manga.source }.map { (sourceId, entries) ->
                    val source = sources.single { it.id == sourceId }
                    async {
                        semaphore.withPermit {
                            entries.forEach { source.getMangaUpdate(it.manga.toMangaInfo(), emptyList(), fetchDetails = false, fetchChapters = true) }
                        }
                    }
                }.awaitAll()
            }
        }

        println("4 flat queue $flat, five-source semaphore $grouped, ratio ${flat / grouped}")
        flat shouldBeLessThanOrEqualTo grouped / 4
    }

    private fun TestScope.fixtureSources() = (1L..20L).map {
        PacedSource(it, testScheduler, latency = PacedSource.fixtureLatency(it), permits = FIXTURE_PERMITS)
    }

    /** How long [block] takes on the test scheduler's virtual clock. */
    private suspend fun TestScope.virtualTime(block: suspend () -> Unit): Duration {
        val start = testScheduler.currentTime
        block()
        return (testScheduler.currentTime - start).milliseconds
    }

    // endregion

    // region 5. the cancel

    /**
     * Twenty sources, each with its one request holding a real thread in a blocking wait that
     * ignores cancellation, the way a source stalls in socket I/O. The cancel returns inside the
     * bound the sequential pass's test held it to (the device row allows 10 s), with every one of
     * those threads still blocked: the queue neither joins them nor waits for a permit they hold.
     */
    @Test
    fun `5 cancel returns within the bound while every source's thread is blocked`() = runBlocking<Unit> {
        val entered = CountDownLatch(BLOCKED_SOURCES)
        val release = CountDownLatch(1)
        val stillBlocked = AtomicInteger()
        val sources = (1L..BLOCKED_SOURCES.toLong()).map { id ->
            BlockingSource(id) {
                stillBlocked.incrementAndGet()
                entered.countDown()
                release.await(BLOCK_BOUND_SECONDS, TimeUnit.SECONDS)
                stillBlocked.decrementAndGet()
            }
        }
        val bench = UpdateBench(sources, entriesPerSource = 10)
        val update = bench.pass(Dispatchers.IO, TimeSource.Monotonic)

        try {
            var outcome: FlatLibraryUpdate.Outcome? = null
            val job = launch(Dispatchers.IO) { outcome = update.run(null, automatic = false) {} }
            entered.await(BLOCK_BOUND_SECONDS, TimeUnit.SECONDS) shouldBe true

            val elapsed = measureTime { job.cancelAndJoin() }

            println("5 cancel returned in $elapsed with ${stillBlocked.get()} threads still blocked")
            stillBlocked.get() shouldBe BLOCKED_SOURCES
            outcome?.cancelled shouldBe true
            elapsed shouldBeLessThan 500.milliseconds
        } finally {
            release.countDown()
        }
    }

    // endregion

    // region 6. the accumulators

    /**
     * Every entry of twenty sources fails, on real threads, as many at once as the caps allow. Each
     * failure is recorded once, in queue order (defect 1.5 of the phase 2 plan: plain lists raced).
     */
    @Test
    fun `6a concurrent failures from many sources are all recorded`() = runBlocking<Unit> {
        val sources = (1L..20L).map { id -> BlockingSource(id) { throw IllegalStateException("fixture: refused") } }
        val bench = UpdateBench(sources, entriesPerSource = 100)

        val outcome = withContext(Dispatchers.IO) {
            bench.pass(Dispatchers.IO, TimeSource.Monotonic).run(null, automatic = false) {}
        }

        outcome.failures.map { it.manga.id } shouldContainExactly bench.library.map { it.id }
    }

    /** The same for new chapters: every entry lands in `updated`, and the badge counts every chapter. */
    @Test
    fun `6b concurrent new chapters from many sources are all recorded and counted`() = runBlocking<Unit> {
        val sources = (1L..20L).map { id -> BlockingSource(id) {} }
        val bench = UpdateBench(sources, entriesPerSource = 100, newChapters = true)

        val outcome = withContext(Dispatchers.IO) {
            bench.pass(Dispatchers.IO, TimeSource.Monotonic).run(null, automatic = false) {}
        }

        outcome.updated.keys.toList() shouldContainExactly bench.library.map { it.id }
        bench.preferences.newUpdatesCount.get() shouldBe bench.library.size
    }

    // endregion

    /** A source whose every request runs [block] on the calling thread, then answers with nothing. */
    private class BlockingSource(override val id: Long, private val block: () -> Unit) : Source {
        override val name: String = "Fixture Source $id"
        override val lang: String = "en"

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
            block()
            return MangaUpdate(null, emptyList())
        }
    }

    private companion object {
        /** Well after source 1 has reached the cap of four in flight, which takes three windows (24 calls). */
        const val FAILING_CALL = 120
        val LATENCY = 600.milliseconds
        const val FIXTURE_PERMITS = 5

        /** Four sources that could each take the per-site cap stay under the global cap, so none waits on it. */
        const val NOISY_SOURCES = 4L
        const val UPSTREAM_SOURCES_AT_ONCE = 5
        const val BLOCKED_SOURCES = 20
        const val BLOCK_BOUND_SECONDS = 10L

        /** `[1, 1, 2, 2, 2]` as `1x2 2x3`, so a trajectory of hundreds of calls prints on one line. */
        fun List<Int>.runLengths(): String = buildList {
            var i = 0
            while (i < this@runLengths.size) {
                var j = i
                while (j < this@runLengths.size && this@runLengths[j] == this@runLengths[i]) j++
                add("${this@runLengths[i]}x${j - i}")
                i = j
            }
        }.joinToString(" ")
    }
}
