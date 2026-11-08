package com.shinsaikou.extensionupdate

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The daily run of "Update extensions automatically" (Settings > Experimental), enqueued by
 * [ExtensionUpdateScheduler] and built by `SaikouWorkerFactory` (R8). It stays in the background:
 * a run that finds nothing posts nothing, so there is no progress notification either, and the
 * Extensions tab shows each install's step as it runs. A run that meets a restore under way tries
 * again after the backoff rather than installing beside it.
 */
class ExtensionUpdateWorker @AssistedInject constructor(
    // As SyncWorker: the factory assists only the parameters, so the context is a plain dependency.
    context: Context,
    @Assisted params: WorkerParameters,
    private val autoUpdate: ExtensionAutoUpdate,
    private val notifier: ExtensionUpdateNotifier,
    private val preferences: ExtensionsPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Turned off after this run was handed to the job scheduler: the cancel is on its way.
        if (!preferences.autoInstallUpdates.get()) return Result.success()
        return try {
            val outcome = autoUpdate.run()
            if (outcome.busy) return Result.retry()
            notifier.showFinished(outcome)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next day's run tries again; nothing was left half done, since each install is whole.
            logcat(LogPriority.ERROR, e) { "The automatic extension update run failed" }
            Result.failure()
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<ExtensionUpdateWorker>

    companion object {
        const val WORK_NAME = "extension-auto-update"
    }
}
