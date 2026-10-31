package com.shinsaikou.appupdate

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
 * The app update's notifications, on two channels: the download's progress on [CHANNEL_PROGRESS],
 * and on [CHANNEL_STATUS] the one line that says how it ended when Android's own install prompt
 * cannot say it: ready to install (the prompt could not open over another app), refused, or failed.
 * Every one but the install prompt opens Settings > Advanced, where "Check for updates" offers the
 * release again.
 */
@SingleIn(AppScope::class)
@Inject
class AppUpdateNotifier(private val context: Context) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannels)
    }

    private val appName: String
        get() = context.getString(R.string.app_name)

    /**
     * Built here and posted by WorkManager through `startForeground`, which crashes the process for a
     * notification whose channel does not exist; touching [manager] first registers it.
     */
    fun progressNotification(version: String, done: Long = 0L, total: Long = -1L): Notification {
        manager
        val known = total > 0L
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(context.getString(R.string.notification_app_update_downloading, appName, version))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (known) PROGRESS_MAX else 0, if (known) (done * PROGRESS_MAX / total).toInt() else 0, !known)
            .build()
    }

    fun showProgress(version: String, done: Long, total: Long) = notify(ID_PROGRESS, progressNotification(version, done, total))

    fun cancelProgress() = manager.cancel(ID_PROGRESS)

    /**
     * Android's install prompt for [version], when it could not be opened at once because the app
     * was not on screen: a tap opens it.
     */
    fun showReadyToInstall(version: String, confirm: Intent, session: Int?) {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CONFIRM_INSTALL,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setContentTitle(context.getString(R.string.notification_app_update_ready, appName, version))
            .setContentText(context.getString(R.string.notification_app_update_ready_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notify(ID_STATUS, notification, readyTag(session))
    }

    fun showRefused(version: String, refusal: UpdateRefusal) = showFailed(
        version,
        when (refusal) {
            UpdateRefusal.DIGEST_MISMATCH -> R.string.app_update_refused_digest
            UpdateRefusal.UNREADABLE -> R.string.app_update_refused_unreadable
            UpdateRefusal.OTHER_PACKAGE -> R.string.app_update_refused_package
            UpdateRefusal.OTHER_SIGNER -> R.string.app_update_refused_signer
            UpdateRefusal.NOT_NEWER -> R.string.app_update_refused_not_newer
        },
    )

    fun showDownloadFailed(version: String) = showFailed(version, R.string.app_update_download_failed)

    /** A `PackageInstaller` failure status, in the words for the causes an owner can do something about. */
    fun showInstallFailed(version: String, status: Int, session: Int? = null) = showFailed(
        version,
        when (status) {
            PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.app_update_install_failed_storage
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.app_update_install_failed_incompatible
            PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.app_update_install_failed_conflict
            PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.app_update_install_failed_blocked
            else -> R.string.app_update_install_failed
        },
        session,
    )

    /**
     * Clears the status lines of one install session (its prompt or its failure), or with no
     * [session] the one a download run posts. Tagged per session, so a session that ends late never
     * clears a newer attempt's line.
     */
    fun cancelStatus(session: Int? = null) {
        if (session == null) {
            manager.cancel(ID_STATUS)
        } else {
            manager.cancel(readyTag(session), ID_STATUS)
            manager.cancel(failedTag(session), ID_STATUS)
        }
    }

    /**
     * What a new download starts by clearing: the download run's own line and every earlier
     * session's failure, so failures never pile up. An earlier session still waiting for Android's
     * confirmation keeps its prompt; the new run's install abandons that session, and its aborted
     * status then clears the prompt.
     */
    fun cancelStaleStatus() {
        manager.cancel(ID_STATUS)
        manager.activeNotifications
            .filter { it.id == ID_STATUS && it.tag?.startsWith(FAILED_TAG) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun readyTag(session: Int?): String? = session?.let { "$READY_TAG$it" }

    private fun failedTag(session: Int?): String? = session?.let { "$FAILED_TAG$it" }

    private fun showFailed(version: String, reason: Int, session: Int? = null) {
        manager
        val text = context.getString(reason)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_ADVANCED_SETTINGS,
            MainIntents.newIntent(context, MainDestination.ADVANCED_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setContentTitle(context.getString(R.string.notification_app_update_failed, appName, version))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notify(ID_STATUS, notification, failedTag(session))
    }

    private fun notify(id: Int, notification: Notification, tag: String? = null) {
        if (manager.areNotificationsEnabled()) manager.notify(tag, id, notification)
    }

    private fun createChannels(manager: NotificationManagerCompat) {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManager.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_app_update_progress))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_STATUS, NotificationManager.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_app_update))
                    .build(),
            ),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "app_update_progress"
        const val CHANNEL_STATUS = "app_update"
        const val ID_PROGRESS = 901
        const val ID_STATUS = 902

        /**
         * Apart from the library update (1 to 5), backup (11, 12), download (21, 22), sign-in (31),
         * sync (41) and storage (81) codes; the installer's status broadcast is [REQUEST_INSTALL_STATUS].
         */
        private const val REQUEST_ADVANCED_SETTINGS = 91
        private const val REQUEST_CONFIRM_INSTALL = 92
        const val REQUEST_INSTALL_STATUS = 93

        private const val PROGRESS_MAX = 100
        private const val READY_TAG = "app-update-ready-"
        private const val FAILED_TAG = "app-update-failed-"
    }
}
