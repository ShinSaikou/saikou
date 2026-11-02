package com.shinsaikou.backup.export

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.shinsaikou.backup.BackupRun
import com.shinsaikou.domain.preferences.BackupPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Everything that enqueues, arms or observes an export. */
@SingleIn(AppScope::class)
@Inject
class BackupScheduler(
    private val context: Context,
    private val backupPreferences: BackupPreferences,
) {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /** Arms or cancels the periodic export from the current preferences. Idempotent. */
    fun setup() {
        val interval = backupPreferences.backupInterval.get()
        if (interval <= 0) {
            workManager.cancelUniqueWork(BackupExportWorker.WORK_NAME_AUTO)
            return
        }

        val request = PeriodicWorkRequestBuilder<BackupExportWorker>(
            interval.toLong(),
            TimeUnit.HOURS,
            FLEX_MINUTES,
            TimeUnit.MINUTES,
        )
            .addTag(BackupExportWorker.TAG)
            .addTag(BackupExportWorker.WORK_NAME_AUTO)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            BackupExportWorker.WORK_NAME_AUTO,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** [uri] is a document the user just created through the system picker. */
    fun exportNow(uri: Uri, options: BackupOptions) {
        val request = OneTimeWorkRequestBuilder<BackupExportWorker>()
            .addTag(BackupExportWorker.TAG)
            .addTag(BackupExportWorker.WORK_NAME_MANUAL)
            .setInputData(
                workDataOf(
                    BackupExportWorker.KEY_URI to uri.toString(),
                    BackupExportWorker.KEY_OPTIONS to options.asBooleanArray(),
                ),
            )
            .build()
        workManager.enqueueUniqueWork(BackupExportWorker.WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
    }

    /** The newest manual export, queued, working or ended; null when WorkManager keeps none. */
    val manualExportRun: Flow<BackupRun?>
        get() = workManager.getWorkInfosForUniqueWorkFlow(BackupExportWorker.WORK_NAME_MANUAL)
            .map(BackupRun::of)

    private companion object {
        const val FLEX_MINUTES = 10L
        const val BACKOFF_MINUTES = 10L
    }
}
