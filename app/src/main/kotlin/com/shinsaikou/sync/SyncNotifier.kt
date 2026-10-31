package com.shinsaikou.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.text.format.DateUtils
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Sync's notifications, on two channels: the worker's foreground notification on
 * [CHANNEL_PROGRESS], which says a sync is running and no more, and [SyncNotices]'s notices on
 * [CHANNEL_ERRORS] (fixes unit sync-notices), the Google sign-in's coming expiry and a background
 * sync that stopped for a [SyncNotices.Reason]. Each notice opens Sync settings, where its action
 * lives, through [MainDestination.SYNC_SETTINGS]; none names the account, the server or anything the
 * backend answered.
 */
@SingleIn(AppScope::class)
@Inject
class SyncNotifier(private val context: Context) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannels)
    }

    /**
     * Built here and posted by WorkManager through `startForeground`, which crashes the process for a
     * notification whose channel does not exist; touching [manager] first registers it (the reason
     * `LibraryUpdateNotifier.builderOn` gives).
     */
    fun progressNotification(): Notification {
        manager
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(context.getString(R.string.notification_syncing_library))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    /**
     * "Google sign-in for sync ends soon", with the date and time it ends in the words Sync settings
     * uses, and Sign in again.
     */
    fun postExpiry(expiresAt: Long) {
        val text = context.getString(R.string.sync_drive_expires_format, dateTimeText(expiresAt))
        notify(ID_EXPIRY, notice(R.string.notification_sync_sign_in_ending, text, R.string.notification_sync_sign_in_again))
    }

    /** "Sync stopped", the reason in the words Sync settings uses, and the action that solves it. */
    fun postFailure(reason: SyncNotices.Reason) {
        val (text, action) = when (reason) {
            SyncNotices.Reason.SIGN_IN_LOST -> R.string.sync_sign_in_again_lost to R.string.notification_sync_sign_in_again
            SyncNotices.Reason.GOOGLE_SIGN_IN_REFUSED -> R.string.sync_drive_sign_in_again_refused to R.string.notification_sync_sign_in_again
            SyncNotices.Reason.PASSWORD_REFUSED -> R.string.sync_sign_in_again_refused to R.string.notification_sync_sign_in_again
            SyncNotices.Reason.DRIVE_FULL -> R.string.error_drive_storage_full to R.string.notification_sync_open_settings
        }
        notify(idOf(reason), notice(R.string.notification_sync_stopped, context.getString(text), action))
    }

    fun cancelFailure(reason: SyncNotices.Reason) = cancel(idOf(reason))

    fun cancel(id: Int) {
        manager.cancel(id)
    }

    private fun notice(title: Int, text: String, action: Int): Notification {
        manager
        val open = PendingIntent.getActivity(
            context,
            REQUEST_SYNC_SETTINGS,
            MainIntents.newIntent(context, MainDestination.SYNC_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ERRORS)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.stat_notify_sync, context.getString(action), open)
            .build()
    }

    private fun notify(id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) {
            manager.notify(id, notification)
        }
    }

    /** The device's locale and its 12 or 24 hour setting, as Sync settings writes a run's time. */
    private fun dateTimeText(millis: Long): String = DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or
            DateUtils.FORMAT_SHOW_TIME,
    )

    private fun createChannels(manager: NotificationManagerCompat) {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManager.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_sync_progress))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ERRORS, NotificationManager.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_sync_errors))
                    .build(),
            ),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "sync_progress"
        const val CHANNEL_ERRORS = "sync_errors"
        const val ID_SYNC_PROGRESS = 601
        const val ID_EXPIRY = 602

        /** One notification per [SyncNotices.Reason]. */
        fun idOf(reason: SyncNotices.Reason): Int = when (reason) {
            SyncNotices.Reason.SIGN_IN_LOST -> 603
            SyncNotices.Reason.GOOGLE_SIGN_IN_REFUSED -> 604
            SyncNotices.Reason.PASSWORD_REFUSED -> 605
            SyncNotices.Reason.DRIVE_FULL -> 606
        }

        /** Apart from the library update (1 to 5), backup (11, 12), download (21, 22) and sign-in (31) codes. */
        private const val REQUEST_SYNC_SETTINGS = 41
    }
}
