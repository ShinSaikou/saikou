package com.shinsaikou.libraryupdate

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.domain.preferences.LibraryPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * A manual run over chosen entries, for Retry on the update errors and the library selection's
 * Update. The ids travel in the request's input data, which WorkManager refuses past 10 KB, so a
 * run names at most [LibraryUpdateWorker.MAX_MANGA_IDS] of them.
 */
class LibraryUpdateSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val request = slot<OneTimeWorkRequest>()
    private val scheduler =
        LibraryUpdateScheduler(mockk(), LibraryPreferences(InMemoryPreferenceStore()), StartMode.NORMAL)

    @BeforeEach
    fun setUp() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.getWorkInfosByTag(LibraryUpdateWorker.TAG) } returns
            mockk<ListenableFuture<List<WorkInfo>>> { every { get() } returns emptyList() }
        every {
            workManager.enqueueUniqueWork(
                LibraryUpdateWorker.WORK_NAME_MANUAL,
                ExistingWorkPolicy.REPLACE,
                capture(request),
            )
        } returns mockk()
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
    }

    @Test
    fun `a run over chosen entries names them in its input`() = runTest {
        scheduler.startNow(mangaIds = listOf(4L, 7L)) shouldBe true

        val named = request.captured.workSpec.input.getLongArray(LibraryUpdateWorker.KEY_MANGA_IDS)
        named?.toList() shouldBe listOf(4L, 7L)
    }

    @Test
    fun `a run over the categories names no entries`() = runTest {
        scheduler.startNow() shouldBe true

        request.captured.workSpec.input.getLongArray(LibraryUpdateWorker.KEY_MANGA_IDS) shouldBe null
    }

    @Test
    fun `the most entries a run can name fit its input`() {
        val ids = LongArray(LibraryUpdateWorker.MAX_MANGA_IDS) { Long.MAX_VALUE - it }

        // Data.Builder.build() throws past WorkManager's limit; this is the input startNow builds.
        val input = workDataOf(LibraryUpdateWorker.KEY_MANGA_IDS to ids)

        input.getLongArray(LibraryUpdateWorker.KEY_MANGA_IDS)?.size shouldBe LibraryUpdateWorker.MAX_MANGA_IDS
        // And the limit is real: a third more does not fit.
        shouldThrow<IllegalStateException> {
            workDataOf(LibraryUpdateWorker.KEY_MANGA_IDS to LongArray(LibraryUpdateWorker.MAX_MANGA_IDS * 4 / 3))
        }
    }

    /**
     * A manual run a start without extensions sent back waits out its backoff after the restart. A
     * new request replaces it rather than being dropped behind it while reported as started.
     */
    @Test
    fun `a manual run that is waiting is replaced by a new one, and one that is going refuses it`() = runTest {
        fun existing(runState: WorkInfo.State) {
            val info = mockk<WorkInfo> { every { state } returns runState }
            every { workManager.getWorkInfosByTag(LibraryUpdateWorker.TAG) } returns
                mockk<ListenableFuture<List<WorkInfo>>> { every { get() } returns listOf(info) }
        }

        existing(WorkInfo.State.ENQUEUED)
        scheduler.startNow(mangaIds = listOf(7L)) shouldBe true
        request.captured.workSpec.input.getLongArray(LibraryUpdateWorker.KEY_MANGA_IDS)!!.toList() shouldBe listOf(7L)

        existing(WorkInfo.State.RUNNING)
        scheduler.startNow() shouldBe false
        verify(exactly = 1) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    /** A start without extensions: the run would record every entry as a source not installed. */
    @Test
    fun `a manual run is refused, and nothing enqueued, in a start without extensions`() = runTest {
        val paused = LibraryUpdateScheduler(
            mockk(),
            LibraryPreferences(InMemoryPreferenceStore()),
            StartMode(withoutExtensions = true),
        )

        paused.isPaused shouldBe true
        paused.startNow() shouldBe false

        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `more entries than a run can name are refused before anything is enqueued`() = runTest {
        shouldThrow<IllegalArgumentException> {
            scheduler.startNow(mangaIds = (1L..LibraryUpdateWorker.MAX_MANGA_IDS + 1L).toList())
        }

        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }
}
