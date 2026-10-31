package com.shinsaikou.backup.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shinsaikou.backup.BackupIntents
import com.shinsaikou.backup.BackupNotifier
import com.shinsaikou.backup.importer.BackupImportWorker
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import com.shinsaikou.domain.preferences.BackupPreferences
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.sink
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Clock

/**
 * Writes one `.tachibk`. A manual run writes to the document the user picked; an automatic run
 * writes through [AutomaticBackupFiles] into the configured directory, which keeps the newest
 * [BackupPreferences.keptBackups] of its own files.
 *
 * An automatic run never competes with a restore: it retries instead, because exporting a database
 * that is halfway through an import would capture a state the owner never had.
 */
class BackupExportWorker @AssistedInject constructor(
    context: Context,
    @Assisted params: WorkerParameters,
    private val exporter: BackupExporter,
    private val notifier: BackupNotifier,
    private val intents: BackupIntents,
    private val backupPreferences: BackupPreferences,
    private val automaticFiles: AutomaticBackupFiles,
) : CoroutineWorker(context, params) {

    private val automatic: Boolean
        get() = WORK_NAME_AUTO in tags

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    override suspend fun doWork(): Result {
        if (automatic && isImportRunning()) return Result.retry()

        val options = inputData.getBooleanArray(KEY_OPTIONS)
            ?.let(BackupOptions::fromBooleanArray)
            ?: BackupOptions()

        return try {
            setForegroundSafely()
            val written = exporter.build(options).use { staged ->
                if (automatic) {
                    val now = Clock.System.now().toEpochMilliseconds()
                    val keep = backupPreferences.keptBackups.get()
                        .coerceIn(BackupPreferences.KEPT_BACKUPS_RANGE)
                    automaticFiles.write(now, keep) { file -> write(staged, file.uri) }.uri
                } else {
                    manualTarget().also { write(staged, it) }
                }
            }

            if (automatic) {
                backupPreferences.lastAutoBackupTimestamp.set(Clock.System.now().toEpochMilliseconds())
            } else {
                notifier.showBackupComplete(intents.shareBackup(written))
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "The backup export failed" }
            notifier.showBackupError()
            Result.failure()
        } finally {
            notifier.cancelBackupProgress()
        }
    }

    private suspend fun write(staged: StagedBackup, target: Uri) = withContext(Dispatchers.IO) {
        applicationContext.contentResolver.openOutputStream(target, "wt").use { stream ->
            checkNotNull(stream) { "The backup file could not be opened for writing" }
            exporter.write(staged, stream.sink())
        }
    }

    private fun manualTarget(): Uri =
        checkNotNull(inputData.getString(KEY_URI)?.let(Uri::parse)) { "A manual export needs a target document" }

    private suspend fun isImportRunning(): Boolean = withContext(Dispatchers.IO) {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWork(BackupImportWorker.WORK_NAME)
            .get()
            .any { it.state == WorkInfo.State.RUNNING }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification = notifier.backupProgressNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                BackupNotifier.ID_BACKUP_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(BackupNotifier.ID_BACKUP_PROGRESS, notification)
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<BackupExportWorker>

    companion object {
        const val TAG = "backup-export"
        const val WORK_NAME_MANUAL = "backup-export"
        const val WORK_NAME_AUTO = "backup-export-auto"

        /** A document URI from the system create-document flow. Manual runs only. */
        const val KEY_URI = "uri"

        /** `BackupOptions.asBooleanArray()`. */
        const val KEY_OPTIONS = "options"
    }
}
