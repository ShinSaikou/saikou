package com.shinsaikou.signin

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The browser sign-in's two notifications, on their own channel: the hold's foreground notification
 * while the browser is open, and "Signed in, tap to return" once the code has arrived. Both open the
 * activity that started the sign-in. Neither names the account, the provider's answer or anything
 * else it received.
 *
 * The channel is high importance so the second shows over the browser; the first is silent.
 */
@SingleIn(AppScope::class)
@Inject
class SignInNotifier(private val context: Context) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannel)
    }

    /**
     * Posted by WorkManager through `startForeground`, which crashes the process for a notification
     * whose channel does not exist; touching [manager] first registers it.
     */
    fun waitingNotification(returnIntent: Intent?): Notification {
        manager
        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.notification_sign_in_waiting))
            .setContentText(context.getString(R.string.notification_sign_in_waiting_detail))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .apply { returnIntent?.let { setContentIntent(pendingIntent(it)) } }
            .build()
    }

    /** "Signed in, tap to return": shown once the code has arrived, gone when tapped or after five minutes. */
    fun signedIn(returnIntent: Intent) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.notification_sign_in_done))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(SIGNED_IN_TIMEOUT_MILLIS)
            .setContentIntent(pendingIntent(returnIntent))
            .build()
        if (manager.areNotificationsEnabled()) {
            manager.notify(ID_SIGNED_IN, notification)
        }
    }

    private fun pendingIntent(intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, REQUEST_RETURN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun createChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManager.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_sign_in))
                .setShowBadge(false)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "sign_in"
        const val ID_WAITING = 701
        const val ID_SIGNED_IN = 702

        private const val REQUEST_RETURN = 31
        private const val SIGNED_IN_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}
