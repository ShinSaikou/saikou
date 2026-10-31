package com.shinsaikou.downloads

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.backup.BackupIntents
import com.shinsaikou.backup.BackupNotifier
import com.shinsaikou.di.AppGraph
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.libraryupdate.LibraryUpdateIntents
import com.shinsaikou.libraryupdate.LibraryUpdateNotifier
import com.shinsaikou.messages.AppDownloadMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The download worker's foreground notification is built by [DownloadNotifier.progressNotification]
 * and posted by WorkManager through `startForeground`, never through the notifier, so its channel
 * has to exist before that call or the platform kills the process (ARCHITECTURE.md carry-over 18,
 * R59). This is the pattern of `LibraryUpdateNotifierForegroundChannelTest`, applied to the third
 * foreground channel, plus the check that all three foreground channels share one importance.
 *
 * Every channel is deleted before each test, so a pass proves the calls under test created them,
 * not that an earlier run or the app itself left them behind. The notifiers are built the way Metro
 * builds them, off the graph's own [com.shinsaikou.core.common.preferences.SecurityPreferences], with
 * the production [AppDownloadMessages] and intents that answer every question with null.
 */
@RunWith(AndroidJUnit4::class)
class DownloadNotifierForegroundChannelTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val app = context.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    private val platformManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val foregroundChannels = listOf(
        DownloadNotifier.CHANNEL_PROGRESS,
        LibraryUpdateNotifier.CHANNEL_PROGRESS,
        BackupNotifier.CHANNEL_PROGRESS,
    )

    @Before
    fun deleteTheForegroundChannels() {
        foregroundChannels.forEach(platformManager::deleteNotificationChannel)
        foregroundChannels.forEach {
            assertEquals(
                "setup left $it registered before the call under test ran",
                null,
                platformManager.getNotificationChannel(it),
            )
        }
    }

    @Test
    fun progressNotificationRegistersItsChannelWithoutBeingPosted() {
        val notification = downloadNotifier().progressNotification(progress = null)

        assertNotNull(
            "progressNotification built a notification but never registered " +
                "${DownloadNotifier.CHANNEL_PROGRESS}, which startForeground refuses for any app " +
                "targeting API 27 or higher",
            platformManager.getNotificationChannel(DownloadNotifier.CHANNEL_PROGRESS),
        )
        assertEquals(
            "the returned notification names a different channel than the one it registered",
            DownloadNotifier.CHANNEL_PROGRESS,
            notification.channelId,
        )
    }

    /** The adoption worker's foreground notification rides the same channel, under an id of its own. */
    @Test
    fun adoptionProgressNotificationRegistersTheSameChannel() {
        val notification = downloadNotifier().adoptionProgressNotification(progress = null)

        assertNotNull(
            "adoptionProgressNotification built a notification but never registered " +
                DownloadNotifier.CHANNEL_PROGRESS,
            platformManager.getNotificationChannel(DownloadNotifier.CHANNEL_PROGRESS),
        )
        assertEquals(DownloadNotifier.CHANNEL_PROGRESS, notification.channelId)
    }

    /** The export worker's foreground notification (unit storage-location) rides it too. */
    @Test
    fun exportProgressNotificationRegistersTheSameChannel() {
        val notification = downloadNotifier().exportProgressNotification(progress = null)

        assertNotNull(
            "exportProgressNotification built a notification but never registered " +
                DownloadNotifier.CHANNEL_PROGRESS,
            platformManager.getNotificationChannel(DownloadNotifier.CHANNEL_PROGRESS),
        )
        assertEquals(DownloadNotifier.CHANNEL_PROGRESS, notification.channelId)
    }

    @Test
    fun theDownloadChannelHasTheImportanceOfTheOtherTwoForegroundChannels() {
        downloadNotifier().progressNotification(progress = null)
        LibraryUpdateNotifier(context, graph.securityPreferences, NullLibraryUpdateIntents)
            .progressNotification(FlatLibraryUpdate.Progress(done = 0, total = 0, current = null), null)
        BackupNotifier(context, graph.securityPreferences, NullBackupIntents)
            .restoreProgressNotification(progress = null, cancelIntent = null)

        val importance = foregroundChannels.associateWith { id ->
            val channel = platformManager.getNotificationChannel(id)
            assertNotNull("$id was not registered by its own progress notification", channel)
            channel.importance
        }

        assertEquals(
            "the library update and backup foreground channels no longer agree, so there is no one " +
                "importance for the download channel to match: $importance",
            importance.getValue(LibraryUpdateNotifier.CHANNEL_PROGRESS),
            importance.getValue(BackupNotifier.CHANNEL_PROGRESS),
        )
        assertEquals(
            "the download foreground channel's importance differs from the other two: $importance",
            importance.getValue(LibraryUpdateNotifier.CHANNEL_PROGRESS),
            importance.getValue(DownloadNotifier.CHANNEL_PROGRESS),
        )
    }

    private fun downloadNotifier() =
        DownloadNotifier(context, graph.securityPreferences, AppDownloadMessages(context), NullDownloadIntents)

    /** Every question answered null, which is a real, legal answer the production intents give too. */
    private object NullLibraryUpdateIntents : LibraryUpdateIntents {
        override fun openUpdates(): PendingIntent? = null
        override fun openManga(mangaId: Long): PendingIntent? = null
        override fun openChapter(mangaId: Long, chapterId: Long): PendingIntent? = null
        override fun markChaptersRead(mangaId: Long, chapterIds: List<Long>, notificationId: Int): PendingIntent? = null
        override fun openUpdateErrors(): PendingIntent? = null
    }

    private object NullDownloadIntents : DownloadIntents {
        override fun openQueue(): PendingIntent? = null
        override fun openDownloadSettings(): PendingIntent? = null
        override fun pauseDownloads(): PendingIntent? = null
        override fun cancelDownloads(): PendingIntent? = null
    }

    private object NullBackupIntents : BackupIntents {
        override fun openImportReport(): PendingIntent? = null
        override fun shareBackup(uri: Uri): PendingIntent? = null
    }
}
