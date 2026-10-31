package com.shinsaikou.tracking

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shinsaikou.core.common.coroutines.Dispatchers
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * `:tracking`'s [DelayedTrackingScheduler] over WorkManager (phase 3 unit T1): one-time runs of
 * [DelayedTrackingUpdateWorker] under the unique name [WORK_NAME], each needing a connected network.
 *
 * A run waiting to start ([WorkInfo.State.ENQUEUED], a retry's backoff included, or
 * [WorkInfo.State.BLOCKED]) will read the store after the push the caller just recorded, so nothing
 * is enqueued. With none waiting, [ExistingWorkPolicy.KEEP]. A run already [WorkInfo.State.RUNNING]
 * may have read the store before the push landed, and KEEP would drop the request, so a new run is
 * appended behind it ([ExistingWorkPolicy.APPEND_OR_REPLACE]) and reads the store when it starts.
 * That is how a push recorded while the worker runs still gets sent, and why at most one run waits.
 *
 * [WorkManager] is taken lazily, as sync's scheduler takes it.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WorkManagerDelayedTrackingScheduler(
    private val workManager: Lazy<WorkManager>,
    private val dispatchers: Dispatchers,
) : DelayedTrackingScheduler {

    override suspend fun schedule() {
        withContext(dispatchers.io) {
            val runs = workManager.value.getWorkInfosForUniqueWork(WORK_NAME).get()
            if (runs.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
                return@withContext
            }
            val policy = if (runs.any { it.state == WorkInfo.State.RUNNING }) {
                ExistingWorkPolicy.APPEND_OR_REPLACE
            } else {
                ExistingWorkPolicy.KEEP
            }
            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateWorker>()
                .addTag(TAG)
                .setConstraints(CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            workManager.value.enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }

    companion object {
        const val TAG = "tracking"
        const val WORK_NAME = "delayed-tracking-update"

        /** Upstream's first retry; WorkManager doubles it each time. */
        private const val BACKOFF_MINUTES = 5L

        private val CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
