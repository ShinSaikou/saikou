package com.shinsaikou.sync.drive

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long the Drive backend and its token refresh wait inside one run before a transient failure
 * propagates (phase 3 unit H, item 3): up to four attempts in all, [waits] of 1, 2 and 4 seconds
 * between them. After the last, the failure reaches `SyncWorker`, which retries it from 60 s.
 *
 * The graph builds the standard one; a test passes its own [sleep], which records the waits and
 * spends no time.
 */
class DriveBackoff(val waits: List<Duration>, private val sleep: suspend (Duration) -> Unit) {

    @Inject
    constructor() : this(WAITS, { delay(it) })

    /**
     * [block], again after each wait while it throws a failure [retryable] accepts; the failure of
     * the last attempt, or the first one [retryable] refuses, propagates. A cancel always propagates.
     */
    suspend fun <T> run(retryable: (Throwable) -> Boolean, block: suspend () -> T): T {
        for (wait in waits) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!retryable(e)) throw e
            }
            sleep(wait)
        }
        return block()
    }

    /** The wait after failed attempt [index] (from 0), for a caller that counts its own attempts. */
    suspend fun pause(index: Int) {
        sleep(waits[index])
    }

    companion object {
        /** The waits between the four attempts. */
        val WAITS: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds)
    }
}
