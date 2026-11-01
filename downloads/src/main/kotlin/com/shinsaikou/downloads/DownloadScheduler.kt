package com.shinsaikou.downloads

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.domain.preferences.DownloadPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Starts, stops and observes the one unique [DownloadWorker]. WorkManager persists the request, so
 * a run cut short by process death, a force-stop or a lost network is started again by WorkManager
 * itself, and the rows left in `download_queue` are what it resumes (phase 2 decision Q4).
 *
 * A request's network constraint is "only download over Wi-Fi" as it was when the request was
 * enqueued. A request still waiting under the other value is replaced, by [start] and whenever the
 * setting changes ([followNetworkSetting]); left alone, a queue started on mobile data with the
 * setting on would wait for Wi-Fi after the owner turned it off.
 */
@SingleIn(AppScope::class)
@Inject
class DownloadScheduler(
    private val workManager: Lazy<WorkManager>,
    private val downloadPreferences: DownloadPreferences,
    private val dispatchers: Dispatchers,
    private val startMode: StartMode,
) {

    /**
     * Enqueues a run unless one is already waiting to start under the current setting. A running
     * worker is followed rather than replaced: KEEP would drop a request that lands after the
     * running worker read the queue for the last time, and those rows would sit until something
     * else started a run.
     */
    suspend fun start() {
        val wanted = requiredNetworkType()
        val unfinished = unfinishedWork()
        when {
            unfinished.any { it.constraints.requiredNetworkType != wanted } ->
                enqueue(wanted, ExistingWorkPolicy.REPLACE)
            unfinished.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> Unit
            else -> enqueue(wanted, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    /**
     * Replaces the request when its network constraint is not the setting's, and does nothing
     * otherwise: no request is made where there was none. A running worker under the old value is
     * replaced too, as a pause and a start, so turning the setting on stops a download on mobile
     * data and turning it off keeps a running one from waiting for Wi-Fi once Wi-Fi drops.
     */
    suspend fun rearm() {
        val wanted = requiredNetworkType()
        if (unfinishedWork().any { it.constraints.requiredNetworkType != wanted }) {
            enqueue(wanted, ExistingWorkPolicy.REPLACE)
        }
    }

    /**
     * The first start with extensions after a start without them, whose worker handed every run
     * back: a request waiting out the backoff those runs built up is replaced, so the queue goes on
     * as soon as its network allows instead of reading as a network problem meanwhile. A running
     * worker, and a queue with no request (paused), are left as they are.
     */
    suspend fun resumeAfterWithoutExtensions() {
        val states = unfinishedWork().map { it.state }
        if (WorkInfo.State.ENQUEUED in states && WorkInfo.State.RUNNING !in states) {
            enqueue(requiredNetworkType(), ExistingWorkPolicy.REPLACE)
        }
    }

    /**
     * Calls [rearm] on every value of "only download over Wi-Fi", whoever writes it: the download
     * settings, a restore or a sync. `SaikouApp` calls this once, with the app's scope.
     */
    fun followNetworkSetting(scope: CoroutineScope) {
        downloadPreferences.downloadOnlyOverWifi.changes()
            .onEach {
                try {
                    rearm()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Could not re-arm the download queue" }
                }
            }
            .launchIn(scope)
    }

    /** Stops the run; the queue rows stay, so this is a pause. */
    fun stop() {
        workManager.value.cancelUniqueWork(DownloadWorker.WORK_NAME)
    }

    val isRunning: Flow<Boolean>
        get() = status.map { it == DownloadStatus.RUNNING }

    /** What the worker is doing, from its requests' states and constraints. */
    val status: Flow<DownloadStatus>
        get() = workManager.value.getWorkInfosForUniqueWorkFlow(DownloadWorker.WORK_NAME)
            .map { statusOf(it, now = System.currentTimeMillis(), withoutExtensions = startMode.withoutExtensions) }

    private fun requiredNetworkType(): NetworkType =
        if (downloadPreferences.downloadOnlyOverWifi.get()) NetworkType.UNMETERED else NetworkType.CONNECTED

    // WorkManager answers from its own database, so the blocking future is read off the caller's
    // thread.
    private suspend fun unfinishedWork(): List<WorkInfo> = withContext(dispatchers.io) {
        workManager.value.getWorkInfosForUniqueWork(DownloadWorker.WORK_NAME).get().filterNot { it.state.isFinished }
    }

    /**
     * A run that ended on a network failure asks for a retry ([DownloadEngine.Outcome.interrupted]).
     * WorkManager holds it for the backoff, then for the constraint: a lost network is waited out by
     * the constraint, so the backoff only spaces the tries at a site that does not answer, and
     * grows linearly rather than doubling.
     */
    private fun enqueue(networkType: NetworkType, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(DownloadWorker.TAG)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.value.enqueueUniqueWork(DownloadWorker.WORK_NAME, policy, request)
    }

    internal companion object {
        const val RETRY_BACKOFF_SECONDS = 30L

        /**
         * A request that is enqueued and not running waits for its backoff when its last run asked
         * for a retry and it may not run before a later time, else for its network constraint; one
         * moment after a start it is also enqueued, on its way to running. A run the platform
         * stopped (Wi-Fi lost, say) has a stop reason, and waits for its network, whatever its next
         * run time says. [now] is epoch milliseconds.
         *
         * In a process started [withoutExtensions] any unfinished request is
         * [DownloadStatus.WAITING_FOR_EXTENSIONS], a run included: the worker only hands it back.
         */
        fun statusOf(infos: List<WorkInfo>, now: Long, withoutExtensions: Boolean = false): DownloadStatus {
            if (withoutExtensions && infos.any { !it.state.isFinished }) return DownloadStatus.WAITING_FOR_EXTENSIONS
            if (infos.any { it.state == WorkInfo.State.RUNNING }) return DownloadStatus.RUNNING
            val waiting = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED } ?: return DownloadStatus.IDLE
            val retrying =
                waiting.stopReason == WorkInfo.STOP_REASON_NOT_STOPPED && waiting.nextScheduleTimeMillis > now
            return when {
                retrying -> DownloadStatus.RETRYING
                waiting.constraints.requiredNetworkType == NetworkType.UNMETERED -> DownloadStatus.WAITING_FOR_WIFI
                else -> DownloadStatus.WAITING_FOR_NETWORK
            }
        }
    }
}
