package com.shinsaikou.libraryupdate

import com.shinsaikou.domain.error.ErrorKey
import eu.kanade.tachiyomi.network.HttpException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How many requests one site may have in flight during a library update, found from what the site
 * does rather than told (phase 2 decision Q3 (b)): additive increase, multiplicative decrease on the
 * site's measured completion rate. A site is every source whose base URL names one host, so the
 * language variants of one website share one of these; `FlatLibraryUpdate` says why.
 *
 * - It starts at one.
 * - After each window of [WINDOW] completed requests it gains one if the window's completion rate
 *   rose by at least [MIN_RISE] over the best window's since the run started or since the last
 *   backoff. The first window of a run, and the first after a backoff, has nothing to compare with
 *   and gains.
 * - A backoff ([isBackoff]) halves it, never below one, and starts a new window. Only a request
 *   started after the last backoff took effect can back off again: the requests already in flight
 *   when the site first pushed back were sent before the queue could hear it, so a burst of refusals
 *   is one backoff, as one round of losses is one halving in TCP's congestion control.
 * - It never passes [PER_SITE_CAP].
 *
 * Two more rules answer a site that pushes back, beyond halving:
 *
 * - A 429 or 503 whose response named a `Retry-After` [holds] the site: no new request starts
 *   until that time has passed, capped at [MAX_HOLD]. A later hold that ends sooner never shortens
 *   one already set.
 * - After [MAX_BACKOFFS] backoffs the site is [stopped] for the rest of the run, and the queue
 *   reports the site's remaining entries as failures rather than ask it again.
 *
 * A site that answers faster the more it is asked keeps gaining; one that sits at its own rate
 * limit stops, because a request over the limit waits inside the extension's limiter and the rate
 * does not move. That leaves about one request per site parked there, which is the price of finding
 * the limit without reading it.
 *
 * How the rate is measured, and why not as eight completions over the window's length. Eight
 * completions time a rate only to within about ten percent: at 5 requests a second and 400 to 800
 * ms per request, windows differ by that much on noise alone, and a count that compared each window
 * with the previous one climbed to the cap on a source whose rate never moved. When every request
 * takes the same time the completions arrive in batches, and a window that starts inside a batch
 * reads a third high. So the rate is taken by Little's law instead, as the window's average number
 * in flight over the mean time a request took, and the time is taken only from requests started
 * since the count last changed, because the window after a gain has the new count in flight at once
 * while the requests ending in it were started under the old one. The bar is the best window rather
 * than the previous one, so a noisy high raises it and gains on noise die out, while a real rise
 * still clears it.
 *
 * Not thread safe: the queue's coordinator is its only caller.
 */
internal class AdaptiveSourceLimit(private val timeSource: TimeSource) {

    /** What one entry's turn did to the site. */
    enum class Request {
        /** Nothing was asked of the site: the entry left the library, or its source is a stub. */
        NONE,

        /** A request finished, with an answer or with a failure that is not a backoff. */
        COMPLETED,

        /** A request finished with a failure [isBackoff] accepts. */
        BACKOFF,
    }

    var limit: Int = 1
        private set

    var inFlight: Int = 0
        private set

    /** The completion rate of the last full window, in requests per second; 0 before the first. */
    var lastRate: Double = 0.0
        private set

    /** The best window's rate since the run started or the last backoff; 0 when there is none. */
    private var bestRate = 0.0

    /** Bumped whenever [limit] changes, so a request can say which count it was started under. */
    private var generation = 0

    /** [generation] as the last backoff left it: a request started under an earlier one predates it. */
    private var backoffGeneration = 0

    /** Backoffs counted this run, by the rule in the class comment. */
    var backoffs: Int = 0
        private set

    /** The site pushed back [MAX_BACKOFFS] times: the queue asks it nothing more this run. */
    val stopped: Boolean
        get() = backoffs >= MAX_BACKOFFS

    /** When the last `Retry-After` ends; null when none was named. */
    private var heldUntil: TimeMark? = null

    private var windowStart: TimeMark? = null
    private var windowCompletions = 0

    /** The integral of [inFlight] over the window, in request seconds. */
    private var windowInFlightSeconds = 0.0
    private var lastChange: TimeMark? = null

    /** The time taken by the window's requests that were started under the current [limit]. */
    private var windowSeconds = 0.0
    private var windowTimed = 0
    private var lastMeanSeconds = 0.0

    /** Whether a new request may start now: not stopped, not held, and under the count. */
    val hasRoom: Boolean
        get() = !stopped && holds() == null && inFlight < limit

    /** How long the site's `Retry-After` still holds new requests back, or null when it does not. */
    fun holds(): Duration? {
        val until = heldUntil ?: return null
        val left = -until.elapsedNow()
        if (left.isPositive()) return left
        heldUntil = null
        return null
    }

    /** Counts a request in; the result is handed back to [onEnd] with the request's outcome. */
    fun onStart(): Int {
        if (windowStart == null) windowStart = timeSource.markNow()
        accrue()
        inFlight++
        return generation
    }

    /**
     * Counts a request out. [seconds] is how long it took, from its start to here; [startedUnder]
     * is what [onStart] returned for it. [retryAfter] is the wait a backoff's response named (the
     * companion's [Companion.retryAfter]); it holds the site even when the backoff predates the last
     * one and so neither halves nor counts, because it is the site's latest word on when to ask
     * again.
     */
    fun onEnd(request: Request, seconds: Double, startedUnder: Int, retryAfter: Duration? = null) {
        accrue()
        inFlight--
        when (request) {
            Request.NONE -> Unit
            Request.BACKOFF -> {
                retryAfter?.let(::hold)
                if (startedUnder < backoffGeneration) return
                backoffs++
                limit = maxOf(1, limit / 2)
                generation++
                backoffGeneration = generation
                bestRate = 0.0
                startWindow()
            }
            Request.COMPLETED -> {
                windowCompletions++
                if (startedUnder == generation) {
                    windowSeconds += seconds
                    windowTimed++
                }
                if (windowCompletions < WINDOW) return
                val rate = windowRate()
                if (rate >= bestRate * (1 + MIN_RISE) && limit < PER_SITE_CAP) {
                    limit++
                    generation++
                }
                lastRate = rate
                bestRate = maxOf(bestRate, rate)
                startWindow()
            }
        }
    }

    /**
     * Little's law: requests in flight on average over the window, divided by the mean time a
     * request took. With no request of the window started under the current count (possible only
     * just below the cap), the last window's mean stands in. A window that took no measurable time
     * is as fast as a window can be.
     */
    private fun windowRate(): Double {
        val length = windowStart!!.elapsedNow().toDouble(DurationUnit.SECONDS)
        val mean = if (windowTimed > 0) windowSeconds / windowTimed else lastMeanSeconds
        lastMeanSeconds = mean
        if (length <= 0.0 || mean <= 0.0) return Double.POSITIVE_INFINITY
        return (windowInFlightSeconds / length) / mean
    }

    private fun hold(wait: Duration) {
        val capped = wait.coerceAtMost(MAX_HOLD)
        if (!capped.isPositive()) return
        val current = holds()
        if (current != null && current >= capped) return
        heldUntil = timeSource.markNow() + capped
    }

    private fun startWindow() {
        windowStart = timeSource.markNow()
        windowCompletions = 0
        windowInFlightSeconds = 0.0
        windowSeconds = 0.0
        windowTimed = 0
    }

    private fun accrue() {
        lastChange?.let { windowInFlightSeconds += inFlight * it.elapsedNow().toDouble(DurationUnit.SECONDS) }
        lastChange = timeSource.markNow()
    }

    companion object {
        /** Completed requests per measurement window. */
        const val WINDOW = 8

        /** The rise over the best window so far that earns one more request in flight. */
        const val MIN_RISE = 0.10

        /**
         * Requests one site may have in flight. Eight at once to one scraped site is the pattern
         * that gets an address banned or a Cloudflare challenge switched on, and no extension of
         * the compat corpus's 320 names the host's `RateLimitInterceptor` class, so the queue
         * cannot count on a limiter in front of every site.
         *
         * Four, not three: a site that allows 5 requests a second and takes about 0.6 s a request
         * (ruling R66's bench: 0.4 to 0.8 s) is kept busy only by about three in flight on
         * average, by Little's law, and a ceiling of exactly three, which the count rides below
         * part of the time, reached 0.856 of that rate on device against R66's mark of 0.9. With
         * the cap at eight the bench never went past four. Four is half the old ceiling and sits
         * behind the halving, [MAX_HOLD] and [MAX_BACKOFFS] rules below.
         */
        const val PER_SITE_CAP = 4

        /** Backoffs that stop a site for the rest of the run. `error_site_stopped` says "three". */
        const val MAX_BACKOFFS = 3

        /** The longest `Retry-After` the queue honours; a site naming more is asked again after this. */
        val MAX_HOLD: Duration = 60.seconds

        /**
         * A site asking to be asked less: an HTTP 429 or 503; an HTTP 403 that is Cloudflare's
         * challenge page (`cf-mitigated: challenge`, known only when the host built the exception
         * from the response); a challenge the Cloudflare interceptor could not get past, or gave up
         * waiting on; or a socket timeout. Looked for at the top of [error] and one cause deep, as
         * [ErrorKey.of] looks, because the network stack rethrows some failures as a plain
         * `IOException` carrying the original.
         *
         * The pass's own per-entry watchdog is not one: it bounds how long an entry may take, and
         * says nothing the site chose to say.
         */
        fun isBackoff(error: Throwable): Boolean {
            val http = httpException(error)
            return http?.code in BACKOFF_CODES ||
                (http?.code == CHALLENGE_CODE && http.pushback?.challenge == true) ||
                error is SocketTimeoutException ||
                error.cause is SocketTimeoutException ||
                ErrorKey.of(error) in CHALLENGE_KEYS
        }

        /** The wait a 429 or 503 named in its `Retry-After`, when the host kept the response's headers. */
        fun retryAfter(error: Throwable): Duration? =
            httpException(error)?.takeIf { it.code in BACKOFF_CODES }?.pushback?.retryAfter

        private fun httpException(error: Throwable): HttpException? =
            error as? HttpException ?: error.cause as? HttpException

        private val BACKOFF_CODES = setOf(429, 503)
        private const val CHALLENGE_CODE = 403
        private val CHALLENGE_KEYS = setOf(ErrorKey.CLOUDFLARE, ErrorKey.CLOUDFLARE_BUDGET)
    }
}
