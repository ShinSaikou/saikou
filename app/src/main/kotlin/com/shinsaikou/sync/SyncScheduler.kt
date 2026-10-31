package com.shinsaikou.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything that enqueues or cancels a sync run (Q10), under two unique names: [WORK_NAME_PERIODIC]
 * and [WORK_NAME_ONCE]. Every request needs a connected network, an unmetered one for the automatic
 * runs while [SyncWorkPreferences.wifiOnly] is on, and nothing is enqueued while no backend is
 * configured. Runs never overlap however the two names fire, because the engine holds one
 * lock per process.
 *
 * [WorkManager] is taken lazily: `SaikouApp` resolves the triggers on the main thread, and nothing
 * there should initialize WorkManager before a trigger fires.
 */
@SingleIn(AppScope::class)
@Inject
class SyncScheduler(
    private val workManager: Lazy<WorkManager>,
    private val syncPreferences: SyncPreferences,
    private val workPreferences: SyncWorkPreferences,
) {

    private val configured: Boolean
        get() = syncPreferences.backendId.get().isNotEmpty()

    /**
     * Arms the periodic run at the configured interval, or cancels it when the interval is off or no
     * backend is configured. [ExistingPeriodicWorkPolicy.UPDATE] keeps the schedule of a request
     * whose interval did not change, and takes a new network constraint. Idempotent; called at
     * start-up, at sign-in, and when the interval or Wi-Fi only changes. A one-time run already
     * waiting keeps the constraint it was enqueued with until [rearm] replaces it.
     */
    fun setup() {
        val hours = workPreferences.intervalHours.get()
        if (!configured || hours <= 0) {
            workManager.value.cancelUniqueWork(WORK_NAME_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(hours.toLong(), TimeUnit.HOURS, FLEX_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .setConstraints(constraints(userStarted = false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.value.enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * An automatic trigger (app start, the move to the background, the reader closing): one run
     * after [delay], unless a [WORK_NAME_ONCE] request is already waiting or running
     * ([ExistingWorkPolicy.KEEP]). Nothing while no backend is configured.
     */
    fun syncSoon(delay: Duration = Duration.ZERO) {
        if (!configured) return
        workManager.value.enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.KEEP, oneTimeRequest(delay))
    }

    /**
     * Sync now. [ExistingWorkPolicy.REPLACE] when no [WORK_NAME_ONCE] run is running, so a tap
     * replaces a waiting delayed request and runs at once; [ExistingWorkPolicy.KEEP] while one runs,
     * whose engine pushes until the log is empty before it releases (Q10). Returns false, enqueuing
     * nothing, while no backend is configured. The request carries [KEY_USER_STARTED], so its failure
     * shows in Sync settings and posts no notice ([SyncNotices.syncFailed]).
     */
    suspend fun syncNow(): Boolean {
        if (!configured) return false
        val running = workManager.value.getWorkInfosForUniqueWorkFlow(WORK_NAME_ONCE).first()
            .any { it.state == WorkInfo.State.RUNNING }
        val policy = if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        workManager.value.enqueueUniqueWork(WORK_NAME_ONCE, policy, oneTimeRequest(Duration.ZERO, userStarted = true))
        return true
    }

    /**
     * Replaces an automatic [WORK_NAME_ONCE] request still waiting under the network Wi-Fi only no
     * longer asks for, with one that runs as soon as the right network is there. Left alone, it
     * would sync on mobile data after the switch was turned on, or wait for Wi-Fi after it was
     * turned off, and [ExistingWorkPolicy.KEEP] turns every trigger away while it waits. Sync now's
     * request runs on any connection either way, and a run under way is left to finish.
     */
    suspend fun rearm() {
        if (!configured) return
        val wanted = constraints(userStarted = false).requiredNetworkType
        val stale = workManager.value.getWorkInfosForUniqueWorkFlow(WORK_NAME_ONCE).first().any {
            it.state == WorkInfo.State.ENQUEUED &&
                TAG_USER_STARTED !in it.tags &&
                it.constraints.requiredNetworkType != wanted
        }
        if (stale) {
            val request = oneTimeRequest(Duration.ZERO)
            workManager.value.enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /** Sign-out: cancels both names, a run in progress included. */
    fun cancel() {
        workManager.value.cancelUniqueWork(WORK_NAME_PERIODIC)
        workManager.value.cancelUniqueWork(WORK_NAME_ONCE)
    }

    private fun oneTimeRequest(delay: Duration, userStarted: Boolean = false) = OneTimeWorkRequestBuilder<SyncWorker>()
        .addTag(TAG)
        .setConstraints(constraints(userStarted))
        .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .apply {
            if (userStarted) {
                setInputData(workDataOf(KEY_USER_STARTED to true))
                addTag(TAG_USER_STARTED)
            }
        }
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Sync now is the owner's own choice, so it runs on any connection, as a manual library update does. */
    private fun constraints(userStarted: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (!userStarted && workPreferences.wifiOnly.get()) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .build()

    companion object {
        const val TAG = "sync"
        const val WORK_NAME_PERIODIC = "sync-periodic"
        const val WORK_NAME_ONCE = "sync-once"

        /** True in the input of a run the owner started with Sync now; absent from every other. */
        const val KEY_USER_STARTED = "user_started"

        /** The tag of Sync now's request, which [rearm] reads off a waiting request's info. */
        const val TAG_USER_STARTED = "sync-user-started"

        /** How long after the reader closes its run starts (Q10). */
        val READER_CLOSED_DELAY: Duration = 30.seconds

        private const val FLEX_MINUTES = 10L

        /** The first retry after a transient failure; WorkManager doubles it each time, to five hours. */
        private const val BACKOFF_SECONDS = 60L
    }
}
