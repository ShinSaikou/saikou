package com.shinsaikou.extensionupdate

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The one notification of an automatic extension update run, on a low-importance channel of its
 * own: which extensions it updated, which it could not, and which wait for the owner. A tap opens
 * Browse > Extensions. Extension names say which sources the owner reads, so with
 * [SecurityPreferences.hideNotificationContent] on only the counts are shown.
 */
@SingleIn(AppScope::class)
@Inject
class ExtensionUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannel)
    }

    /** Posts nothing for a run that found nothing to do. */
    fun showFinished(outcome: ExtensionAutoUpdate.Outcome) {
        if (outcome.isEmpty) return
        val title = context.getString(
            if (outcome.updated.isNotEmpty()) R.string.notification_extensions_updated else R.string.notification_extensions_not_updated,
        )
        val lines = if (securityPreferences.hideNotificationContent.get()) countLines(outcome) else nameLines(outcome)
        val text = lines.joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(
                if (outcome.updated.isNotEmpty()) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error,
            )
            .setAutoCancel(true)
            .setContentIntent(openExtensions())
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(ID_FINISHED, notification)
    }

    private fun nameLines(outcome: ExtensionAutoUpdate.Outcome): List<String> = buildList {
        if (outcome.updated.isNotEmpty()) {
            add(context.getString(R.string.notification_extensions_updated_names, outcome.updated.joinToString()))
        }
        if (outcome.failed.isNotEmpty()) {
            add(context.getString(R.string.notification_extensions_failed_names, outcome.failed.joinToString()))
        }
        if (outcome.needsYou.isNotEmpty()) {
            add(context.getString(R.string.notification_extensions_needs_you_names, outcome.needsYou.joinToString()))
        }
    }

    private fun countLines(outcome: ExtensionAutoUpdate.Outcome): List<String> = buildList {
        if (outcome.updated.isNotEmpty()) add(quantity(R.plurals.notification_extensions_updated_count, outcome.updated.size))
        if (outcome.failed.isNotEmpty()) add(quantity(R.plurals.notification_extensions_failed_count, outcome.failed.size))
        if (outcome.needsYou.isNotEmpty()) add(quantity(R.plurals.notification_extensions_needs_you_count, outcome.needsYou.size))
    }

    private fun quantity(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    private fun openExtensions(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_EXTENSIONS,
        MainIntents.newIntent(context, MainDestination.EXTENSIONS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManager.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_extension_updates))
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "extension_updates"
        const val ID_FINISHED = 1001

        /** Apart from the other notifiers' request codes (1 to 93). */
        private const val REQUEST_EXTENSIONS = 101
    }
}
