package com.shinsaikou.downloads

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.domain.preferences.DownloadPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.UUID

/**
 * The request [DownloadScheduler] hands WorkManager, read off the mock, and which request it leaves
 * in place. "Only download over Wi-Fi" is on by default, so a queue started on mobile data waits
 * for Wi-Fi; turning the setting off has to release it.
 */
class DownloadSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val preferences = DownloadPreferences(InMemoryPreferenceStore())

    /** WorkManager's future is read on the IO dispatcher, which is the test's here. */
    private val TestScope.scheduler: DownloadScheduler
        get() {
            val dispatcher = StandardTestDispatcher(testScheduler)
            return DownloadScheduler(
                lazyOf(workManager),
                preferences,
                Dispatchers(dispatcher, dispatcher, dispatcher),
                StartMode.NORMAL,
            )
        }

    private var existing = emptyList<WorkInfo>()
    private val request = slot<OneTimeWorkRequest>()

    @BeforeEach
    fun setUp() {
        every { workManager.getWorkInfosForUniqueWork(DownloadWorker.WORK_NAME) } answers {
            mockk<ListenableFuture<List<WorkInfo>>> { every { get() } returns existing }
        }
        every { workManager.enqueueUniqueWork(DownloadWorker.WORK_NAME, any(), capture(request)) } returns mockk()
    }

    @Test
    fun `with nothing enqueued, a start asks for Wi-Fi by default`() = runTest {
        preferences.downloadOnlyOverWifi.get() shouldBe true

        scheduler.start()

        verifyEnqueued(ExistingWorkPolicy.APPEND_OR_REPLACE)
        request.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
    }

    @Test
    fun `a request waiting for Wi-Fi is replaced by one on any network once the setting is off`() = runTest {
        existing = listOf(work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED))
        preferences.downloadOnlyOverWifi.set(false)

        scheduler.start()

        verifyEnqueued(ExistingWorkPolicy.REPLACE)
        request.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
    }

    @Test
    fun `a request waiting under the current setting is left as it is`() = runTest {
        existing = listOf(work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED))

        scheduler.start()

        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `a running worker under the current setting is followed, not replaced`() = runTest {
        existing = listOf(work(WorkInfo.State.RUNNING, NetworkType.UNMETERED))

        scheduler.start()

        verifyEnqueued(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    @Test
    fun `changing the setting replaces a waiting request, with no start`() = runTest {
        existing = listOf(work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED))
        scheduler.followNetworkSetting(backgroundScope)
        runCurrent()

        preferences.downloadOnlyOverWifi.set(false)
        runCurrent()

        verifyEnqueued(ExistingWorkPolicy.REPLACE)
        request.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
    }

    @Test
    fun `changing the setting with no request makes none`() = runTest {
        existing = listOf(work(WorkInfo.State.SUCCEEDED, NetworkType.UNMETERED))
        scheduler.followNetworkSetting(backgroundScope)
        runCurrent()

        preferences.downloadOnlyOverWifi.set(false)
        runCurrent()

        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `the status names what an enqueued request waits for`() {
        DownloadScheduler.statusOf(emptyList(), NOW) shouldBe DownloadStatus.IDLE
        DownloadScheduler.statusOf(listOf(work(WorkInfo.State.SUCCEEDED, NetworkType.UNMETERED)), NOW) shouldBe
            DownloadStatus.IDLE
        DownloadScheduler.statusOf(listOf(work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED)), NOW) shouldBe
            DownloadStatus.WAITING_FOR_WIFI
        DownloadScheduler.statusOf(listOf(work(WorkInfo.State.ENQUEUED, NetworkType.CONNECTED)), NOW) shouldBe
            DownloadStatus.WAITING_FOR_NETWORK
        val runningThenBlocked = listOf(
            work(WorkInfo.State.RUNNING, NetworkType.UNMETERED),
            work(WorkInfo.State.BLOCKED, NetworkType.UNMETERED),
        )
        DownloadScheduler.statusOf(runningThenBlocked, NOW) shouldBe DownloadStatus.RUNNING
        // A run that ended on a network failure, waiting out its backoff.
        val backingOff = work(WorkInfo.State.ENQUEUED, NetworkType.CONNECTED, nextRun = NOW + 30_000)
        DownloadScheduler.statusOf(listOf(backingOff), NOW) shouldBe DownloadStatus.RETRYING
        // A run the platform stopped when Wi-Fi went waits for Wi-Fi, whatever its next run time.
        val stopped = work(
            WorkInfo.State.ENQUEUED,
            NetworkType.UNMETERED,
            nextRun = NOW + 30_000,
            stopReason = WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
        )
        DownloadScheduler.statusOf(listOf(stopped), NOW) shouldBe DownloadStatus.WAITING_FOR_WIFI
    }

    /**
     * A start without extensions: the worker hands every run back, so a request waiting for its
     * network or its backoff, or the moment it runs, says what it really waits for.
     */
    @Test
    fun `without extensions any unfinished request waits for them`() {
        val unfinished = listOf(
            work(WorkInfo.State.RUNNING, NetworkType.CONNECTED),
            work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED),
            work(WorkInfo.State.ENQUEUED, NetworkType.CONNECTED, nextRun = NOW + 30_000),
            work(WorkInfo.State.BLOCKED, NetworkType.CONNECTED),
        )
        for (info in unfinished) {
            DownloadScheduler.statusOf(listOf(info), NOW, withoutExtensions = true) shouldBe
                DownloadStatus.WAITING_FOR_EXTENSIONS
        }
        val finished = work(WorkInfo.State.SUCCEEDED, NetworkType.CONNECTED)
        DownloadScheduler.statusOf(emptyList(), NOW, withoutExtensions = true) shouldBe DownloadStatus.IDLE
        DownloadScheduler.statusOf(listOf(finished), NOW, withoutExtensions = true) shouldBe DownloadStatus.IDLE
    }

    /**
     * The start after one without extensions: the request waiting out the backoff its handed-back
     * runs built up goes again at once, and nothing else is touched.
     */
    @Test
    fun `after a start without extensions a waiting request is replaced and the rest left alone`() = runTest {
        existing = listOf(work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED, nextRun = NOW + 300_000))
        scheduler.resumeAfterWithoutExtensions()
        runCurrent()
        verifyEnqueued(ExistingWorkPolicy.REPLACE)

        existing = listOf(work(WorkInfo.State.RUNNING, NetworkType.UNMETERED))
        scheduler.resumeAfterWithoutExtensions()
        existing = emptyList()
        scheduler.resumeAfterWithoutExtensions()
        runCurrent()
        verify(exactly = 1) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `a run that ends on a network failure is retried linearly, 30 seconds a step`() = runTest {
        scheduler.start()

        request.captured.workSpec.backoffPolicy shouldBe BackoffPolicy.LINEAR
        request.captured.workSpec.backoffDelayDuration shouldBe 30_000L
    }

    private fun verifyEnqueued(policy: ExistingWorkPolicy) = verify(exactly = 1) {
        workManager.enqueueUniqueWork(DownloadWorker.WORK_NAME, policy, any<OneTimeWorkRequest>())
    }

    private fun work(
        state: WorkInfo.State,
        networkType: NetworkType,
        nextRun: Long = NOW,
        stopReason: Int = WorkInfo.STOP_REASON_NOT_STOPPED,
    ) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = setOf(DownloadWorker.TAG),
        constraints = Constraints(requiredNetworkType = networkType),
        nextScheduleTimeMillis = nextRun,
        stopReason = stopReason,
    )

    private companion object {
        const val NOW = 1_000_000L
    }
}
