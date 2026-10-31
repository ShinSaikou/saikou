package com.shinsaikou.libraryupdate

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the crash fixed on 2026-09-19: `progressNotification` is the `ForegroundInfo` the
 * library update worker hands to WorkManager, which posts it itself through `startForeground` and
 * never through `LibraryUpdateNotifier.notify`. Before the fix, [LibraryUpdateNotifier.CHANNEL_PROGRESS]
 * was registered only as a side effect of touching the `manager` property, which `notify` did and
 * `progressNotification` did not, so a fresh process posted a foreground notification on a channel
 * the device had never seen and the platform killed it with
 * `CannotPostForegroundServiceNotificationException`, which it does for any app targeting API 27 or
 * higher. The Phase 0 gate run of 2026-09-19 shows the
 * same failure under this notifier's own channel, `No Channel found for pkg=com.shinsaikou,
 * channelId=library_progress`, one process over from where the backup one was caught.
 *
 * A JVM test cannot see this: there is no Robolectric in this project, and the property under test
 * is whether a real platform `NotificationManager` has registered a real channel, which only exists
 * on a device. The channel is deleted before the assertion so a pass proves the call under test
 * created it, not that an earlier run of this suite, or the app itself, left it behind.
 *
 * `LibraryUpdateNotifier` has no accessor on [AppGraph]: nothing in `:app` calls it directly, only
 * the worker does, by constructor injection. So the notifier is built here the same way Metro would
 * build it, off the graph's own [com.shinsaikou.core.common.preferences.SecurityPreferences] and
 * [com.shinsaikou.source.SourceManager], plus a fake [LibraryUpdateIntents] that answers every question
 * with null, which is a legal answer the real intents give too and touches nothing under test.
 */
@RunWith(AndroidJUnit4::class)
class LibraryUpdateNotifierForegroundChannelTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val app = context.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    private val platformManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notifier: LibraryUpdateNotifier
        get() = LibraryUpdateNotifier(context, graph.securityPreferences, NullLibraryUpdateIntents)

    @Before
    fun deleteTheProgressChannel() {
        platformManager.deleteNotificationChannel(LibraryUpdateNotifier.CHANNEL_PROGRESS)
        assertEquals(
            "setup left the progress channel registered before the call under test ran",
            null,
            platformManager.getNotificationChannel(LibraryUpdateNotifier.CHANNEL_PROGRESS),
        )
    }

    @Test
    fun progressNotificationRegistersItsChannelWithoutBeingPosted() {
        val progress = FlatLibraryUpdate.Progress(done = 0, total = 0, current = null)

        val notification = notifier.progressNotification(progress, cancelIntent = null)

        assertNotNull(
            "progressNotification built a notification but never registered " +
                "${LibraryUpdateNotifier.CHANNEL_PROGRESS}, which startForeground refuses for any app " +
                "targeting API 27 or higher",
            platformManager.getNotificationChannel(LibraryUpdateNotifier.CHANNEL_PROGRESS),
        )
        assertEquals(
            "the returned notification names a different channel than the one it registered",
            LibraryUpdateNotifier.CHANNEL_PROGRESS,
            notification.channelId,
        )
    }

    /** Every question answered null, which is a real, legal answer the production intents give too. */
    private object NullLibraryUpdateIntents : LibraryUpdateIntents {
        override fun openUpdates(): PendingIntent? = null
        override fun openManga(mangaId: Long): PendingIntent? = null
        override fun openChapter(mangaId: Long, chapterId: Long): PendingIntent? = null
        override fun markChaptersRead(mangaId: Long, chapterIds: List<Long>, notificationId: Int): PendingIntent? = null
        override fun openUpdateErrors(): PendingIntent? = null
    }
}
