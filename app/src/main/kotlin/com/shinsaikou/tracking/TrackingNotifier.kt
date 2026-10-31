package com.shinsaikou.tracking

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * `:tracking`'s [TrackerSignInNotifier] as a notification on its own channel: "<tracker> needs a new
 * sign-in", one per tracker, opening Settings > Tracking, where Sign in again is. It names the
 * tracker and nothing the service answered.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class TrackingNotifier(private val context: Context) : TrackerSignInNotifier {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannel)
    }

    override fun expired(tracker: Tracker) {
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            REQUEST_TRACKING_SETTINGS,
            MainIntents.newIntent(context, MainDestination.TRACKING_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.notification_tracker_sign_in_expired_detail)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.notification_tracker_sign_in_expired, tracker.name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.stat_notify_sync, context.getString(R.string.tracking_sign_in_again), open)
            .build()
        manager.notify(idOf(tracker), notification)
    }

    override fun signedIn(tracker: Tracker) {
        manager.cancel(idOf(tracker))
    }

    private fun createChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManager.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_tracking))
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "tracking"

        /** One notification per tracker: 900 plus its id, apart from every other notifier's. */
        fun idOf(tracker: Tracker): Int = 900 + tracker.id.toInt()

        /** Apart from the library update, backup, download, sign-in, sync and storage request codes. */
        private const val REQUEST_TRACKING_SETTINGS = 91
    }
}
