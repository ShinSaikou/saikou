package com.shinsaikou.extensionupdate

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Keeps the daily [ExtensionUpdateWorker] in step with "Update extensions automatically": armed
 * while the switch is on, cancelled while it is off. [ExistingPeriodicWorkPolicy.KEEP], so an app
 * start finds the schedule it left and does not move the next run.
 *
 * [WorkManager] is taken lazily, as [com.shinsaikou.sync.SyncScheduler] takes it.
 */
@SingleIn(AppScope::class)
@Inject
class ExtensionUpdateScheduler(
    private val workManager: Lazy<WorkManager>,
    private val preferences: ExtensionsPreferences,
) {

    /**
     * Applies the switch now and on every later value, whoever writes it, so the settings row needs
     * no hook of its own. `SaikouApp` calls this once, with the app's scope.
     */
    fun followSetting(scope: CoroutineScope) {
        preferences.autoInstallUpdates.changes()
            .distinctUntilChanged()
            .onEach { on ->
                try {
                    if (on) arm() else workManager.value.cancelUniqueWork(ExtensionUpdateWorker.WORK_NAME)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Could not schedule the automatic extension updates" }
                }
            }
            .launchIn(scope)
    }

    private fun arm() {
        // Wi-Fi or another unmetered network, as the switch's text promises; the battery and storage
        // limits keep a run of several downloads off a device that is short of either.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.value.enqueueUniquePeriodicWork(ExtensionUpdateWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val INTERVAL_HOURS = 24L

        /** The first retry after a run met a restore; WorkManager doubles it each time, to five hours. */
        const val BACKOFF_MINUTES = 15L
    }
}
