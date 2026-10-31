package com.shinsaikou.downloads.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import com.shinsaikou.domain.storage.FolderCopy
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.downloads.DownloadNotifier
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Hosts one [DownloadExport] run in the foreground, on [DownloadNotifier.CHANNEL_PROGRESS] with the
 * `dataSync` type the other long workers use, into the storage location's `downloads` folder. With
 * the location unreachable it copies nothing and says so in a notification that opens the download
 * settings, where the row picks the folder again; a run that fails outright says so the same way,
 * and the row starts it again, skipping what it copied. Its result is the counts, as output data, which
 * the Export downloads row shows.
 */
class DownloadExportWorker @AssistedInject constructor(
    context: Context,
    @Assisted params: WorkerParameters,
    private val export: DownloadExport,
    private val storageLocation: StorageLocation,
    private val notifier: DownloadNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        return try {
            setForegroundSafely()
            val target = withContext(Dispatchers.IO) { storageLocation.folder(StorageLocation.DOWNLOADS) }
            if (target == null) {
                notifier.showExportUnreachable()
                return Result.failure(workDataOf(KEY_UNREACHABLE to true))
            }
            val result = withContext(Dispatchers.IO) {
                export.run(target) { progress ->
                    setProgress(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                    notifier.showExportProgress(progress)
                }
            }
            notifier.cancelExportProgress()
            notifier.showExportFinished(result)
            Result.success(
                workDataOf(
                    KEY_COPIED to result.copied,
                    KEY_SKIPPED to result.skipped,
                    KEY_FAILED to result.failed,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "The downloads export failed" }
            notifier.showExportStopped()
            Result.failure(workDataOf(KEY_STOPPED to true))
        } finally {
            notifier.cancelExportProgress()
        }
    }

    private fun foregroundInfo(progress: FolderCopy.Progress?): ForegroundInfo {
        val notification = notifier.exportProgressNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(DownloadNotifier.ID_EXPORT_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(DownloadNotifier.ID_EXPORT_PROGRESS, notification)
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<DownloadExportWorker>

    companion object {
        const val WORK_NAME = "download-export"
        const val TAG = "download-export"

        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_COPIED = "copied"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_UNREACHABLE = "unreachable"
        const val KEY_STOPPED = "stopped"
    }
}
