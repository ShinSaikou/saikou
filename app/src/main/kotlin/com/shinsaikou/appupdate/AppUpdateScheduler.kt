package com.shinsaikou.appupdate

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Enqueues the [AppUpdateWorker] that downloads and installs one release. */
@SingleIn(AppScope::class)
@Inject
class AppUpdateScheduler(private val workManager: Lazy<WorkManager>) {

    /**
     * Waits for a network and keeps a run for the same version that is already waiting or running,
     * so the download survives leaving the screen and a second tap does not start a second one.
     */
    fun start(update: AppUpdate) {
        workManager.value.enqueueUniqueWork(
            AppUpdateWorker.workName(update.version),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(AppUpdateWorker.inputOf(update))
                .build(),
        )
    }
}
