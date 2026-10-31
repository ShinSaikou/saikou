package com.shinsaikou.ui.main

import com.shinsaikou.navigation.BrowsePage
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Where an intent from outside the app lands, read off the host's own mapping rather than a copy.
 *
 * The failed-update notification is the case that matters (ruling R43): it opens the Updates tab
 * with the error dialog up, beside the new-chapters notification of the same run, which opens the
 * same tab without it. Before this test nothing pinned either half, so a refactor of the mapping
 * could have sent the notification to the library, or to Updates with no dialog, and stayed green.
 */
class ArrivalTest {

    @Test
    fun `the update errors notification lands on updates with the dialog requested`() {
        MainIntents.Target(MainDestination.UPDATE_ERRORS).toArrival() shouldBe
            Arrival(route = Route.Updates, showUpdateErrors = true)
    }

    @Test
    fun `the new chapters notification lands on the same tab without the dialog`() {
        MainIntents.Target(MainDestination.UPDATES).toArrival() shouldBe
            Arrival(route = Route.Updates, showUpdateErrors = false)
    }

    @Test
    fun `no other destination asks for the update errors dialog`() {
        val asking = MainDestination.entries.filter { MainIntents.Target(it).toArrival().showUpdateErrors }

        asking shouldContainExactly listOf(MainDestination.UPDATE_ERRORS)
    }

    /** Both download notifications open the queue, where a failed row can be retried. */
    @Test
    fun `the download notifications land on the download queue`() {
        MainIntents.Target(MainDestination.DOWNLOAD_QUEUE).toArrival() shouldBe
            Arrival(route = Route.DownloadQueue, showUpdateErrors = false)
    }

    /** The adoption notifications open the Downloads section of the settings, where the summary is. */
    @Test
    fun `the adoption notifications land on the download settings`() {
        MainIntents.Target(MainDestination.DOWNLOAD_SETTINGS).toArrival() shouldBe
            Arrival(route = Route.Settings(SettingsSection.DOWNLOADS), showUpdateErrors = false)
    }

    /** Sync's notices open the Sync section of the settings, where Sign in again and Sync now are (fixes unit sync-notices). */
    @Test
    fun `the sync notices land on the sync settings`() {
        MainIntents.Target(MainDestination.SYNC_SETTINGS).toArrival() shouldBe
            Arrival(route = Route.Settings(SettingsSection.SYNC), showUpdateErrors = false)
    }

    /** The storage location move's notifications open the Data section, where the Storage location row is. */
    @Test
    fun `the storage move notifications land on the data settings`() {
        MainIntents.Target(MainDestination.DATA_SETTINGS).toArrival() shouldBe
            Arrival(route = Route.Settings(SettingsSection.DATA), showUpdateErrors = false)
    }

    /** A tracker's "sign in again" notice opens the Tracking section, where Sign in again is. */
    @Test
    fun `the tracker sign-in notice lands on the tracking settings`() {
        MainIntents.Target(MainDestination.TRACKING_SETTINGS).toArrival() shouldBe
            Arrival(route = Route.Settings(SettingsSection.TRACKING), showUpdateErrors = false)
    }

    /** The Restart action of Settings > Advanced reopens that section in the new process (F13). */
    @Test
    fun `a restart from the advanced settings lands on the advanced settings`() {
        MainIntents.Target(MainDestination.ADVANCED_SETTINGS).toArrival() shouldBe
            Arrival(route = Route.Settings(SettingsSection.ADVANCED), showUpdateErrors = false)
    }

    /** The restore's completion notification opens the restore report (unit restore-report). */
    @Test
    fun `the import report notification lands on the restore report`() {
        MainIntents.Target(MainDestination.IMPORT_REPORT).toArrival() shouldBe
            Arrival(route = Route.ImportReport, showUpdateErrors = false)
    }

    @Test
    fun `payload destinations reach their screens and fall back to the library without one`() {
        MainIntents.Target(MainDestination.ENTRY, mangaId = 7L).toArrival().route shouldBe Route.MangaDetail(7L)
        MainIntents.Target(MainDestination.ENTRY).toArrival().route shouldBe Route.Library
        MainIntents.Target(MainDestination.RESTORE_BACKUP, dataUri = "content://x/y").toArrival().route shouldBe
            Route.BackupRestore("content://x/y")
        MainIntents.Target(MainDestination.RESTORE_BACKUP).toArrival().route shouldBe Route.Library
        MainIntents.Target(MainDestination.EXTENSIONS).toArrival().route shouldBe Route.Browse(BrowsePage.EXTENSIONS)
    }
}
