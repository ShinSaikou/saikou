package com.shinsaikou.libraryupdate

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Which of one run's progress reports go out (phase 5 unit SC5). [FlatLibraryUpdate] reports once
 * per entry started, which at 40,000 entries was 40,000 notification posts and 40,000 WorkManager
 * progress writes, and Android sheds notification updates past a few a second anyway. The worker
 * passes every report through one of these, so the notification and the work data stay in step.
 *
 * A report goes out when it is the run's first, when [INTERVAL] has passed since the last one sent,
 * when `done` has moved by a hundredth of `total` (at least one entry) since the last one sent, or
 * when it is the run's last. The last is told by counting: the pass reports exactly once per entry
 * it starts, so the `total`-th report is the last entry starting, whatever `done` says then (with
 * several entries in flight it is short of `total - 1`).
 *
 * One per run. Not thread safe: the pass calls `progress` from one coroutine at a time.
 */
internal class ProgressCadence(private val timeSource: TimeSource) {

    /** Reports seen, sent or not: the entries started so far. */
    private var started = 0

    private var lastSentAt: TimeMark? = null
    private var lastSentDone = 0

    fun shouldSend(progress: FlatLibraryUpdate.Progress): Boolean {
        started++
        val lastAt = lastSentAt
        val send = lastAt == null ||
            started >= progress.total ||
            lastAt.elapsedNow() >= INTERVAL ||
            progress.done - lastSentDone >= step(progress.total)
        if (send) {
            lastSentAt = timeSource.markNow()
            lastSentDone = progress.done
        }
        return send
    }

    private fun step(total: Int): Int = (total / STEPS).coerceAtLeast(1)

    companion object {
        val INTERVAL = 500.milliseconds

        /** A report each time `done` moves by `total / STEPS`. */
        const val STEPS = 100
    }
}
