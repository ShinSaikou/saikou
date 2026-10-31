package com.shinsaikou.backup

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.backup.importer.ImportProgress
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.lang.chop

/**
 * Every notification a backup run posts. The channels belong to this module and are created on
 * first use. The only text taken from the user's own data is a manga title, and that is hidden
 * when [SecurityPreferences.hideNotificationContent] is on. A source name, a signing key and a
 * file path from a foreign install never reach a notification.
 */
@SingleIn(AppScope::class)
@Inject
class BackupNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val intents: BackupIntents,
) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannels)
    }

    /**
     * Also the restore worker's foreground notification, so it is built here and posted by
     * WorkManager. Built rather than posted still needs the channel; see [builderOn].
     */
    fun restoreProgressNotification(progress: ImportProgress?, cancelIntent: PendingIntent?): Notification {
        val builder = builderOn(CHANNEL_PROGRESS)
            .setContentTitle(context.getString(R.string.restore_in_progress))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progress?.total ?: 0, progress?.done ?: 0, progress == null || progress.total == 0)
        if (!securityPreferences.hideNotificationContent.get()) {
            progress?.currentTitle?.let { builder.setContentText(it.chop(TITLE_MAX_LENGTH)) }
        }
        if (cancelIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                cancelIntent,
            )
        }
        return builder.build()
    }

    fun showRestoreProgress(progress: ImportProgress, cancelIntent: PendingIntent?) {
        notify(ID_RESTORE_PROGRESS, restoreProgressNotification(progress, cancelIntent))
    }

    fun cancelRestoreProgress() {
        manager.cancel(ID_RESTORE_PROGRESS)
    }

    fun showRestoreComplete(report: ImportReport) {
        val builder = builderOn(CHANNEL_COMPLETE)
            .setContentTitle(context.getString(R.string.restore_completed))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setAutoCancel(true)
        restoreCompleteText(report)?.let(builder::setContentText)
        intents.openImportReport()?.let(builder::setContentIntent)
        notify(ID_RESTORE_COMPLETE, builder.build())
    }

    /**
     * The completion notification's one line, which the tap on it explains in full (the restore
     * report, unit restore-report): the errors when there are any, else how many sources the file
     * named that have no extension here, else nothing.
     */
    internal fun restoreCompleteText(report: ImportReport): String? {
        val missingSources = report.sourcesWithoutExtension.size
        return when {
            report.errors.isNotEmpty() -> context.getString(R.string.restore_error_count, report.errors.size)
            missingSources > 0 ->
                context.resources.getQuantityString(R.plurals.restore_missing_sources_count, missingSources, missingSources)
            else -> null
        }
    }

    fun showRestoreCancelled() {
        notify(
            ID_RESTORE_COMPLETE,
            builderOn(CHANNEL_COMPLETE)
                .setContentTitle(context.getString(R.string.restore_cancelled))
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A restore that stopped on an error it will not retry; its checkpoint stays for the next try. */
    fun showRestoreFailed() {
        notify(
            ID_RESTORE_COMPLETE,
            builderOn(CHANNEL_COMPLETE)
                .setContentTitle(context.getString(R.string.restore_failed))
                .setContentText(context.getString(R.string.restore_failed_resume))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun backupProgressNotification(): Notification = builderOn(CHANNEL_PROGRESS)
        .setContentTitle(context.getString(R.string.creating_backup))
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()

    fun cancelBackupProgress() {
        manager.cancel(ID_BACKUP_PROGRESS)
    }

    /** [shareIntent] is null for an automatic run, which posts nothing at all. */
    fun showBackupComplete(shareIntent: PendingIntent?) {
        val builder = builderOn(CHANNEL_COMPLETE)
            .setContentTitle(context.getString(R.string.backup_created))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setAutoCancel(true)
        shareIntent?.let {
            builder.addAction(android.R.drawable.ic_menu_share, context.getString(R.string.action_share), it)
        }
        notify(ID_BACKUP_COMPLETE, builder.build())
    }

    fun showBackupError() {
        notify(
            ID_BACKUP_COMPLETE,
            builderOn(CHANNEL_COMPLETE)
                .setContentTitle(context.getString(R.string.backup_failed))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Every notification this class makes is built here, so that none can be built on a channel the
     * device does not have. Registering the channels is a side effect of the first touch of
     * [manager], and a notification that is built and handed to someone else to post never touches
     * it: WorkManager takes the progress notifications below and posts them itself through
     * `startForeground`. The platform refuses a foreground notification whose channel does not
     * exist, for any app targeting API 27 or higher (the check is already in the API 28 source,
     * `ServiceRecord.postNotification`, and this app targets far above it), and crashes the process
     * for it with `Bad notification for startForeground` (thrown on the gate's tablet as
     * `CannotPostForegroundServiceNotificationException`), so the failure is not a missing
     * notification but a dead application. That is what happened on the
     * tablet during the Phase 0 gate run of 2026-09-19, where the log line under it read
     * `No Channel found for pkg=com.shinsaikou, channelId=backup_restore_progress`.
     *
     * `createNotificationChannelsCompat` is idempotent, so this costs one binder call per process.
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
                    .setName(context.getString(R.string.channel_backup_progress))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_COMPLETE, NotificationManager.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_backup_complete))
                    .build(),
            ),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "backup_restore_progress"
        const val CHANNEL_COMPLETE = "backup_restore_complete"

        const val ID_BACKUP_PROGRESS = 501
        const val ID_BACKUP_COMPLETE = 502
        const val ID_RESTORE_PROGRESS = 503
        const val ID_RESTORE_COMPLETE = 504

        private const val TITLE_MAX_LENGTH = 45
    }
}
