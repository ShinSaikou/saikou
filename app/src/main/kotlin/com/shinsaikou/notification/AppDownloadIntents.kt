package com.shinsaikou.notification

import android.app.PendingIntent
import android.content.Context
import com.shinsaikou.downloads.DownloadIntents
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Where a download notification sends the owner, and the [NotificationReceiver] broadcasts its
 * actions send. `:downloads` owns the notifications and may not depend on `:app`, so the intent
 * arrives through this interface. The request code is its own, apart
 * from the library update and backup codes, so no notification reuses another's extras.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppDownloadIntents(private val context: Context) : DownloadIntents {

    override fun openQueue(): PendingIntent? = PendingIntent.getActivity(
        context,
        REQUEST_DOWNLOAD_QUEUE,
        MainIntents.newIntent(context, MainDestination.DOWNLOAD_QUEUE),
        FLAGS,
    )

    override fun openDownloadSettings(): PendingIntent? = PendingIntent.getActivity(
        context,
        REQUEST_DOWNLOAD_SETTINGS,
        MainIntents.newIntent(context, MainDestination.DOWNLOAD_SETTINGS),
        FLAGS,
    )

    override fun pauseDownloads(): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_PAUSE_DOWNLOADS,
        NotificationReceiver.pauseDownloads(context),
        FLAGS,
    )

    override fun cancelDownloads(): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CANCEL_DOWNLOADS,
        NotificationReceiver.cancelDownloads(context),
        FLAGS,
    )

    private companion object {
        const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        const val REQUEST_DOWNLOAD_QUEUE = 21
        const val REQUEST_DOWNLOAD_SETTINGS = 22
        const val REQUEST_PAUSE_DOWNLOADS = 23
        const val REQUEST_CANCEL_DOWNLOADS = 24
    }
}
