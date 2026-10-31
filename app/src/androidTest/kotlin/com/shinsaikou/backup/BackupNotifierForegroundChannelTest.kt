package com.shinsaikou.backup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
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
 * Regression for the crash fixed on 2026-09-19: `restoreProgressNotification` and
 * `backupProgressNotification` are the `ForegroundInfo` the import and export workers hand to
 * WorkManager, which posts them itself through `startForeground` and never through
 * `BackupNotifier.notify`. Before the fix, the channel those two functions named was registered
 * only as a side effect of touching the `manager` property, which `notify` did and these two did
 * not, so a fresh process posted a foreground notification on a channel the device had never seen
 * and the platform killed it with `CannotPostForegroundServiceNotificationException`, which it does
 * for any app targeting API 27 or higher. This is what
 * happened on the tablet during the Phase 0 gate run, with `No Channel found for
 * pkg=com.shinsaikou, channelId=backup_restore_progress` in the log underneath it.
 *
 * A JVM test cannot see this: there is no Robolectric in this project, and the property under test
 * is whether a real platform `NotificationManager` has registered a real channel, which only exists
 * on a device. The channel is deleted before each assertion so a pass proves the call under test
 * created it, not that an earlier run of this suite, or the app itself, left it behind.
 *
 * `BackupNotifier` has no accessor on [AppGraph]: nothing in `:app` calls it directly, only the
 * workers do, by constructor injection. So the notifier is built here the same way Metro would
 * build it, off the graph's own [com.shinsaikou.core.common.preferences.SecurityPreferences] plus a
 * fake [BackupIntents] that answers every question with null, which is a legal answer the real
 * intents give too (an automatic backup, for instance) and touches nothing under test.
 */
@RunWith(AndroidJUnit4::class)
class BackupNotifierForegroundChannelTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val app = context.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    private val platformManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val notifier: BackupNotifier
        get() = BackupNotifier(context, graph.securityPreferences, NullBackupIntents)

    @Before
    fun deleteTheProgressChannel() {
        platformManager.deleteNotificationChannel(BackupNotifier.CHANNEL_PROGRESS)
        assertEquals(
            "setup left the progress channel registered before the call under test ran",
            null,
            platformManager.getNotificationChannel(BackupNotifier.CHANNEL_PROGRESS),
        )
    }

    @Test
    fun restoreProgressNotificationRegistersItsChannelWithoutBeingPosted() {
        val notification = notifier.restoreProgressNotification(progress = null, cancelIntent = null)

        assertNotNull(
            "restoreProgressNotification built a notification but never registered " +
                "${BackupNotifier.CHANNEL_PROGRESS}, which startForeground refuses for any app targeting API 27 or higher",
            platformManager.getNotificationChannel(BackupNotifier.CHANNEL_PROGRESS),
        )
        assertEquals(
            "the returned notification names a different channel than the one it registered",
            BackupNotifier.CHANNEL_PROGRESS,
            notification.channelId,
        )
    }

    @Test
    fun backupProgressNotificationRegistersItsChannelWithoutBeingPosted() {
        val notification = notifier.backupProgressNotification()

        assertNotNull(
            "backupProgressNotification built a notification but never registered " +
                "${BackupNotifier.CHANNEL_PROGRESS}, which startForeground refuses for any app targeting API 27 or higher",
            platformManager.getNotificationChannel(BackupNotifier.CHANNEL_PROGRESS),
        )
        assertEquals(
            "the returned notification names a different channel than the one it registered",
            BackupNotifier.CHANNEL_PROGRESS,
            notification.channelId,
        )
    }

    /** Every question answered null, which is a real, legal answer the production intents give too. */
    private object NullBackupIntents : BackupIntents {
        override fun openImportReport(): PendingIntent? = null
        override fun shareBackup(uri: Uri): PendingIntent? = null
    }
}
