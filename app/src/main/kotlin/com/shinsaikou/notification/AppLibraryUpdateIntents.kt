package com.shinsaikou.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shinsaikou.libraryupdate.LibraryUpdateIntents
import com.shinsaikou.reader.ReaderIntents
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The activities and the receiver a library update notification can reach. `:library-update` owns
 * the notifications and may not depend on `:app`, so the intents arrive through this interface.
 *
 * Two pending intents are one when their request codes match and their intents differ only in
 * extras, and then a second notification silently takes over the first one's extras. Each kind here
 * has its own action or activity, so the code only has to tell one entry, chapter or notification
 * from another of the same kind, and the ones that carry an id use the whole id ([requestCode]).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppLibraryUpdateIntents(private val context: Context) : LibraryUpdateIntents {

    override fun openUpdates(): PendingIntent? =
        activity(REQUEST_UPDATES, MainIntents.newIntent(context, MainDestination.UPDATES))

    override fun openManga(mangaId: Long): PendingIntent? = activity(
        requestCode(mangaId),
        MainIntents.newIntent(context, MainDestination.ENTRY, mangaId),
    )

    /**
     * The entry's screen under the reader. The reader alone was the whole task on a cold start, so
     * Back from the chapter left the app; now it lands on the entry. With the app already open, the
     * entry intent brings its task forward and the reader opens over the entry there. The last
     * intent is the one the platform compares, so the chapter's id keeps two of these apart.
     */
    override fun openChapter(mangaId: Long, chapterId: Long): PendingIntent? = PendingIntent.getActivities(
        context,
        requestCode(chapterId),
        arrayOf(
            MainIntents.newIntent(context, MainDestination.ENTRY, mangaId),
            ReaderIntents.newIntent(context, mangaId, chapterId),
        ),
        FLAGS,
    )

    override fun markChaptersRead(mangaId: Long, chapterIds: List<Long>, notificationId: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode(notificationId.toLong()),
            NotificationReceiver.markAsRead(context, chapterIds, notificationId),
            FLAGS,
        )

    override fun openUpdateErrors(): PendingIntent? =
        activity(REQUEST_UPDATE_ERRORS, MainIntents.newIntent(context, MainDestination.UPDATE_ERRORS))

    private fun activity(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, FLAGS)

    private companion object {
        const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        const val REQUEST_UPDATES = 1
        const val REQUEST_UPDATE_ERRORS = 2

        /**
         * The id itself, kept positive, which is distinct for every id below `Int.MAX_VALUE`. It used
         * to keep 16 bits of the id inside a band per kind, so two chapters 65,536 ids apart shared
         * one intent and the earlier notification opened the later one's chapter.
         */
        fun requestCode(id: Long): Int = id.mod(Int.MAX_VALUE)
    }
}
