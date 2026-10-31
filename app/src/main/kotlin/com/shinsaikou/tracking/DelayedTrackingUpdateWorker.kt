package com.shinsaikou.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Sends the tracker pushes the reader and the chapter list recorded (phase 3 unit T1), modeled on
 * upstream's `DelayedTrackingUpdateJob`: one pass of [DelayedTrackingUpdate], enqueued by
 * [WorkManagerDelayedTrackingScheduler] with a network constraint and built by `SaikouWorkerFactory`
 * (R8).
 *
 * A pass that leaves an entry for a retry is [Result.retry], which WorkManager backs off from five
 * minutes, doubling. After upstream's three retries the work fails and the entries stay in the store:
 * the next recorded push enqueues a run that sends them too. A pass that throws is retried the same
 * way. A cancel propagates.
 */
class DelayedTrackingUpdateWorker @AssistedInject constructor(
    // WorkerInstanceFactory in :core:common assists only the parameters; the application context
    // comes from the graph, so it is a plain dependency here.
    context: Context,
    @Assisted params: WorkerParameters,
    private val update: DelayedTrackingUpdate,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (runAttemptCount > MAX_RETRIES) return Result.failure()
        return try {
            if (update.run()) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "The delayed tracking update failed" }
            Result.retry()
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<DelayedTrackingUpdateWorker>

    companion object {
        /** Upstream's `runAttemptCount > 3`. */
        const val MAX_RETRIES = 3
    }
}
