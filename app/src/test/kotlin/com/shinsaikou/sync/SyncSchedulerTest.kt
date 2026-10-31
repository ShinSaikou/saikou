package com.shinsaikou.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Q10's two unique names, their policies, the network constraint on every request and the interval,
 * read off the requests the scheduler hands WorkManager.
 */
class SyncSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val values = mutableMapOf<String, Any>()
    private val syncPreferences = SyncPreferences(MapPreferenceStore(values))
    private val workPreferences = SyncWorkPreferences(MapPreferenceStore(values))
    private val scheduler = SyncScheduler(lazyOf(workManager), syncPreferences, workPreferences)

    private fun signIn() = syncPreferences.backendId.set("webdav-0123456789abcdef")

    private fun WorkRequest.assertSyncRequest() {
        workSpec.workerClassName shouldBe SyncWorker::class.java.name
        workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        workSpec.backoffPolicy shouldBe BackoffPolicy.EXPONENTIAL
        workSpec.backoffDelayDuration shouldBe TimeUnit.MINUTES.toMillis(1)
        (SyncScheduler.TAG in tags) shouldBe true
    }

    @Test
    fun `the periodic run is sync-periodic at the chosen interval, six hours by default, updated in place`() {
        signIn()
        val request = slot<PeriodicWorkRequest>()
        every {
            workManager.enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, capture(request))
        } returns mockk()

        scheduler.setup()
        request.captured.assertSyncRequest()
        request.captured.workSpec.intervalDuration shouldBe TimeUnit.HOURS.toMillis(6)

        for (hours in listOf(1, 12, 24)) {
            workPreferences.intervalHours.set(hours)
            scheduler.setup()
            request.captured.workSpec.intervalDuration shouldBe TimeUnit.HOURS.toMillis(hours.toLong())
        }
        verify(exactly = 4) { workManager.enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, any()) }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `the interval's choices are off, 1, 6, 12 and 24 hours`() {
        SyncWorkPreferences.INTERVALS shouldBe listOf(0, 1, 6, 12, 24)
        workPreferences.intervalHours.get() shouldBe 6
    }

    @Test
    fun `an interval of off, or no backend, cancels the periodic run and arms nothing`() {
        scheduler.setup()
        signIn()
        workPreferences.intervalHours.set(0)
        scheduler.setup()

        verify(exactly = 2) { workManager.cancelUniqueWork("sync-periodic") }
        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }
    }

    @Test
    fun `an automatic trigger is sync-once with KEEP, delayed when asked, and nothing without a backend`() {
        scheduler.syncSoon()
        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }

        signIn()
        val requests = mutableListOf<OneTimeWorkRequest>()
        every { workManager.enqueueUniqueWork("sync-once", ExistingWorkPolicy.KEEP, capture(requests)) } returns mockk()

        scheduler.syncSoon()
        scheduler.syncSoon(SyncScheduler.READER_CLOSED_DELAY)

        requests.forEach { it.assertSyncRequest() }
        requests.map { it.workSpec.initialDelay } shouldBe listOf(0L, 30_000L)
    }

    @Test
    fun `sync now replaces a waiting sync-once and keeps a running one`() = runTest {
        scheduler.syncNow() shouldBe false
        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }

        signIn()
        val request = slot<OneTimeWorkRequest>()
        every { workManager.enqueueUniqueWork("sync-once", any(), capture(request)) } returns mockk()

        every { workManager.getWorkInfosForUniqueWorkFlow("sync-once") } returns flowOf(listOf(info(WorkInfo.State.ENQUEUED)))
        scheduler.syncNow() shouldBe true
        verify(exactly = 1) { workManager.enqueueUniqueWork("sync-once", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
        request.captured.assertSyncRequest()
        request.captured.workSpec.initialDelay shouldBe 0L

        every { workManager.getWorkInfosForUniqueWorkFlow("sync-once") } returns flowOf(listOf(info(WorkInfo.State.RUNNING)))
        scheduler.syncNow() shouldBe true
        verify(exactly = 1) { workManager.enqueueUniqueWork("sync-once", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }

        every { workManager.getWorkInfosForUniqueWorkFlow("sync-once") } returns flowOf(emptyList())
        scheduler.syncNow() shouldBe true
        verify(exactly = 2) { workManager.enqueueUniqueWork("sync-once", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `with Wi-Fi only on, the automatic runs wait for an unmetered network and sync now takes any`() = runTest {
        signIn()
        workPreferences.wifiOnly.get() shouldBe false
        workPreferences.wifiOnly.set(true)
        val requests = mutableListOf<OneTimeWorkRequest>()
        every { workManager.enqueueUniqueWork("sync-once", any(), capture(requests)) } returns mockk()
        every { workManager.getWorkInfosForUniqueWorkFlow("sync-once") } returns flowOf(emptyList())
        val periodic = slot<PeriodicWorkRequest>()
        every { workManager.enqueueUniquePeriodicWork("sync-periodic", any(), capture(periodic)) } returns mockk()

        scheduler.setup()
        scheduler.syncSoon()
        scheduler.syncNow()

        periodic.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
        requests.map { it.workSpec.constraints.requiredNetworkType } shouldBe
            listOf(NetworkType.UNMETERED, NetworkType.CONNECTED)
    }

    /**
     * An automatic run waiting under the network Wi-Fi only no longer asks for is replaced, in both
     * directions; one already under the right network, Sync now's and a running one are left alone.
     */
    @Test
    fun `a change of Wi-Fi only replaces a waiting automatic run that waits for the other network`() = runTest {
        signIn()
        val requests = mutableListOf<OneTimeWorkRequest>()
        every { workManager.enqueueUniqueWork("sync-once", ExistingWorkPolicy.REPLACE, capture(requests)) } returns
            mockk()
        fun waiting(vararg infos: WorkInfo) {
            every { workManager.getWorkInfosForUniqueWorkFlow("sync-once") } returns flowOf(infos.toList())
        }

        workPreferences.wifiOnly.set(true)
        for (left in listOf(
            info(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED),
            info(WorkInfo.State.ENQUEUED, tags = setOf(SyncScheduler.TAG, SyncScheduler.TAG_USER_STARTED)),
            info(WorkInfo.State.RUNNING, NetworkType.CONNECTED),
        )) {
            waiting(left)
            scheduler.rearm()
        }
        waiting()
        scheduler.rearm()
        requests.shouldBeEmpty()

        waiting(info(WorkInfo.State.ENQUEUED, NetworkType.CONNECTED))
        scheduler.rearm()
        workPreferences.wifiOnly.set(false)
        waiting(info(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED))
        scheduler.rearm()

        requests.map { it.workSpec.constraints.requiredNetworkType } shouldBe
            listOf(NetworkType.UNMETERED, NetworkType.CONNECTED)
        requests.forEach { it.workSpec.input.getBoolean(SyncScheduler.KEY_USER_STARTED, false) shouldBe false }
    }

    /** Fixes unit sync-notices: only Sync now's run says the owner started it, so only its failure posts no notice. */
    @Test
    fun `only sync now's request is marked as started by the owner`() = runTest {
        signIn()
        val requests = mutableListOf<OneTimeWorkRequest>()
        every { workManager.enqueueUniqueWork("sync-once", any(), capture(requests)) } returns mockk()
        every { workManager.getWorkInfosForUniqueWorkFlow("sync-once") } returns flowOf(emptyList())
        val periodic = slot<PeriodicWorkRequest>()
        every { workManager.enqueueUniquePeriodicWork("sync-periodic", any(), capture(periodic)) } returns mockk()

        scheduler.syncSoon()
        scheduler.syncNow()
        scheduler.setup()

        requests.map { it.workSpec.input.getBoolean(SyncScheduler.KEY_USER_STARTED, false) } shouldBe listOf(false, true)
        requests.map { SyncScheduler.TAG_USER_STARTED in it.tags } shouldBe listOf(false, true)
        periodic.captured.workSpec.input.getBoolean(SyncScheduler.KEY_USER_STARTED, false) shouldBe false
    }

    @Test
    fun `sign-out cancels both names`() {
        scheduler.cancel()

        verify(exactly = 1) { workManager.cancelUniqueWork("sync-periodic") }
        verify(exactly = 1) { workManager.cancelUniqueWork("sync-once") }
    }

    private fun info(
        state: WorkInfo.State,
        network: NetworkType = NetworkType.CONNECTED,
        tags: Set<String> = setOf(SyncScheduler.TAG),
    ) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = tags,
        constraints = Constraints.Builder().setRequiredNetworkType(network).build(),
    )
}
