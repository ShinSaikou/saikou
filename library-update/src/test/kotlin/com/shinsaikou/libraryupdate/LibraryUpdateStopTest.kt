package com.shinsaikou.libraryupdate

import android.content.Context
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.domain.preferences.LibraryPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.UUID

/**
 * Stopping a library update, from the Library screen or the notification's Cancel, stops the run
 * going now and nothing else. Both used to cancel every request tagged [LibraryUpdateWorker.TAG],
 * and the periodic request carries that tag while it waits, so a manual cancel removed the
 * automatic schedule until the next start-up or settings change.
 */
class LibraryUpdateStopTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val manual = UUID.randomUUID()
    private val automatic = UUID.randomUUID()
    private val scheduler = LibraryUpdateScheduler(mockk(), LibraryPreferences(InMemoryPreferenceStore()))

    @BeforeEach
    fun setUp() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
    }

    @Test
    fun `stopping a manual run leaves the waiting periodic request enqueued`() = runTest {
        runs(manualState = WorkInfo.State.RUNNING, automaticState = WorkInfo.State.ENQUEUED)

        scheduler.stop()

        verify { workManager.cancelWorkById(manual) }
        verify(exactly = 0) { workManager.cancelWorkById(automatic) }
        verify(exactly = 0) { workManager.cancelAllWorkByTag(any()) }
        verify(exactly = 0) { workManager.cancelUniqueWork(LibraryUpdateWorker.WORK_NAME_AUTO) }
    }

    @Test
    fun `stopping an automatic run cancels that run by its id`() = runTest {
        runs(manualState = null, automaticState = WorkInfo.State.RUNNING)

        scheduler.stop()

        verify(exactly = 1) { workManager.cancelWorkById(automatic) }
        verify(exactly = 0) { workManager.cancelAllWorkByTag(any()) }
    }

    @Test
    fun `the notification's cancel stops its own run and nothing tagged with it`() {
        val intent = mockk<Intent> {
            every { getStringExtra(LibraryUpdateCancelReceiver.EXTRA_RUN_ID) } returns manual.toString()
        }

        LibraryUpdateCancelReceiver().onReceive(mockk(), intent)

        verify(exactly = 1) { workManager.cancelWorkById(manual) }
        verify(exactly = 0) { workManager.cancelAllWorkByTag(any()) }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `a cancel with no run id cancels nothing`() {
        val intent = mockk<Intent> { every { getStringExtra(any()) } returns null }

        LibraryUpdateCancelReceiver().onReceive(mockk(), intent)

        verify(exactly = 0) { workManager.cancelWorkById(any()) }
        verify(exactly = 0) { workManager.cancelAllWorkByTag(any()) }
    }

    /** What WorkManager holds under the tag: a manual run in [manualState], if any, and the periodic one. */
    private fun runs(manualState: WorkInfo.State?, automaticState: WorkInfo.State) {
        val infos = listOfNotNull(
            manualState?.let {
                WorkInfo(manual, it, setOf(LibraryUpdateWorker.TAG, LibraryUpdateWorker.WORK_NAME_MANUAL))
            },
            WorkInfo(automatic, automaticState, setOf(LibraryUpdateWorker.TAG, LibraryUpdateWorker.WORK_NAME_AUTO)),
        )
        every { workManager.getWorkInfosByTag(LibraryUpdateWorker.TAG) } returns
            mockk<ListenableFuture<List<WorkInfo>>> { every { get() } returns infos }
    }
}
