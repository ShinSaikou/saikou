package com.shinsaikou.downloads.adoption

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hippo.unifile.UniFile
import com.shinsaikou.domain.preferences.StoragePreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Starts and observes the one unique [AdoptionWorker]. WorkManager persists the request, so a run
 * the process death cut short is started again by WorkManager itself, over the tree
 * [StoragePreferences.adoptionTree] still names.
 */
@SingleIn(AppScope::class)
@Inject
class AdoptionScheduler(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    /** Where a run stands: not running, walking the tree (a total of 0), or [done] of [total] chapters. */
    data class State(val running: Boolean, val done: Int = 0, val total: Int = 0)

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    /**
     * Records [treeUri] as the tree to adopt from and enqueues the worker, unless a run is already
     * waiting or running, which is left to finish over the tree it was started with. Returns whether
     * a run was started.
     */
    suspend fun start(treeUri: String): Boolean {
        val active = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(AdoptionWorker.WORK_NAME).get().any { !it.state.isFinished }
        }
        if (active) return false

        storagePreferences.adoptionTree.set(treeUri)
        val request = OneTimeWorkRequestBuilder<AdoptionWorker>()
            .addTag(AdoptionWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(AdoptionWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return true
    }

    /**
     * Whether [treeUri], the tree an earlier run read, can be read again without the picker. The
     * worker gave the persisted read grant back when that run ended, so it is taken again here,
     * which succeeds only while a grant from the picker still holds; then the tree has to open as a
     * folder. False sends the owner to the picker.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reopen(treeUri: String): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(treeUri)
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            UniFile.fromUri(context, uri)?.isDirectory == true
        } catch (_: Exception) {
            // A provider that refuses the query for want of a grant.
            false
        }
    }

    val state: Flow<State>
        get() = workManager.getWorkInfosForUniqueWorkFlow(AdoptionWorker.WORK_NAME).map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished }
            if (info == null) {
                State(running = false)
            } else {
                State(
                    running = true,
                    done = info.progress.getInt(AdoptionWorker.KEY_DONE, 0),
                    total = info.progress.getInt(AdoptionWorker.KEY_TOTAL, 0),
                )
            }
        }
}
