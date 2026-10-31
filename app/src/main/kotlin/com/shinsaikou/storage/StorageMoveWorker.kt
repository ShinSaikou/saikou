package com.shinsaikou.storage

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

/**
 * Hosts one [StorageMove] in the foreground. Built by `SaikouWorkerFactory` (R8). A stop leaves every
 * file where it was (the app or the previous location), in the location, or in both, and WorkManager starts the request again after the
 * process died; that run skips what is already there.
 */
class StorageMoveWorker @AssistedInject constructor(
    context: Context,
    @Assisted params: WorkerParameters,
    private val move: StorageMove,
    private val notifier: StorageNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotification(null)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(StorageNotifier.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(StorageNotifier.ID_PROGRESS, notification)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result = try {
        setForegroundSafely()
        val moved = withContext(Dispatchers.IO) {
            move.run { progress ->
                setProgress(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                notifier.showProgress(progress)
            }
        }
        notifier.cancelProgress()
        notifier.showFinished(moved.files, stillIn = moved.previousName.takeIf { moved.failedInPrevious > 0 })
        Result.success(workDataOf(KEY_FAILED to moved.files.failed))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat(LogPriority.ERROR, e) { "The move to the storage location failed" }
        notifier.cancelProgress()
        notifier.showStopped()
        Result.failure()
    } finally {
        notifier.cancelProgress()
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<StorageMoveWorker>

    companion object {
        const val WORK_NAME = "storage-move"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
    }
}
