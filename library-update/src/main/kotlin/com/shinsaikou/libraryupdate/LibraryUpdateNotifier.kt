package com.shinsaikou.libraryupdate

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.lang.chop
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.math.RoundingMode
import java.text.NumberFormat

/**
 * Every notification the library update posts. The channels belong to this module and are created
 * on first use, because `:app` has no knowledge of them.
 *
 * Nothing here logs or renders a source name: the only text taken from outside the app is a manga
 * title, and that is hidden when [SecurityPreferences.hideNotificationContent] is on.
 */
@SingleIn(AppScope::class)
@Inject
class LibraryUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val intents: LibraryUpdateIntents,
) {

    private val manager by lazy {
        NotificationManagerCompat.from(context).also(::createChannels)
    }

    private val chapterNumberFormat = NumberFormat.getInstance().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 2
        isGroupingUsed = false
    }

    /** The ongoing progress notification, also used as the worker's foreground notification. */
    fun progressNotification(progress: FlatLibraryUpdate.Progress, cancelIntent: PendingIntent?): Notification {
        val builder = builderOn(CHANNEL_PROGRESS)
            .setContentTitle(context.getString(R.string.notification_updating_library))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progress.total, progress.done, progress.total == 0)
        if (!securityPreferences.hideNotificationContent.get()) {
            progress.current?.let { builder.setContentText(it.displayTitle.chop(TITLE_MAX_LENGTH)) }
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

    fun showProgress(progress: FlatLibraryUpdate.Progress, cancelIntent: PendingIntent?) {
        notify(ID_LIBRARY_PROGRESS, progressNotification(progress, cancelIntent))
    }

    fun cancelProgress() {
        manager.cancel(ID_LIBRARY_PROGRESS)
    }

    /**
     * Whether the progress notification is up. After a process died during a run the system can
     * bring WorkManager's foreground service back with that run's notification, before any run of
     * the new process has entered the foreground.
     */
    fun isProgressShown(): Boolean = manager.activeNotifications.any { it.id == ID_LIBRARY_PROGRESS }

    /**
     * One summary that names every updated entry plus, unless notification content is hidden, one
     * notification for each of the first [MAX_ENTRY_NOTIFICATIONS] entries. Android drops an app's
     * notifications past about fifty, so an uncapped run of a large library lost entries, and the
     * summary with them, at random.
     */
    fun showNewChapters(updated: Map<Manga, List<Chapter>>) {
        if (updated.isEmpty()) return
        val hideContent = securityPreferences.hideNotificationContent.get()

        val summary = builderOn(CHANNEL_NEW_CHAPTERS)
            .setContentTitle(context.getString(R.string.notification_new_chapters))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setGroup(GROUP_NEW_CHAPTERS)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(intents.openUpdates())
        if (hideContent) {
            summary.setContentText(newChaptersSummary(updated.size))
        } else if (updated.size == 1) {
            summary.setContentText(updated.keys.first().displayTitle.chop(TITLE_MAX_LENGTH))
        } else {
            summary.setContentText(newChaptersSummary(updated.size))
            summary.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    updated.keys.joinToString("\n") { it.displayTitle.chop(TITLE_MAX_LENGTH) },
                ),
            )
        }
        notify(ID_LIBRARY_COMPLETE, summary.build())

        if (hideContent) return
        updated.entries.take(MAX_ENTRY_NOTIFICATIONS).forEach { (manga, chapters) ->
            notify(newChaptersId(manga.id), newChaptersNotification(manga, chapters))
        }
    }

    fun showErrors(count: Int) {
        if (count == 0) return
        notify(
            ID_LIBRARY_ERROR,
            builderOn(CHANNEL_ERRORS)
                .setContentTitle(context.getString(R.string.notification_update_error, count))
                .setContentText(context.getString(R.string.notification_update_error_action))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(intents.openUpdateErrors())
                .build(),
        )
    }

    private fun newChaptersNotification(manga: Manga, chapters: List<Chapter>): Notification {
        val notificationId = newChaptersId(manga.id)
        val description = newChaptersDescription(chapters)
        val builder = builderOn(CHANNEL_NEW_CHAPTERS)
            .setContentTitle(manga.displayTitle)
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(description))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setGroup(GROUP_NEW_CHAPTERS)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(intents.openChapter(manga.id, chapters.first().id))

        intents.markChaptersRead(manga.id, chapters.map { it.id }, notificationId)?.let {
            builder.addAction(
                android.R.drawable.checkbox_on_background,
                context.getString(R.string.action_mark_as_read),
                it,
            )
        }
        intents.openManga(manga.id)?.let {
            builder.addAction(
                android.R.drawable.ic_menu_agenda,
                context.getString(R.string.action_view_chapters),
                it,
            )
        }
        return builder.build()
    }

    /** Upstream's getNewChaptersDescription: up to five recognised numbers, else a plain count. */
    private fun newChaptersDescription(chapters: List<Chapter>): String {
        val numbers = chapters
            .filter { it.isRecognizedNumber }
            .sortedBy { it.chapterNumber }
            .map { chapterNumberFormat.format(it.chapterNumber) }
            .toSet()

        return when {
            numbers.isEmpty() -> context.resources.getQuantityString(
                R.plurals.notification_chapters_generic,
                chapters.size,
                chapters.size,
            )
            numbers.size == 1 -> {
                val remaining = chapters.size - 1
                if (remaining == 0) {
                    context.getString(R.string.notification_chapters_single, numbers.first())
                } else {
                    context.getString(R.string.notification_chapters_single_and_more, numbers.first(), remaining)
                }
            }
            numbers.size > MAX_LISTED_CHAPTERS -> context.getString(
                R.string.notification_chapters_multiple_and_more,
                numbers.take(MAX_LISTED_CHAPTERS).joinToString(", "),
                numbers.size - MAX_LISTED_CHAPTERS,
            )
            else -> context.getString(R.string.notification_chapters_multiple, numbers.joinToString(", "))
        }
    }

    private fun newChaptersSummary(count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_new_chapters_summary, count, count)

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
     * `No Channel found for pkg=com.shinsaikou, channelId=library_progress`.
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
                    .setName(context.getString(R.string.channel_library_progress))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_NEW_CHAPTERS, NotificationManager.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_new_chapters))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ERRORS, NotificationManager.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_library_errors))
                    .build(),
            ),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "library_progress"
        const val CHANNEL_NEW_CHAPTERS = "library_new_chapters"
        const val CHANNEL_ERRORS = "library_errors"
        const val GROUP_NEW_CHAPTERS = "com.shinsaikou.NEW_CHAPTERS"

        const val ID_LIBRARY_PROGRESS = 101
        const val ID_LIBRARY_COMPLETE = 102
        const val ID_LIBRARY_ERROR = 103
        /** Retired, keep free: the pre-run warning about one source's queue (F7, update-site-notice). */
        const val ID_LIBRARY_SIZE_WARNING = 104

        /**
         * Per-manga ids sit above every fixed id of the app and stay inside the positive int range,
         * one for each entry id below about two billion. They used to keep 15 bits of the id, so two
         * entries 32,768 ids apart shared one notification.
         */
        fun newChaptersId(mangaId: Long): Int =
            NEW_CHAPTERS_ID_BASE + mangaId.mod(Int.MAX_VALUE - NEW_CHAPTERS_ID_BASE)

        /** Entries past this many are named in the summary only. */
        const val MAX_ENTRY_NOTIFICATIONS = 20

        private const val NEW_CHAPTERS_ID_BASE = 1000
        private const val TITLE_MAX_LENGTH = 45
        private const val MAX_LISTED_CHAPTERS = 5
    }
}

