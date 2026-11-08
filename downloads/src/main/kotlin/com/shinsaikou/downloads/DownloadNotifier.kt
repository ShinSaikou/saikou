package com.shinsaikou.downloads

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.domain.storage.FolderCopy
import com.shinsaikou.downloads.adoption.AdoptionSummary
import com.shinsaikou.downloads.adoption.DownloadAdoption
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.lang.chop

/**
 * Every notification the download engine posts. The channels belong to this module and are
 * created on first use, the way `LibraryUpdateNotifier` and `BackupNotifier` create theirs.
 *
 * The only text taken from the owner's data is a manga title and a chapter name, and both are hidden
 * when [SecurityPreferences.hideNotificationContent] is on. No source name or page address reaches a
 * notification. Both download notifications open the download queue through [DownloadIntents].
 *
 * The adoption of upstream's downloads posts on the same progress channel, under ids of its own so a
 * download run and an adoption run can each hold a foreground notification; its closing notification
 * opens the download settings, where the summary is. The export of the downloads (unit
 * storage-location) does the same under its own two ids.
 */
@SingleIn(AppScope::class)
@Inject
class DownloadNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val messages: DownloadMessages,
    private val intents: DownloadIntents,
) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannels)
    }

    /**
     * The worker's foreground notification. WorkManager posts it, so it is built here and not posted.
     * It names the entry, then the chapter and the chapters left; with notification content hidden,
     * only the count. Pause stops the run and keeps the queue; Cancel all empties the queue.
     *
     * With several chapters running at once (several sources at once, the Experimental switch), it
     * names no entry: the title counts the chapters running, the text the chapters left, and the bar
     * is their pages together.
     */
    fun progressNotification(progress: DownloadEngine.Progress?): Notification {
        val hidden = securityPreferences.hideNotificationContent.get()
        val running = progress?.chaptersRunning ?: 1
        val several = running > 1
        val builder = builderOn(CHANNEL_PROGRESS)
            .setContentTitle(
                if (several) {
                    messages.chaptersRunning(running)
                } else {
                    progress?.takeUnless { hidden }?.mangaTitle?.chop(TITLE_MAX_LENGTH) ?: messages.downloaderTitle()
                },
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intents.openQueue())
            .setProgress(
                progress?.pagesTotal ?: 0,
                progress?.pagesDone ?: 0,
                progress == null || progress.pagesTotal == 0,
            )
        if (progress != null) {
            builder.setContentText(
                if (hidden || several) {
                    messages.chaptersLeft(progress.chaptersLeft)
                } else {
                    messages.chapterAndLeft(progress.chapterName.chop(TITLE_MAX_LENGTH), progress.chaptersLeft)
                },
            )
        }
        intents.pauseDownloads()?.let {
            builder.addAction(android.R.drawable.ic_media_pause, messages.pauseAction(), it)
        }
        intents.cancelDownloads()?.let {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, messages.cancelAllAction(), it)
        }
        return builder.build()
    }

    fun showProgress(progress: DownloadEngine.Progress) {
        notify(ID_DOWNLOAD_PROGRESS, progressNotification(progress))
    }

    fun cancelProgress() {
        manager.cancel(ID_DOWNLOAD_PROGRESS)
    }

    /**
     * Whether the progress notification is up. After a process died during a run the system can
     * bring WorkManager's foreground service back with that run's notification, before any run of
     * the new process has entered the foreground.
     */
    fun isProgressShown(): Boolean = manager.activeNotifications.any { it.id == ID_DOWNLOAD_PROGRESS }

    /** The adoption worker's foreground notification, built here and posted by WorkManager. */
    fun adoptionProgressNotification(progress: DownloadAdoption.Progress?): Notification =
        builderOn(CHANNEL_PROGRESS)
            .setContentTitle(messages.adoptionTitle())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intents.openDownloadSettings())
            .setProgress(
                progress?.total ?: 0,
                progress?.done ?: 0,
                progress == null || progress.total == 0,
            )
            .build()

    fun showAdoptionProgress(progress: DownloadAdoption.Progress) {
        notify(ID_ADOPTION_PROGRESS, adoptionProgressNotification(progress))
    }

    fun cancelAdoptionProgress() {
        manager.cancel(ID_ADOPTION_PROGRESS)
    }

    fun showAdoptionFinished(summary: AdoptionSummary) {
        notify(
            ID_ADOPTION_FINISHED,
            builderOn(CHANNEL_PROGRESS)
                .setContentTitle(messages.adoptionFinished(summary.copied))
                .setContentText(messages.adoptionFinishedDetail())
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(intents.openDownloadSettings())
                .build(),
        )
    }

    /** The export worker's foreground notification, built here and posted by WorkManager. */
    fun exportProgressNotification(progress: FolderCopy.Progress?): Notification =
        builderOn(CHANNEL_PROGRESS)
            .setContentTitle(messages.exportTitle())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intents.openDownloadSettings())
            .setProgress(progress?.total ?: 0, progress?.done ?: 0, progress == null || progress.total == 0)
            .build()

    fun showExportProgress(progress: FolderCopy.Progress) {
        notify(ID_EXPORT_PROGRESS, exportProgressNotification(progress))
    }

    fun cancelExportProgress() {
        manager.cancel(ID_EXPORT_PROGRESS)
    }

    fun showExportFinished(result: FolderCopy.Result) {
        notify(
            ID_EXPORT_FINISHED,
            builderOn(CHANNEL_PROGRESS)
                .setContentTitle(messages.exportFinished(result.copied, result.skipped, result.failed))
                .setContentText(messages.exportFinishedDetail())
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .setContentIntent(intents.openDownloadSettings())
                .build(),
        )
    }

    /** No folder to write to: the notification opens the download settings, whose row picks one. */
    fun showExportUnreachable() = showExportProblem(messages.exportUnreachable())

    /** The run failed outright: the notification opens the row, which runs it again. */
    fun showExportStopped() = showExportProblem(messages.exportStopped())

    private fun showExportProblem(title: String) {
        notify(
            ID_EXPORT_FINISHED,
            builderOn(CHANNEL_ERRORS)
                .setContentTitle(title)
                .setContentText(messages.exportProblemDetail())
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(intents.openDownloadSettings())
                .build(),
        )
    }

    /** The run stopped with chapters still queued because storage is almost full; it opens the queue. */
    fun showStorageFull() {
        notify(
            ID_DOWNLOAD_STORAGE_FULL,
            builderOn(CHANNEL_ERRORS)
                .setContentTitle(messages.storageFull())
                .setContentText(messages.storageFullDetail())
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(intents.openQueue())
                .build(),
        )
    }

    fun showErrors(count: Int) {
        if (count == 0) return
        notify(
            ID_DOWNLOAD_ERROR,
            builderOn(CHANNEL_ERRORS)
                .setContentTitle(messages.downloadErrors(count))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(intents.openQueue())
                .build(),
        )
    }

    /**
     * Every notification this class makes is built here, so none can be built on a channel the
     * device does not have. WorkManager posts [progressNotification] itself through
     * `startForeground`, never through [notify], and the platform kills the process for a foreground
     * notification on a missing channel (ARCHITECTURE.md carry-over 18, R59). Touching [manager]
     * registers the channels; `createNotificationChannelsCompat` is idempotent.
     */
    private fun builderOn(channel: String): NotificationCompat.Builder {
        manager
        return NotificationCompat.Builder(context, channel)
    }

    private fun notify(id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) {
            manager.notify(id, notification)
        }
    }

    private fun createChannels(manager: NotificationManagerCompat) {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManager.IMPORTANCE_LOW)
                    .setName(messages.progressChannelName())
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ERRORS, NotificationManager.IMPORTANCE_LOW)
                    .setName(messages.errorChannelName())
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "download_progress"
        const val CHANNEL_ERRORS = "download_errors"

        const val ID_DOWNLOAD_PROGRESS = 201
        const val ID_DOWNLOAD_ERROR = 202
        const val ID_ADOPTION_PROGRESS = 203
        const val ID_ADOPTION_FINISHED = 204
        const val ID_EXPORT_PROGRESS = 205
        const val ID_EXPORT_FINISHED = 206
        const val ID_DOWNLOAD_STORAGE_FULL = 207

        private const val TITLE_MAX_LENGTH = 45
    }
}
