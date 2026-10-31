package com.shinsaikou.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import com.shinsaikou.sync.drive.DriveMalformedResponseException
import com.shinsaikou.sync.drive.DriveStorageFullException
import com.shinsaikou.sync.google.GoogleAuthRefusedException
import com.shinsaikou.sync.webdav.WebDavHostNameMismatchException
import com.shinsaikou.sync.webdav.WebDavMalformedResponseException
import com.shinsaikou.sync.webdav.WebDavRedirectException
import com.shinsaikou.sync.webdav.WebDavUntrustedCertificateException
import com.shinsaikou.sync.webdav.WebDavUrlRefusedException
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * Hosts one sync run as a foreground worker (plan unit G, Q10), under either of [SyncScheduler]'s
 * two names. The run is [SyncSession.run]; this class owns the WorkManager contract.
 *
 * A run another run's lock turned away is a success with no work. A failure the owner has to act on
 * ends the work with [Result.failure], shown in Sync settings and never retried in a loop: signed
 * out, a newer format, a malformed sync file, an untrusted certificate, a host name mismatch, a
 * redirect, a malformed WebDAV answer, a refused address, a failed safety backup, Google's refusal of
 * the Drive client (Q1's fallback), a full Drive and a malformed Drive answer. Any other
 * [IOException] is [Result.retry], which WorkManager backs off exponentially: a token endpoint or
 * a Drive that did not answer after the backend's own waits, a Drive status no rule accepts, and a
 * Drive write that did not read back among them. Anything else is a failure. A cancel propagates.
 * Sync does not wait for the source registry (Q10): nothing here reads a source.
 *
 * Each run's outcome goes to [SyncNotices] (fixes unit sync-notices): a success removes the failure
 * notices, and a failure may post one, unless it is the first attempt of a run the owner started with
 * Sync now ([userStarted]). A notice that cannot be posted is logged and changes no outcome.
 */
class SyncWorker @AssistedInject constructor(
    // WorkerInstanceFactory in :core:common assists only the parameters; the application context
    // comes from the graph, so it is a plain dependency here.
    context: Context,
    @Assisted params: WorkerParameters,
    private val session: SyncSession,
    private val notifier: SyncNotifier,
    private val notices: SyncNotices,
) : CoroutineWorker(context, params) {

    /**
     * Whether the owner is looking at this run: Sync now's request ([SyncScheduler.KEY_USER_STARTED])
     * on its first attempt. WorkManager's retries of it run later in the background, as any other run.
     */
    private val userStarted: Boolean
        get() = runAttemptCount == 0 && inputData.getBoolean(SyncScheduler.KEY_USER_STARTED, false)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SyncNotifier.ID_SYNC_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SyncNotifier.ID_SYNC_PROGRESS, notification)
        }
    }

    override suspend fun doWork(): Result = try {
        setForegroundSafely()
        if (session.run() != null) quietly { notices.syncSucceeded() }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat(LogPriority.ERROR, e) { "The sync run failed" }
        quietly { notices.syncFailed(e, userStarted) }
        if (retries(e)) Result.retry() else Result.failure()
    }

    /** Runs a notice change; its failure is logged, so the run's outcome stays the run's own. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "The sync notice failed" }
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<SyncWorker>

    companion object {

        /**
         * Whether [error] is transient: an [IOException] that none of the final classes names. The
         * newer format and the malformed file are not [IOException]s, so they fail too.
         */
        fun retries(error: Throwable): Boolean = error is IOException && when (error) {
            is SyncSignedOutException,
            is WebDavUntrustedCertificateException,
            is WebDavHostNameMismatchException,
            is WebDavRedirectException,
            is WebDavMalformedResponseException,
            is WebDavUrlRefusedException,
            is SyncSafetyBackupException,
            is GoogleAuthRefusedException,
            is DriveStorageFullException,
            is DriveMalformedResponseException,
            -> false
            else -> true
        }
    }
}
