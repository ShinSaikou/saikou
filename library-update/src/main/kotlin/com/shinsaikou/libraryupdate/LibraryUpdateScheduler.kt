package com.shinsaikou.libraryupdate

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.shinsaikou.domain.preferences.LibraryPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Everything that enqueues, cancels or observes a library update run. `:app` calls [setup] at
 * start-up and [startNow] from the library screen.
 */
@SingleIn(AppScope::class)
@Inject
class LibraryUpdateScheduler(
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
) {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /** Arms or cancels the periodic run from the current preferences. Idempotent. */
    fun setup() {
        LibraryUpdateWorker.setupPeriodic(workManager, libraryPreferences)
    }

    /**
     * Enqueues a manual run. Returns false when a run is already going, which is when the KEEP
     * policy would silently drop the request.
     *
     * [mangaIds], when given, are the entries to update in place of the categories, at most
     * [LibraryUpdateWorker.MAX_MANGA_IDS] of them: the ids travel in the request's input data,
     * which WorkManager caps, so a caller with more says so rather than starting a run.
     */
    suspend fun startNow(
        categoryId: Long? = null,
        refreshMetadata: Boolean = false,
        mangaIds: Collection<Long>? = null,
    ): Boolean {
        require(mangaIds == null || mangaIds.size <= LibraryUpdateWorker.MAX_MANGA_IDS) {
            "${mangaIds?.size} entries are more than one run's input can name"
        }
        if (isAnyRunRunning()) return false

        val input = workDataOf(
            LibraryUpdateWorker.KEY_CATEGORY to (categoryId ?: LibraryUpdateWorker.CATEGORY_ALL),
            LibraryUpdateWorker.KEY_REFRESH_METADATA to refreshMetadata,
            LibraryUpdateWorker.KEY_MANGA_IDS to mangaIds?.toLongArray(),
        )
        val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
            .addTag(LibraryUpdateWorker.TAG)
            .addTag(LibraryUpdateWorker.WORK_NAME_MANUAL)
            .setInputData(input)
            .build()
        workManager.enqueueUniqueWork(LibraryUpdateWorker.WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
        return true
    }

    /**
     * Cancels the run going now, automatic or manual, and leaves the automatic schedule armed; see
     * [LibraryUpdateWorker.stop].
     */
    suspend fun stop() {
        LibraryUpdateWorker.stop(workManager)
    }

    val isRunning: Flow<Boolean>
        get() = workManager.getWorkInfosByTagFlow(LibraryUpdateWorker.TAG)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    /** Whether a manual run is going now; an automatic run that comes due meanwhile waits for it. */
    suspend fun isManualRunning(): Boolean = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(LibraryUpdateWorker.WORK_NAME_MANUAL).get()
            .any { it.state == WorkInfo.State.RUNNING }
    }

    // WorkManager answers from its own database, so the blocking future is read off the caller's
    // thread. There is no coroutine-aware query for a tag in work-runtime 2.11.
    private suspend fun isAnyRunRunning(): Boolean = withContext(Dispatchers.IO) {
        workManager.getWorkInfosByTag(LibraryUpdateWorker.TAG).get()
            .any { it.state == WorkInfo.State.RUNNING }
    }
}
