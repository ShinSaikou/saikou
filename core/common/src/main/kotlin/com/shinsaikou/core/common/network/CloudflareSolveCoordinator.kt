package com.shinsaikou.core.common.network

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Process-wide coordination of challenge solves, one at a time per host.
 *
 * The solve itself parks an OkHttp dispatcher thread for up to half a minute while a WebView runs.
 * Without coordination, an update pass that fires several requests at one challenged host parks one
 * thread and spins up one WebView per request, all solving the same challenge. This runs the first
 * caller and makes the rest wait for it, per host, so unrelated hosts still solve in parallel.
 *
 * A failed solve is remembered for [FAILURE_MEMORY]. A challenge that wants a person fails the same
 * way every time, and without the memory each queued caller would take the lock in turn and spend
 * its own half minute learning that; with it, they fail at once until the memory lapses.
 */
@SingleIn(AppScope::class)
@Inject
class CloudflareSolveCoordinator {

    /** One lock per host; entries are never removed (a handful of hosts per session). */
    private val hostLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val hostWaiters = ConcurrentHashMap<String, AtomicInteger>()

    /** Host to the time its last solve failed, on [elapsedRealtimeMs]'s clock. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The clock [failedAt] is kept on; a test substitutes its own. */
    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
    private val _inFlight = MutableStateFlow<Map<String, InFlightSolve>>(emptyMap())

    /** Visible latency budget: every solve currently running, with its start time and waiter count. */
    val inFlight: StateFlow<Map<String, InFlightSolve>> = _inFlight.asStateFlow()

    /**
     * Runs [solve] for [host] under the host's lock and returns its result.
     *
     * A caller arriving while another solve for the same host is running blocks until that solve
     * finishes (up to [waitBudget]), then calls [alreadySolved]; when that returns true the caller
     * skips its own solve and this returns null. Called from OkHttp dispatcher threads, so it
     * blocks rather than suspends.
     *
     * @throws CloudflareBudgetExceededException when the host's lock is not acquired within [waitBudget].
     * @throws CloudflareSolveRecentlyFailedException when the host's last solve failed less than
     *   [FAILURE_MEMORY] ago and [alreadySolved] is false, without waiting and without solving.
     */
    fun <T> coalesce(
        host: String,
        waitBudget: Duration,
        alreadySolved: () -> Boolean,
        solve: () -> T,
    ): T? {
        // Before queueing as well as after: a caller arriving just after a failure need not wait for
        // the lock only to be told the same thing.
        failFastIfRecentlyFailed(host, alreadySolved)
        val lock = hostLocks.getOrPut(host) { ReentrantLock(true) }
        val waiters = hostWaiters.getOrPut(host) { AtomicInteger() }

        val queued = lock.isLocked
        if (queued) {
            waiters.incrementAndGet()
            publishWaiterCount(host, waiters.get())
        }
        val acquired = try {
            lock.tryLock(waitBudget.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CloudflareBudgetExceededException(host, waitBudget, e)
        } finally {
            if (queued) {
                publishWaiterCount(host, waiters.decrementAndGet())
            }
        }
        if (!acquired) throw CloudflareBudgetExceededException(host, waitBudget)

        try {
            if (alreadySolved()) return null
            failFastIfRecentlyFailed(host) { false }
            _inFlight.update {
                it + (
                    host to InFlightSolve(
                        host = host,
                        startedAtElapsedMs = SystemClock.elapsedRealtime(),
                        waiters = waiters.get(),
                        budgetMs = SOLVER_BUDGET.inWholeMilliseconds,
                    )
                )
            }
            return try {
                solve().also { failedAt.remove(host) }
            } catch (e: InterruptedException) {
                // A cancelled call, not a failed solve: the next caller tries again.
                throw e
            } catch (e: Exception) {
                failedAt[host] = elapsedRealtimeMs()
                throw e
            } finally {
                _inFlight.update { it - host }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun failFastIfRecentlyFailed(host: String, alreadySolved: () -> Boolean) {
        val at = failedAt[host] ?: return
        val since = elapsedRealtimeMs() - at
        if (since >= FAILURE_MEMORY.inWholeMilliseconds) {
            failedAt.remove(host, at)
            return
        }
        // A clearance that arrived since (a person solved the page in the app's WebView) still counts.
        if (alreadySolved()) return
        throw CloudflareSolveRecentlyFailedException(host)
    }

    private fun publishWaiterCount(host: String, count: Int) {
        _inFlight.update { current ->
            val solve = current[host] ?: return@update current
            current + (host to solve.copy(waiters = count))
        }
    }

    data class InFlightSolve(
        val host: String,
        val startedAtElapsedMs: Long,
        val waiters: Int,
        val budgetMs: Long,
    )

    companion object {
        /** What the solving caller itself waits for the WebView, matching the vendored latch. */
        val SOLVER_BUDGET: Duration = 30.seconds

        /** A waiter outlives the solver by enough scheduling slack not to time out just under it. */
        val WAITER_BUDGET: Duration = 45.seconds

        /** How long a failed solve makes every caller for its host fail at once. */
        val FAILURE_MEMORY: Duration = 1.minutes
    }
}

/** Thrown when a caller gave up waiting for another caller's solve of the same host. */
class CloudflareBudgetExceededException(
    val host: String,
    val budget: Duration,
    cause: Throwable? = null,
) : IOException("waited $budget for a challenge solve on $host", cause)

/**
 * Thrown instead of a solve when the host's last solve failed moments ago: the challenge wants a
 * person, and another headless attempt would only fail the same way after the same wait.
 */
class CloudflareSolveRecentlyFailedException(val host: String) :
    IOException("a challenge solve on $host failed moments ago")
