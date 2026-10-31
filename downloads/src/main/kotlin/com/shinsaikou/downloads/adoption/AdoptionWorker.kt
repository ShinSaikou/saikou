package com.shinsaikou.downloads.adoption

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.downloads.DownloadNotifier
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.FileNotFoundException
import kotlin.time.Clock

/**
 * Hosts one [DownloadAdoption] run in the foreground, on [DownloadNotifier.CHANNEL_PROGRESS] with
 * the `dataSync` type the other long workers use, over the tree [StoragePreferences.adoptionTree]
 * names.
 *
 * The key is the run's state. It is cleared, and the read grant on the tree given back, only when
 * the run ends, with a summary either way. A run stopped before that keeps both, and WorkManager
 * starts the worker again on its own after the process died; that run reads the same key and, since
 * the manifest already holds every chapter the first one finished, copies only the rest.
 */
class AdoptionWorker @AssistedInject constructor(
    // WorkerInstanceFactory in :core:common assists only the parameters; the application context
    // comes from the graph, so it is a plain dependency here.
    context: Context,
    @Assisted params: WorkerParameters,
    private val adoption: DownloadAdoption,
    private val summaryStore: AdoptionSummaryStore,
    private val storagePreferences: StoragePreferences,
    private val notifier: DownloadNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val tree = storagePreferences.adoptionTree.get()
        if (tree.isBlank()) return Result.success()

        return try {
            // Inside the try, so the finally below clears the progress notification whatever
            // happens from here on, a stop while entering the foreground included.
            setForegroundSafely()
            val summary = withContext(Dispatchers.IO) {
                val root = UniFile.fromUri(applicationContext, Uri.parse(tree))
                    ?.takeIf { it.isDirectory }
                    ?: throw FileNotFoundException("The picked folder cannot be read")
                adoption.run(UniFileAdoptionNode(root)) { progress ->
                    setProgress(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total))
                    notifier.showAdoptionProgress(progress)
                }
            }
            finish(tree, summary)
            Result.success()
        } catch (_: CancellationException) {
            // Stopped: the key and the grant stay, so the run can be finished later.
            Result.success()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "The adoption run failed" }
            val key = ErrorKey.of(e)
            finish(
                tree,
                AdoptionSummary(
                    finishedAt = Clock.System.now().toEpochMilliseconds(),
                    matched = 0,
                    copied = 0,
                    skipped = 0,
                    unmatched = emptyList(),
                    failures = listOf(
                        AdoptionFailure(
                            path = "",
                            reason = AdoptionFailureReason.ERROR,
                            key = key,
                            detail = ErrorKey.detail(e),
                        ),
                    ),
                ),
            )
            Result.failure()
        } finally {
            notifier.cancelAdoptionProgress()
        }
    }

    /** Stores [summary] with [tree] in it, which the summary screen's Copy again runs over. */
    private suspend fun finish(tree: String, summary: AdoptionSummary) {
        summaryStore.replace(summary.copy(treeUri = tree))
        storagePreferences.adoptionTree.delete()
        // Nothing to give back for a grant that was never persisted, or for a plain file tree.
        runCatching {
            applicationContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(tree),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        notifier.showAdoptionFinished(summary)
    }

    private fun foregroundInfo(progress: DownloadAdoption.Progress?): ForegroundInfo {
        val notification = notifier.adoptionProgressNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifier.ID_ADOPTION_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(DownloadNotifier.ID_ADOPTION_PROGRESS, notification)
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<AdoptionWorker>

    companion object {
        const val WORK_NAME = "download-adoption"
        const val TAG = "download-adoption"

        /** Progress data: chapters handled so far, and the matched total (0 while walking). */
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
    }
}
