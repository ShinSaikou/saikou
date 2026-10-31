package com.shinsaikou.core.common.work

import androidx.work.CoroutineWorker
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Asks WorkManager to run this worker as a foreground service, with the notification from its
 * [CoroutineWorker.getForegroundInfo], and carries on as ordinary background work when the platform
 * refuses.
 *
 * From API 31 a foreground service cannot start while the app is in the background unless the app
 * is exempt from battery optimisation, and every worker here can start exactly then: a periodic
 * automatic run, a retry after the network returns, a run that starts the process itself.
 * WorkManager reports that refusal as `ForegroundServiceStartNotAllowedException`, which is an
 * [IllegalStateException]. Failing the run over it would drop the work for want of a notification,
 * so only that exception is caught; anything else is the worker's own failure. A coroutine
 * [CancellationException] is an [IllegalStateException] too, and a stop must still stop the run, so
 * it is rethrown.
 *
 * Returns whether the worker is now in the foreground, so a worker whose whole purpose is the
 * foreground (the sign-in hold) can say it was refused.
 */
suspend fun CoroutineWorker.setForegroundSafely(): Boolean {
    try {
        setForeground(getForegroundInfo())
        return true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        logcat(LogPriority.WARN) { "Could not enter the foreground, running in the background: ${e::class.simpleName}" }
        return false
    }
}
