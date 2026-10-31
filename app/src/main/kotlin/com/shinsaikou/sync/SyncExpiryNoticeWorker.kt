package com.shinsaikou.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Posts the Google sign-in's expiry notice when [SyncNotices.reconcileExpiry]'s delay is up
 * (fixes unit sync-notices). Built through `SaikouWorkerFactory` (R8). Short and offline: it reads
 * the stored expiry again and posts, so it needs neither the foreground nor a network. A failure
 * is logged and not retried, since the next run or app start arms the notice again.
 */
class SyncExpiryNoticeWorker @AssistedInject constructor(
    // As SyncWorker: the factory assists only the parameters, so the context is a plain dependency.
    context: Context,
    @Assisted params: WorkerParameters,
    private val notices: SyncNotices,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        notices.expiryDue()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "The sync sign-in expiry notice failed" }
        Result.failure()
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<SyncExpiryNoticeWorker>
}
