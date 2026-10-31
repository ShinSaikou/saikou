package com.shinsaikou.storage

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.domain.storage.FolderCopy
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The move to a storage location's notifications, on a channel of their own: the worker's progress,
 * then how it ended. Each opens Settings > Data, where the Storage location row is and, when files
 * were left behind, the row that moves them again.
 */
@SingleIn(AppScope::class)
@Inject
class StorageNotifier(private val context: Context) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannel)
    }

    /** Posted by WorkManager through `startForeground`, so touching [manager] registers the channel first. */
    fun progressNotification(progress: FolderCopy.Progress?): Notification {
        manager
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.notification_storage_move_title))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDataSettings())
            .setProgress(progress?.total ?: 0, progress?.done ?: 0, progress == null || progress.total == 0)
            .build()
    }

    fun showProgress(progress: FolderCopy.Progress) = notify(ID_PROGRESS, progressNotification(progress))

    fun cancelProgress() = manager.cancel(ID_PROGRESS)

    /** [stillIn] names the previous location when files that could not be moved are still in it. */
    fun showFinished(result: FolderCopy.Result, stillIn: String? = null) {
        val title = when {
            result.failed == 0 -> context.getString(R.string.notification_storage_move_done)
            stillIn != null -> context.resources.getQuantityString(
                R.plurals.notification_storage_move_failed_in,
                result.failed,
                result.failed,
                stillIn,
            )
            else -> context.resources.getQuantityString(R.plurals.notification_storage_move_failed, result.failed, result.failed)
        }
        val text = if (result.failed == 0) null else context.getString(R.string.notification_storage_move_again)
        notify(ID_FINISHED, finished(title, text, android.R.drawable.stat_sys_upload_done))
    }

    /** The location could not be reached or the run failed outright; nothing was lost, and the row moves again. */
    fun showStopped() = notify(
        ID_FINISHED,
        finished(
            context.getString(R.string.notification_storage_move_stopped),
            context.getString(R.string.notification_storage_move_again),
            android.R.drawable.stat_notify_error,
        ),
    )

    private fun finished(title: String, text: String?, icon: Int): Notification {
        manager
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(title)
            .apply { text?.let(::setContentText) }
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setContentIntent(openDataSettings())
            .build()
    }

    private fun openDataSettings(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_DATA_SETTINGS,
        MainIntents.newIntent(context, MainDestination.DATA_SETTINGS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    private fun createChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManager.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_storage_move))
                .setShowBadge(false)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "storage_move"
        const val ID_PROGRESS = 801
        const val ID_FINISHED = 802
        private const val REQUEST_DATA_SETTINGS = 81
    }
}
