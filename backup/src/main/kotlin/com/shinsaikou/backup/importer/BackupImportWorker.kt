package com.shinsaikou.backup.importer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shinsaikou.backup.BackupImportCancelReceiver
import com.shinsaikou.backup.BackupNotifier
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.backup.BackupDecodeException
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Hosts one restore as a foreground worker. The file is copied into app-private storage before
 * anything is decoded, so a revoked storage grant cannot break a resume, and so the checkpoint can
 * be keyed on bytes the app controls rather than on a URI that may no longer open.
 */
class BackupImportWorker @AssistedInject constructor(
    // WorkerInstanceFactory assists only the parameters; the application context is a plain graph
    // dependency (architecture carry-over: an assisted Context does not compile).
    context: Context,
    @Assisted params: WorkerParameters,
    private val importer: BackupImporter,
    private val checkpoints: ImportCheckpointStore,
    private val notifier: BackupNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        val flags = inputData.getBooleanArray(KEY_OPTIONS) ?: return Result.failure()
        val options = RestoreOptions.fromBooleanArray(flags)

        return try {
            val file = withContext(Dispatchers.IO) { copyToPrivateStorage(uri) }
            setForegroundSafely()

            val report = importer.import(file, options) { progress ->
                setProgressAsync(
                    workDataOf(
                        PROGRESS_PHASE to progress.phase.name,
                        PROGRESS_DONE to progress.done,
                        PROGRESS_TOTAL to progress.total,
                        PROGRESS_TITLE to progress.currentTitle.orEmpty(),
                    ),
                )
                notifier.showRestoreProgress(progress, cancelIntent())
            }

            if (report.cancelled) notifier.showRestoreCancelled() else notifier.showRestoreComplete(report)
            Result.success()
        } catch (_: CancellationException) {
            // A user cancel is not a failure. The checkpoint stays, so the next tap resumes.
            notifier.showRestoreCancelled()
            Result.success()
        } catch (e: BackupDecodeException) {
            // The file itself will not read, so a retry would stop at the same entry every time.
            logcat(LogPriority.ERROR, e) { "The backup file could not be decoded" }
            notifier.showRestoreFailed()
            Result.failure()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "The backup import failed" }
            // A checkpoint means there is something to come back to, so let WorkManager retry, a few
            // times: a failure that outlasts the backoff (a full storage, a heap the file outgrows)
            // would otherwise run the restore again forever. The checkpoint stays for a later try.
            if (checkpoints.load() != null && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                notifier.showRestoreFailed()
                Result.failure()
            }
        } finally {
            notifier.cancelRestoreProgress()
        }
    }

    /**
     * Copies the picked file and hashes it in the same pass. A copy whose hash already matches the
     * checkpoint is kept, which is what makes a resume after process death cost seconds.
     */
    private fun copyToPrivateStorage(uri: Uri): File {
        val target = checkpoints.importFile
        val expected = checkpoints.load()?.fileSha256
        if (expected != null && target.exists() && target.sha256Hex() == expected) return target

        target.parentFile?.mkdirs()
        applicationContext.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "The backup file could not be opened" }
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private fun foregroundInfo(progress: ImportProgress?): ForegroundInfo {
        val notification = notifier.restoreProgressNotification(progress, cancelIntent())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                BackupNotifier.ID_RESTORE_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(BackupNotifier.ID_RESTORE_PROGRESS, notification)
        }
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        0,
        Intent(applicationContext, BackupImportCancelReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<BackupImportWorker>

    companion object {
        const val WORK_NAME = "backup-import"

        const val KEY_URI = "uri"

        /** `RestoreOptions.asBooleanArray()`. */
        const val KEY_OPTIONS = "options"

        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_TITLE = "title"

        /** Retries after a failure that left a checkpoint; with the default backoff, about 15 minutes. */
        const val MAX_RETRIES = 5

        private fun String.toUri(): Uri = Uri.parse(this)
    }
}
