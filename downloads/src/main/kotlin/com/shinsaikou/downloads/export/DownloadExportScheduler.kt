package com.shinsaikou.downloads.export

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Starts and observes the one unique [DownloadExportWorker]. WorkManager keeps the request, so a run
 * cut short by the process dying is started again, and copies only what the first did not.
 */
@SingleIn(AppScope::class)
@Inject
class DownloadExportScheduler(private val context: Context) {

    /** A run in flight ([done] of [total] files, 0 of 0 while it lists them), or the last one's end. */
    data class State(
        val running: Boolean,
        val done: Int = 0,
        val total: Int = 0,
        val last: Last? = null,
    )

    /**
     * The last finished run: its counts, or [unreachable] when there was no folder to write to, or
     * [stopped] when it failed outright partway.
     */
    data class Last(val copied: Int, val skipped: Int, val failed: Int, val unreachable: Boolean, val stopped: Boolean)

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /** Enqueues an export unless one is already waiting or running, which is left to finish. */
    fun start() {
        val request = OneTimeWorkRequestBuilder<DownloadExportWorker>()
            .addTag(DownloadExportWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(DownloadExportWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    val state: Flow<State>
        get() = workManager.getWorkInfosForUniqueWorkFlow(DownloadExportWorker.WORK_NAME).map(::stateOf)

    companion object {
        fun stateOf(infos: List<WorkInfo>): State {
            val active = infos.firstOrNull { !it.state.isFinished }
            if (active != null) {
                return State(
                    running = true,
                    done = active.progress.getInt(DownloadExportWorker.KEY_DONE, 0),
                    total = active.progress.getInt(DownloadExportWorker.KEY_TOTAL, 0),
                )
            }
            val finished = infos.lastOrNull { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }
                ?: return State(running = false)
            val data = finished.outputData
            return State(
                running = false,
                last = Last(
                    copied = data.getInt(DownloadExportWorker.KEY_COPIED, 0),
                    skipped = data.getInt(DownloadExportWorker.KEY_SKIPPED, 0),
                    failed = data.getInt(DownloadExportWorker.KEY_FAILED, 0),
                    unreachable = data.getBoolean(DownloadExportWorker.KEY_UNREACHABLE, false),
                    stopped = data.getBoolean(DownloadExportWorker.KEY_STOPPED, false),
                ),
            )
        }
    }
}
