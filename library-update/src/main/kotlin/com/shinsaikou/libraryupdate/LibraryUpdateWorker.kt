package com.shinsaikou.libraryupdate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateSkipCounts
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Hosts one library update run as a foreground worker. The pass itself is
 * [FlatLibraryUpdate]; this class owns the WorkManager contract, the progress reporting and
 * the notifications.
 */
class LibraryUpdateWorker @AssistedInject constructor(
    // WorkerInstanceFactory in :core:common assists only the parameters; the application context
    // comes from the graph, so it is a plain dependency here.
    context: Context,
    @Assisted params: WorkerParameters,
    private val update: FlatLibraryUpdate,
    private val getManga: GetManga,
    private val notifier: LibraryUpdateNotifier,
    private val errorStore: LibraryUpdateErrorStore,
    private val libraryPreferences: LibraryPreferences,
    private val scheduler: LibraryUpdateScheduler,
    private val timeSource: TimeSource,
) : CoroutineWorker(context, params) {

    private val automatic: Boolean
        get() = WORK_NAME_AUTO in tags

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
        FlatLibraryUpdate.Progress(done = 0, total = 0, current = null),
    )

    override suspend fun doWork(): Result {
        if (automatic && !canRunAutomatically()) return Result.retry()
        // Upstream's rule: an automatic run that comes due while a manual one is going tries again
        // later. Two runs at once ask the same sites with two budgets, share one progress
        // notification, and the later one would report over the first.
        if (automatic && scheduler.isManualRunning()) return Result.retry()

        val categoryId = inputData.getLong(KEY_CATEGORY, CATEGORY_ALL).takeIf { it != CATEGORY_ALL }
        val refreshMetadata = inputData.getBoolean(KEY_REFRESH_METADATA, false)
        val mangaIds = inputData.getLongArray(KEY_MANGA_IDS)?.toSet()

        return try {
            // Inside the try, so the finally below clears the progress and re-arms an automatic run
            // whatever happens from here on.
            setForegroundSafely()
            libraryPreferences.lastUpdatedTimestamp.set(Clock.System.now().toEpochMilliseconds())

            // A stop cancels this coroutine's own Job. The pass still returns what it gathered, but a
            // withContext whose Job was cancelled from outside drops the value its block returns and
            // throws instead, so the outcome leaves the block by assignment.
            var outcome: FlatLibraryUpdate.Outcome? = null
            val cadence = ProgressCadence(timeSource)
            try {
                withContext(Dispatchers.IO) {
                    outcome = update.run(
                        categoryId = categoryId,
                        automatic = automatic,
                        refreshMetadata = refreshMetadata,
                        mangaIds = mangaIds,
                    ) { progress ->
                        // Both or neither, so the work data and the notification show the same report.
                        if (cadence.shouldSend(progress)) {
                            setProgressAsync(
                                workDataOf(
                                    KEY_PROGRESS_DONE to progress.done,
                                    KEY_PROGRESS_TOTAL to progress.total,
                                    KEY_PROGRESS_CURRENT to progress.current?.displayTitle.orEmpty(),
                                ),
                            )
                            notifier.showProgress(progress, cancelIntent())
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Stopped. What the pass returned is reported below; a stop before it ran leaves nothing.
            }

            // A cancelled run has still written everything it managed to fetch, so its failures
            // go into the list and its new chapters are announced. The coroutine may be cancelled
            // here, so this runs regardless, or its first suspension would throw.
            outcome?.let { withContext(NonCancellable) { report(it, mangaIds.orEmpty()) } }
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "The library update run failed" }
            Result.failure()
        } finally {
            notifier.cancelProgress()
            if (automatic) {
                // Stopping an automatic run from the notification cancels its periodic request too.
                scheduler.setup()
            }
        }
    }

    private fun foregroundInfo(progress: FlatLibraryUpdate.Progress): ForegroundInfo {
        val notification = notifier.progressNotification(progress, cancelIntent())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                LibraryUpdateNotifier.ID_LIBRARY_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(LibraryUpdateNotifier.ID_LIBRARY_PROGRESS, notification)
        }
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        0,
        Intent(applicationContext, LibraryUpdateCancelReceiver::class.java).apply {
            putExtra(LibraryUpdateCancelReceiver.EXTRA_RUN_ID, id.toString())
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Below API 28 WorkManager cannot express "wifi only" as a constraint, so the transport is
     * re-checked here and the run is retried when the device moved off wifi.
     */
    private fun canRunAutomatically(): Boolean {
        val restrictions = libraryPreferences.autoUpdateDeviceRestrictions.get()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return true
        if (LibraryUpdateRestrictions.DEVICE_ONLY_ON_WIFI !in restrictions) return true

        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** [asked] is the entries the run was given, empty when it took categories. */
    private suspend fun report(outcome: FlatLibraryUpdate.Outcome, asked: Set<Long>) {
        // An automatic run's skips were only logged, so "it skips most of my library" had no answer
        // on the device. Updates shows these under its last update line; a manual run applies no
        // rule but fetch-once, so it leaves the last automatic run's counts in place.
        if (automatic) {
            outcome.skipped?.let { libraryPreferences.lastAutoUpdateSkipped.set(LibraryUpdateSkipCounts.encode(it)) }
        }
        errorStore.merge(outcome.failures, outcome.checked, asked, ::isInLibrary)
        notifier.showNewChapters(resolveTitles(outcome))
        notifier.showErrors(outcome.failures.size)
    }

    /** Whether an earlier run's error row still names a library entry, and so stays in the list. */
    private suspend fun isInLibrary(mangaId: Long): Boolean = withContext(Dispatchers.IO) {
        getManga.await(mangaId)?.let { it.favorite && it.deletedAt == null } == true
    }

    /** The pass reports updates by manga id; the notification needs the row for its title. */
    private suspend fun resolveTitles(
        outcome: FlatLibraryUpdate.Outcome,
    ): Map<Manga, List<Chapter>> = withContext(Dispatchers.IO) {
        outcome.updated.mapNotNull { (mangaId, chapters) ->
            getManga.await(mangaId)?.let { it to chapters }
        }.toMap()
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<LibraryUpdateWorker>

    companion object {
        const val TAG = "library-update"
        const val WORK_NAME_AUTO = "library-update-auto"
        const val WORK_NAME_MANUAL = "library-update-manual"

        /** Long input, [CATEGORY_ALL] means every category the preferences allow. */
        const val KEY_CATEGORY = "category"

        /** Boolean input, manual runs only. */
        const val KEY_REFRESH_METADATA = "metadata"

        /**
         * Long array input, manual runs only: the entries to update, in place of the categories. At
         * most [MAX_MANGA_IDS], which keeps the request inside WorkManager's 10 KB of input data.
         */
        const val KEY_MANGA_IDS = "manga_ids"

        const val MAX_MANGA_IDS = 1000

        const val CATEGORY_ALL = -1L

        /**
         * The progress [doWork] writes with `setProgressAsync`, at [ProgressCadence]'s pace and
         * with the notification's report: the entries done, the queue's size and the title
         * starting. No screen reads them now: the Library and Updates screens read
         * [LibraryUpdateScheduler.isRunning] (ruling R10 had the Updates screen read these through
         * `WorkInfo.progress`).
         */
        const val KEY_PROGRESS_DONE = "done"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_PROGRESS_CURRENT = "current"

        private const val FLEX_MINUTES = 10L
        private const val BACKOFF_MINUTES = 10L

        /**
         * Arms the periodic run from [preferences], or cancels it when the interval is off. Called
         * at start-up and whenever the interval or the device restrictions change.
         *
         * The network is upstream's `NetworkRequest`: any network a VPN may carry, Wi-Fi when the
         * owner asked for it, not metered when asked. WorkManager applies the request from API 28
         * and the [NetworkType] below that, where [canRunAutomatically] checks for Wi-Fi at run
         * time. Before phase 3 unit AD the request was never set, so on API 28 and up "Only on
         * Wi-Fi" constrained nothing and a periodic run went out on mobile data.
         */
        fun setupPeriodic(workManager: WorkManager, preferences: LibraryPreferences) {
            val interval = preferences.autoUpdateInterval.get()
            if (interval <= 0) {
                workManager.cancelUniqueWork(WORK_NAME_AUTO)
                return
            }

            val restrictions = preferences.autoUpdateDeviceRestrictions.get()
            val networkType = if (LibraryUpdateRestrictions.DEVICE_NETWORK_NOT_METERED in restrictions) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val networkRequest = NetworkRequest.Builder().apply {
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (LibraryUpdateRestrictions.DEVICE_ONLY_ON_WIFI in restrictions) {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                }
                if (LibraryUpdateRestrictions.DEVICE_NETWORK_NOT_METERED in restrictions) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkRequest(networkRequest, networkType)
                .setRequiresCharging(LibraryUpdateRestrictions.DEVICE_CHARGING in restrictions)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(
                interval.toLong(),
                TimeUnit.HOURS,
                FLEX_MINUTES,
                TimeUnit.MINUTES,
            )
                .addTag(TAG)
                .addTag(WORK_NAME_AUTO)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(WORK_NAME_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Cancels the run going now, automatic or manual, and a manual run still waiting to start,
         * as upstream's `stop` cancels the running work only. It never cancels by [TAG]: the periodic
         * request carries that tag while it waits, and cancelling the tag removed the automatic
         * schedule after every manual cancel. A cancelled automatic run re-arms itself in [doWork]'s
         * `finally`.
         */
        suspend fun stop(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME_MANUAL)
            // WorkManager answers from its own database, so the blocking future is read off the
            // caller's thread.
            val infos = withContext(Dispatchers.IO) { workManager.getWorkInfosByTag(TAG).get() }
            infos.filter { it.state == WorkInfo.State.RUNNING }
                .forEach { workManager.cancelWorkById(it.id) }
        }
    }
}
