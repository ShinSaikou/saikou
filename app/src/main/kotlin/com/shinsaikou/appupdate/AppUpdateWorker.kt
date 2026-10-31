package com.shinsaikou.appupdate

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException

/**
 * Downloads one release's APK in the foreground, checks it, and hands it to Android to install.
 * Built by `SaikouWorkerFactory` (R8) and enqueued by [AppUpdateScheduler], once per version.
 *
 * The file lives in the cache only for the length of the run: it is deleted when its digest or its
 * signer is refused, when the download fails, and once its bytes are in the install session. What
 * happens after the commit is [AppUpdateInstallReceiver]'s.
 */
class AppUpdateWorker @AssistedInject constructor(
    private val context: Context,
    @Assisted params: WorkerParameters,
    private val releases: GitHubReleases,
    private val installer: AppUpdateInstaller,
    private val notifier: AppUpdateNotifier,
) : CoroutineWorker(context, params) {

    private val version: String
        get() = inputData.getString(KEY_VERSION).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotification(version)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(AppUpdateNotifier.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(AppUpdateNotifier.ID_PROGRESS, notification)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val update = updateOf(inputData) ?: return Result.failure()
        // Named for the version, so a run that died with its process and is started again writes over
        // its own file, and a run for another version never touches this one.
        val apk = File(File(context.cacheDir, DIRECTORY), "${update.version}.apk")
        notifier.cancelStaleStatus()
        setForegroundSafely()
        return try {
            val progress = ProgressGate()
            releases.download(update, apk) { done, total ->
                if (progress.due(done, total)) notifier.showProgress(update.version, done, total)
            }
            notifier.cancelProgress()
            val refusal = installer.check(apk)
            if (refusal != null) {
                logcat(LogPriority.WARN) { "The update to ${update.version} was refused: $refusal" }
                notifier.showRefused(update.version, refusal)
                Result.failure()
            } else {
                installer.install(apk, update.version)
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DigestMismatchException) {
            logcat(LogPriority.WARN, e) { "The update to ${update.version} does not match its digest" }
            notifier.showRefused(update.version, UpdateRefusal.DIGEST_MISMATCH)
            Result.failure()
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "The update to ${update.version} could not be downloaded" }
            notifier.showDownloadFailed(update.version)
            Result.failure()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "The update to ${update.version} could not be installed" }
            notifier.showInstallFailed(update.version, PackageInstaller.STATUS_FAILURE)
            Result.failure()
        } finally {
            apk.delete()
            notifier.cancelProgress()
        }
    }

    /** A progress line when the percentage moves, and no more often than Android keeps up with. */
    private class ProgressGate {
        private var lastPercent = -1L
        private var lastAt = 0L

        fun due(done: Long, total: Long): Boolean {
            val percent = if (total > 0L) done * PERCENT / total else -1L
            val now = SystemClock.elapsedRealtime()
            if (percent == lastPercent || now - lastAt < MIN_INTERVAL_MILLIS) return false
            lastPercent = percent
            lastAt = now
            return true
        }
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<AppUpdateWorker>

    companion object {
        private const val DIRECTORY = "app_update"
        private const val KEY_VERSION = "version"
        private const val KEY_ASSET_NAME = "asset_name"
        private const val KEY_ASSET_URL = "asset_url"
        private const val KEY_ASSET_DIGEST = "asset_digest"
        private const val PERCENT = 100L
        private const val MIN_INTERVAL_MILLIS = 500L

        /** One unique work per version, so a second Update on the same release joins the first. */
        fun workName(version: String): String = "app-update-$version"

        /** The release notes stay behind: the run never shows them, and input data is capped in size. */
        fun inputOf(update: AppUpdate): Data = workDataOf(
            KEY_VERSION to update.version,
            KEY_ASSET_NAME to update.assetName,
            KEY_ASSET_URL to update.assetUrl,
            KEY_ASSET_DIGEST to update.assetDigest,
        )

        fun updateOf(data: Data): AppUpdate? {
            val version = data.getString(KEY_VERSION) ?: return null
            val url = data.getString(KEY_ASSET_URL) ?: return null
            return AppUpdate(
                version = version,
                notes = "",
                assetName = data.getString(KEY_ASSET_NAME).orEmpty(),
                assetUrl = url,
                assetDigest = data.getString(KEY_ASSET_DIGEST),
            )
        }
    }
}
