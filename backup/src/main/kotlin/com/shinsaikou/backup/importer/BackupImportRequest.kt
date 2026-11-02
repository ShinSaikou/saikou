package com.shinsaikou.backup.importer

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.shinsaikou.backup.BackupRun
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The restore screen's whole view of the import. [ExistingWorkPolicy.KEEP] on a unique name is what
 * stops a second tap from racing a run that is already resuming.
 */
@SingleIn(AppScope::class)
@Inject
class BackupImportRequest(private val context: Context) {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /**
     * Queues a restore of [uri] and says whether it was queued. False means a restore was already
     * queued or working: [ExistingWorkPolicy.KEEP] keeps that one and drops this one without a word,
     * so whether this request's id is the name's run afterwards is the only way to tell. Blocks, so
     * call it off the main thread.
     */
    fun start(uri: Uri, options: RestoreOptions): Boolean {
        val request = OneTimeWorkRequestBuilder<BackupImportWorker>()
            .addTag(BackupImportWorker.WORK_NAME)
            .setInputData(
                workDataOf(
                    BackupImportWorker.KEY_URI to uri.toString(),
                    BackupImportWorker.KEY_OPTIONS to options.asBooleanArray(),
                ),
            )
            .build()
        workManager.enqueueUniqueWork(BackupImportWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get()
        return workManager.getWorkInfosForUniqueWork(BackupImportWorker.WORK_NAME).get().any { it.id == request.id }
    }

    fun cancel() {
        workManager.cancelUniqueWork(BackupImportWorker.WORK_NAME)
    }

    /** The newest restore, queued, working or ended; null when WorkManager keeps none. */
    val run: Flow<BackupRun?>
        get() = infos.map(BackupRun::of)

    val progress: Flow<ImportProgress?>
        get() = infos.map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { info ->
                val phase = info.progress.getString(BackupImportWorker.PROGRESS_PHASE)
                    ?.let(::phaseOf)
                    ?: return@let null
                ImportProgress(
                    phase = phase,
                    done = info.progress.getInt(BackupImportWorker.PROGRESS_DONE, 0),
                    total = info.progress.getInt(BackupImportWorker.PROGRESS_TOTAL, 0),
                    currentTitle = info.progress.getString(BackupImportWorker.PROGRESS_TITLE)
                        ?.takeIf { it.isNotEmpty() },
                )
            }
        }

    private val infos: Flow<List<WorkInfo>>
        get() = workManager.getWorkInfosForUniqueWorkFlow(BackupImportWorker.WORK_NAME)

    /** An unknown phase name comes from a build that is no longer installed; it is not a crash. */
    private fun phaseOf(name: String): ImportPhase? = ImportPhase.entries.firstOrNull { it.name == name }
}
