package com.shinsaikou.reader

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Upstream's `SaveImageNotifier`: once a page is saved, a notification with the page as a big
 * picture, a tap that opens it and a Share action. Each save replaces the one before.
 *
 * Only a content address can be handed to another app. A page saved on API 26 to 28 lands in the
 * app's own pictures folder as a file address, which no other app may open, so there the
 * notification has the picture and no tap or action.
 */
@SingleIn(AppScope::class)
@Inject
class SavedPageNotifier(private val context: Context) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannel)
    }

    /** Called off the main thread: it decodes the preview. [caption] goes with the Share action. */
    fun showSaved(uri: Uri, mimeType: String, caption: String) {
        if (!manager.areNotificationsEnabled()) return
        val preview = preview(uri)
        val shareable = uri.scheme == ContentResolver.SCHEME_CONTENT
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.picture_saved))
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setAutoCancel(true)
            .apply {
                preview?.let {
                    setLargeIcon(it)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?))
                }
                if (shareable) {
                    setContentIntent(activity(REQUEST_OPEN, viewIntent(uri, mimeType)))
                    addAction(
                        android.R.drawable.ic_menu_share,
                        context.getString(R.string.action_share),
                        activity(REQUEST_SHARE, shareChooser(uri, mimeType, caption)),
                    )
                }
            }
            .build()
        manager.notify(ID_SAVED, notification)
    }

    private fun viewIntent(uri: Uri, mimeType: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activity(request: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context,
        request,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The page at most [PREVIEW_WIDTH] by [PREVIEW_HEIGHT], as upstream sizes it, or null for a
     * format the platform decoder cannot read; the notification then goes without a picture.
     */
    private fun preview(uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight)
        }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun createChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManager.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_saved_pages))
                .setShowBadge(false)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "saved_pages"
        const val ID_SAVED = 301
        private const val REQUEST_OPEN = 31
        private const val REQUEST_SHARE = 32
    }
}

private const val PREVIEW_WIDTH = 720
private const val PREVIEW_HEIGHT = 1280

/**
 * The smallest power of two that fits a [width] by [height] page inside the preview's box, as
 * upstream's 720 by 1280 request fits it. A notification's picture crosses to the system whole, so
 * a full size page is never sent.
 */
internal fun previewSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > PREVIEW_WIDTH || height / sample > PREVIEW_HEIGHT) sample *= 2
    return sample
}
