package com.shinsaikou.storage

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Starts and observes the one unique [StorageMoveWorker]. */
@SingleIn(AppScope::class)
@Inject
class StorageMoveScheduler(private val context: Context) {

    /** A move in flight, [done] of [total] files (0 of 0 while it counts them). */
    data class State(val running: Boolean, val done: Int = 0, val total: Int = 0)

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /** Enqueues a move unless one is waiting or running, which is left to finish. */
    fun start() {
        workManager.enqueueUniqueWork(
            StorageMoveWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<StorageMoveWorker>().build(),
        )
    }

    val state: Flow<State>
        get() = workManager.getWorkInfosForUniqueWorkFlow(StorageMoveWorker.WORK_NAME).map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished }
            if (info == null) {
                State(running = false)
            } else {
                State(
                    running = true,
                    done = info.progress.getInt(StorageMoveWorker.KEY_DONE, 0),
                    total = info.progress.getInt(StorageMoveWorker.KEY_TOTAL, 0),
                )
            }
        }
}
