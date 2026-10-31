package com.shinsaikou.libraryupdate

import com.shinsaikou.core.common.network.CloudflareBudgetExceededException
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.source.model.MangaUpdate
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the queue does when a site pushes back, and how it tells one site from another. Every test
 * runs the real pass on virtual time against [PacedSource]s, so the in-flight counts, the waits
 * and the order of the failures are exact. Hosts are `example.invalid` names, never real sites.
 */
class FlatLibraryUpdatePushbackTest {

    private val cap = AdaptiveSourceLimit.PER_SITE_CAP

    private fun TestScope.update(bench: UpdateBench): FlatLibraryUpdate =
        bench.pass(StandardTestDispatcher(testScheduler), testScheduler.timeSource)

    private fun TestScope.source(
        id: Long,
        site: String? = null,
        latency: (Int) -> Duration = { LATENCY },
        load: Load? = null,
        answer: (Int) -> MangaUpdate = { MangaUpdate(null, emptyList()) },
    ) = PacedSource(id, testScheduler, latency = latency, load = load, site = { site }, answer = answer)

    // region one budget per site

    /**
     * Two sources whose base URLs share a host but differ in scheme, port and path are one website:
     * together they never have more than the cap in flight, at any instant, while a third on
     * another host reaches the cap on its own at the same time.
     */
    @Test
    fun `sources sharing a host share one budget while another host has its own`() = runTest {
        val shared = Load()
        val a = source(1L, "https://shared.example/en/", load = shared)
        val b = source(2L, "http://shared.example:8080/fr", load = shared)
        val c = source(3L, "https://other.example", load = null)
        val bench = UpdateBench(listOf(a, b, c), entriesPerSource = 60)

        val outcome = update(bench).run(null, automatic = false) {}

        val atCapAt = c.calls.first { it.inFlight == cap }.arrivedAt
        println("shared host: most in flight together ${shared.max}; other host reached ${c.levels().max()}; " +
            "at ${atCapAt}ms the shared pair had ${a.levelAt(atCapAt) + b.levelAt(atCapAt)} in flight")
        shared.max shouldBe cap
        c.levels().max() shouldBe cap
        (a.levelAt(atCapAt) + b.levelAt(atCapAt)) shouldBeGreaterThan 0
        listOf(a, b, c).forEach { it.calls shouldHaveSize 60 }
        outcome.failures shouldHaveSize 0
    }

    @Test
    fun `two sources with no base URL each get their own budget`() = runTest {
        val both = Load()
        val a = source(1L, site = null, load = both)
        val b = source(2L, site = null, load = both)
        val bench = UpdateBench(listOf(a, b), entriesPerSource = 60)

        update(bench).run(null, automatic = false) {}

        println("no base URL: per source ${a.levels().max()} and ${b.levels().max()}, together ${both.max}")
        a.levels().max() shouldBe cap
        b.levels().max() shouldBe cap
        both.max shouldBe 2 * cap
    }

    @Test
    fun `a source whose base URL getter throws is keyed by id and all its entries update`() = runTest {
        val both = Load()
        val throwing = PacedSource(
            1L, testScheduler, latency = { LATENCY }, load = both, site = { error("fixture: extension bug") },
        )
        val other = source(2L, site = null, load = both)
        val bench = UpdateBench(listOf(throwing, other), entriesPerSource = 40)

        val outcome = update(bench).run(null, automatic = false) {}

        println("throwing base URL: per source ${throwing.levels().max()} and ${other.levels().max()}, together ${both.max}")
        throwing.calls shouldHaveSize 40
        other.calls shouldHaveSize 40
        throwing.levels().max() shouldBe cap
        both.max shouldBe 2 * cap
        outcome.failures shouldHaveSize 0
    }

    @Test
    fun `sources whose base URL does not parse are each their own site`() = runTest {
        val both = Load()
        val a = source(1L, site = "not a url", load = both)
        val b = source(2L, site = "not a url", load = both)
        val bench = UpdateBench(listOf(a, b), entriesPerSource = 40)

        update(bench).run(null, automatic = false) {}

        both.max shouldBe 2 * cap
    }

    // endregion

    // region what counts as a backoff

    private fun response(code: Int, vararg headers: Pair<String, String>): Response = Response.Builder()
        .request(Request.Builder().url("https://shared.example/m").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Fixture")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()

    @Test
    fun `a 403 built from a Cloudflare challenge response halves the site like a 429`() =
        backoffKind(HttpException(response(403, "cf-mitigated" to "challenge")), halves = true)

    @Test
    fun `a plain 403 does not halve the site`() = backoffKind(HttpException(403), halves = false)

    @Test
    fun `a 403 built from a response with no challenge mark does not halve the site`() =
        backoffKind(HttpException(response(403)), halves = false)

    @Test
    fun `a Cloudflare budget failure halves the site`() = backoffKind(
        IOException("interceptor text", CloudflareBudgetExceededException(host = "example.invalid", budget = 45.seconds)),
        halves = true,
    )

    @Test
    fun `a Cloudflare bypass failure halves the site`() =
        backoffKind(IOException("interceptor text", CloudflareBypassException()), halves = true)

    /**
     * One source climbs to the cap, then call [REFUSED_CALL] answers [error] in a batch with the others
     * started beside it. Once the requests already in flight have ended (one latency later) the source has
     * half the cap in flight if the error is a backoff, and the cap still if it is not.
     */
    private fun backoffKind(error: Throwable, halves: Boolean) = runTest {
        val s = source(1L, "https://shared.example") { call -> if (call == REFUSED_CALL) throw error else MangaUpdate(null, emptyList()) }
        val bench = UpdateBench(listOf(s), entriesPerSource = 120)

        update(bench).run(null, automatic = false) {}

        val at = s.calls[REFUSED_CALL].endedAt
        // The site's requests are spaced by its rate, so the count falls to the new limit as the
        // requests already in flight end, one latency after the refusal.
        val settled = at + LATENCY.inWholeMilliseconds
        println(
            "${error::class.simpleName} at ${at}ms, in flight before/settled: ${s.levelAt(at - 1)}/${s.levelAt(settled)}",
        )
        s.levelAt(at - 1) shouldBe cap
        s.levelAt(settled) shouldBe if (halves) cap / 2 else cap
    }

    // endregion

    // region Retry-After

    /** What [holdAfter] saw: A's first refusal, when A was asked next, and how busy B was meanwhile. */
    private class HoldRun(val a: PacedSource, val b: PacedSource, val outcome: FlatLibraryUpdate.Outcome) {
        val refusedAt: Long = a.calls[0].endedAt
        val nextAskAt: Long = a.calls[1].arrivedAt
        val gap: Long = nextAskAt - refusedAt
        val otherAsked: Int = b.calls.count { it.arrivedAt > refusedAt && it.arrivedAt < nextAskAt }
    }

    /**
     * Source A's first call answers a refusal built from [code] and [headers]; source B, on another
     * host, is asked throughout. Every entry of both is fetched in the end.
     */
    private suspend fun TestScope.holdAfter(code: Int, vararg headers: Pair<String, String>): HoldRun {
        val refusal = HttpException(response(code, *headers))
        val a = source(1L, "https://a.example") { call -> if (call == 0) throw refusal else MangaUpdate(null, emptyList()) }
        val b = source(2L, "https://b.example")
        val outcome = update(UpdateBench(listOf(a, b), entriesPerSource = 40)).run(null, automatic = false) {}
        val run = HoldRun(a, b, outcome)
        println("$code ${headers.toList()}: refused at ${run.refusedAt}ms, asked again at ${run.nextAskAt}ms " +
            "(held ${run.gap}ms), other site asked ${run.otherAsked} times meanwhile")
        a.calls shouldHaveSize 40
        b.calls shouldHaveSize 40
        outcome.failures.single().error shouldBe refusal
        return run
    }

    /** A 429 naming 5 s: A is not asked for 5 s of virtual time, and B is asked all along. */
    @Test
    fun `a 429 with Retry-After holds that site for the named seconds while another site is asked`() = runTest {
        val run = holdAfter(429, "Retry-After" to "5")

        run.gap shouldBe 5_000L
        run.otherAsked shouldBeGreaterThan 5
    }

    @Test
    fun `a 503 with Retry-After holds that site`() = runTest {
        val run = holdAfter(503, "Retry-After" to "5")

        run.gap shouldBe 5_000L
        run.otherAsked shouldBeGreaterThan 5
    }

    @Test
    fun `a Retry-After of 600 seconds holds for the longest hold only`() = runTest {
        val run = holdAfter(429, "Retry-After" to "600")

        run.gap shouldBe AdaptiveSourceLimit.MAX_HOLD.inWholeMilliseconds
    }

    @Test
    fun `a Retry-After date far in the future holds for the longest hold only`() = runTest {
        val run = holdAfter(
            429,
            "Retry-After" to "Wed, 21 Oct 2037 07:28:00 GMT",
            "Date" to "Wed, 21 Oct 2026 07:28:00 GMT",
        )

        run.gap shouldBe AdaptiveSourceLimit.MAX_HOLD.inWholeMilliseconds
    }

    @Test
    fun `a Retry-After date already past holds nothing`() = runTest {
        val run = holdAfter(
            429,
            "Retry-After" to "Tue, 20 Oct 2026 07:28:00 GMT",
            "Date" to "Wed, 21 Oct 2026 07:28:00 GMT",
        )

        run.gap shouldBe 0L
    }

    @Test
    fun `a 429 that names no Retry-After holds nothing`() = runTest {
        val run = holdAfter(429)

        run.gap shouldBe 0L
    }

    /**
     * Two requests of one site refuse a moment apart: the first names 30 s, the second, which was
     * sent before the first refusal was heard and so does not count as a backoff, names 5 s. The
     * site is asked again after 30 s, not after the later, shorter 5.
     */
    @Test
    fun `a later shorter Retry-After does not shorten a hold already running`() = runTest {
        val first = HttpException(response(429, "Retry-After" to "30"))
        val second = HttpException(response(429, "Retry-After" to "5"))
        val s = source(
            1L,
            "https://shared.example",
            // Call 9 answers 100 ms after call 8, so the order of the two refusals is fixed.
            latency = { call -> if (call == 9) LATENCY + 100.milliseconds else LATENCY },
        ) { call ->
            when (call) {
                8 -> throw first
                9 -> throw second
                else -> MangaUpdate(null, emptyList())
            }
        }

        val outcome = update(UpdateBench(listOf(s), entriesPerSource = 40)).run(null, automatic = false) {}

        val firstRefusedAt = s.calls[8].endedAt
        println("two refusals: ${firstRefusedAt}ms and ${s.calls[9].endedAt}ms, asked again at ${s.calls[10].arrivedAt}ms")
        s.calls[8].arrivedAt shouldBe s.calls[9].arrivedAt
        s.calls[9].endedAt shouldBe firstRefusedAt + 100
        s.calls[10].arrivedAt - firstRefusedAt shouldBe 30_000L
        outcome.failures.map { it.error } shouldContainExactly listOf(first, second)
        s.calls shouldHaveSize 40
    }

    /**
     * Nothing else is in flight while the only site is held, so the coordinator has nothing to wake
     * it but the hold's end. The run still finishes, every entry fetched, and its virtual time
     * includes the wait.
     */
    @Test
    fun `a hold with nothing else in flight ends with every entry fetched and the wait in the run's time`() = runTest {
        val refusal = HttpException(response(429, "Retry-After" to "5"))
        val s = source(1L, "https://shared.example") { call -> if (call == 0) throw refusal else MangaUpdate(null, emptyList()) }
        val start = testScheduler.currentTime

        val outcome = update(UpdateBench(listOf(s), entriesPerSource = 10)).run(null, automatic = false) {}

        val elapsed = testScheduler.currentTime - start
        println("single held site: 10 entries in ${elapsed}ms, asked again ${s.calls[1].arrivedAt - s.calls[0].endedAt}ms after the refusal")
        s.calls shouldHaveSize 10
        s.calls[1].arrivedAt - s.calls[0].endedAt shouldBe 5_000L
        elapsed shouldBeGreaterThanOrEqualTo 5_000L + 2 * LATENCY.inWholeMilliseconds
        outcome.failures.single().error shouldBe refusal
        outcome.cancelled shouldBe false
    }

    // endregion

    // region three strikes

    private fun refuse429() = HttpException(response(429))

    private fun stoppedBy(failures: List<FlatLibraryUpdate.Failure>) =
        failures.mapNotNull { it.error as? SiteStoppedException }

    /**
     * A site that answers 429 to every request is asked exactly [AdaptiveSourceLimit.MAX_BACKOFFS]
     * times. Every entry it was not asked is a failure with a [SiteStoppedException] naming the
     * host, in queue order; a second site's entries all update.
     */
    @Test
    fun `a site that refuses every request is asked three times and its other entries fail as stopped`() = runTest {
        val refused = source(1L, "https://shared.example/en") { throw refuse429() }
        val healthy = source(2L, "https://healthy.example")
        val bench = UpdateBench(listOf(refused, healthy), entriesPerSource = 12, newChapters = true)

        val outcome = update(bench).run(null, automatic = false) {}

        println("three strikes: refused site asked ${refused.calls.size} times, healthy site ${healthy.calls.size}")
        refused.calls shouldHaveSize AdaptiveSourceLimit.MAX_BACKOFFS
        healthy.calls shouldHaveSize 12
        outcome.failures.map { it.manga.id } shouldContainExactly bench.library.filter { it.manga.source == 1L }.map { it.id }
        outcome.failures.take(3).forEach { it.error.shouldBeInstanceOf<HttpException>() }
        val stopped = outcome.failures.drop(3)
        stopped shouldHaveSize 9
        stopped.forEach {
            it.error.shouldBeInstanceOf<SiteStoppedException>().host shouldBe "shared.example"
            ErrorKey.of(it.error) shouldBe ErrorKey.SITE_STOPPED
            ErrorKey.detail(it.error) shouldBe "shared.example"
        }
        outcome.updated.keys.toList() shouldContainExactly bench.library.filter { it.manga.source == 2L }.map { it.id }
    }

    /**
     * Every concurrent request of a site at its cap refuses in one burst. They were all sent
     * before the first refusal could be heard, so they are one backoff: the site is not stopped and
     * the rest of its entries update.
     */
    @Test
    fun `a burst of refusals from requests already in flight counts as one backoff`() = runTest {
        // The first window of eight is at one in flight, the second at two, the third at three, so
        // calls 25 to 28 are the first four that run together (the count lags a window by the
        // completions still to come).
        val burst = 25..28
        val s = source(1L, "https://shared.example") { call -> if (call in burst) throw refuse429() else MangaUpdate(null, emptyList()) }
        val bench = UpdateBench(listOf(s), entriesPerSource = 60)

        val outcome = update(bench).run(null, automatic = false) {}

        println("burst: in flight at the burst ${burst.map { s.calls[it].inFlight }}, arrived ${burst.map { s.calls[it].arrivedAt }}, " +
            "failures ${outcome.failures.size}, calls ${s.calls.size}")
        s.calls[burst.last].inFlight shouldBe cap
        burst.map { s.calls[it].arrivedAt }.distinct() shouldHaveSize 1
        s.calls shouldHaveSize 60
        outcome.failures shouldHaveSize burst.count()
        stoppedBy(outcome.failures) shouldHaveSize 0
    }

    @Test
    fun `a stopped source that names no host fails with a stopped error that has no host`() = runTest {
        val s = source(1L, site = null) { throw refuse429() }
        val bench = UpdateBench(listOf(s), entriesPerSource = 6)

        val outcome = update(bench).run(null, automatic = false) {}

        s.calls shouldHaveSize 3
        val stopped = stoppedBy(outcome.failures)
        stopped shouldHaveSize 3
        stopped.forEach {
            it.host shouldBe null
            ErrorKey.of(it) shouldBe ErrorKey.SITE_STOPPED
            ErrorKey.detail(it) shouldBe null
        }
    }

    /**
     * Two language variants of one website with two entries each: the first refuses both of its,
     * the second its first. Neither refused three times alone; together they did, so the second's
     * other entry is not asked.
     */
    @Test
    fun `sources sharing a host share the strike count`() = runTest {
        val first = source(1L, "https://shared.example/en") { throw refuse429() }
        val second = source(2L, "https://shared.example/fr") { call -> if (call == 0) throw refuse429() else MangaUpdate(null, emptyList()) }
        val bench = UpdateBench(listOf(first, second), entriesPerSource = 2)

        val outcome = update(bench).run(null, automatic = false) {}

        println("shared strikes: asked ${first.calls.size} + ${second.calls.size}, stopped ${stoppedBy(outcome.failures).size}")
        first.calls shouldHaveSize 2
        second.calls shouldHaveSize 1
        val stopped = outcome.failures.filter { it.error is SiteStoppedException }
        stopped.map { it.manga.id } shouldContainExactly listOf(second.id * UpdateBench.ENTRY_ID_STRIDE + 2)
        stopped.single().error.shouldBeInstanceOf<SiteStoppedException>().host shouldBe "shared.example"
    }

    // endregion

    private class CloudflareBypassException : Exception()

    private companion object {
        val LATENCY = 600.milliseconds
        const val REFUSED_CALL = 60
    }
}
