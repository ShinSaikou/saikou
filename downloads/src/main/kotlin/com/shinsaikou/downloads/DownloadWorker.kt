package com.shinsaikou.downloads

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.TimeSource

/**
 * Hosts one [DownloadEngine] run in the foreground, on [DownloadNotifier.CHANNEL_PROGRESS] with the
 * `dataSync` type the library update and backup workers use. Before downloading it runs
 * [DownloadManifestSweep], which is decision Q7's "when the worker starts".
 *
 * A stop (a pause, a lost network, the system reclaiming the process) cancels the run; the queue
 * rows and the finished pages stay, and the next run picks them up.
 */
class DownloadWorker @AssistedInject constructor(
    // WorkerInstanceFactory in :core:common assists only the parameters; the application context
    // comes from the graph, so it is a plain dependency here.
    context: Context,
    @Assisted params: WorkerParameters,
    private val engine: DownloadEngine,
    private val sweep: DownloadManifestSweep,
    private val notifier: DownloadNotifier,
    private val timeSource: TimeSource,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        return try {
            // Inside the try, so the finally below clears the progress notification whatever
            // happens from here on, a stop while entering the foreground included.
            setForegroundSafely()
            val outcome = withContext(Dispatchers.IO) {
                try {
                    sweep.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The sweep is housekeeping; a failure in it must not hold the queue back.
                    logcat(LogPriority.ERROR, e) { "The download manifest sweep failed" }
                }
                val cadence = DownloadProgressCadence(timeSource)
                engine.run { progress -> if (cadence.shouldSend(progress)) notifier.showProgress(progress) }
            }
            notifier.showErrors(outcome.failed)
            if (outcome.storageFull) notifier.showStorageFull()
            // The request waits out its backoff and its network constraint, then runs the rest.
            if (outcome.interrupted) Result.retry() else Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "The download run failed" }
            Result.failure()
        } finally {
            notifier.cancelProgress()
        }
    }

    private fun foregroundInfo(progress: DownloadEngine.Progress?): ForegroundInfo {
        val notification = notifier.progressNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifier.ID_DOWNLOAD_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(DownloadNotifier.ID_DOWNLOAD_PROGRESS, notification)
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<DownloadWorker>

    companion object {
        const val TAG = "download-queue"
        const val WORK_NAME = "download-queue"
    }
}
