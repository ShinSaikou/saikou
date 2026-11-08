package com.shinsaikou.extensionupdate

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The daily job follows the Experimental switch: armed with the unique name, KEEP and the limits the
 * switch's text promises while it is on, cancelled while it is off. The requests are read off what
 * the scheduler hands WorkManager, as in `SyncSchedulerTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionUpdateSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)

    /** The platform's preference replays its current value to a new collector; this one does as well. */
    private val switch = MutableStateFlow(false)
    private val preference = mockk<Preference<Boolean>> {
        every { changes() } returns switch as Flow<Boolean>
        every { get() } answers { switch.value }
    }
    private val preferences = mockk<ExtensionsPreferences> {
        every { autoInstallUpdates } returns preference
    }
    private val scheduler = ExtensionUpdateScheduler(lazyOf(workManager), preferences)

    private fun TestScope.follow() = scheduler.followSetting(backgroundScope)

    @Test
    fun `a switch that is on at start arms the unique periodic work with KEEP`() = runTest(UnconfinedTestDispatcher()) {
        switch.value = true
        val request = slot<PeriodicWorkRequest>()
        every {
            workManager.enqueueUniquePeriodicWork("extension-auto-update", ExistingPeriodicWorkPolicy.KEEP, capture(request))
        } returns mockk<Operation>()

        follow()

        verify(exactly = 1) { workManager.enqueueUniquePeriodicWork("extension-auto-update", ExistingPeriodicWorkPolicy.KEEP, any()) }
        request.captured.workSpec.workerClassName shouldBe ExtensionUpdateWorker::class.java.name
    }

    @Test
    fun `the request runs every 24 hours on an unmetered network with battery and storage not low`() = runTest(UnconfinedTestDispatcher()) {
        switch.value = true
        val request = slot<PeriodicWorkRequest>()
        every { workManager.enqueueUniquePeriodicWork(any(), any(), capture(request)) } returns mockk<Operation>()

        follow()

        val spec = request.captured.workSpec
        spec.intervalDuration shouldBe TimeUnit.HOURS.toMillis(24)
        spec.constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
        spec.constraints.requiresBatteryNotLow() shouldBe true
        spec.constraints.requiresStorageNotLow() shouldBe true
        spec.backoffPolicy shouldBe BackoffPolicy.EXPONENTIAL
        spec.backoffDelayDuration shouldBe TimeUnit.MINUTES.toMillis(15)
    }

    @Test
    fun `a switch that is off at start cancels the work and arms nothing`() = runTest(UnconfinedTestDispatcher()) {
        follow()

        verify(exactly = 1) { workManager.cancelUniqueWork("extension-auto-update") }
        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }
    }

    @Test
    fun `turning the switch on arms the work and turning it off cancels it`() = runTest(UnconfinedTestDispatcher()) {
        follow()
        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }

        switch.value = true
        verify(exactly = 1) { workManager.enqueueUniquePeriodicWork("extension-auto-update", ExistingPeriodicWorkPolicy.KEEP, any()) }

        switch.value = false
        verify(exactly = 2) { workManager.cancelUniqueWork("extension-auto-update") }
        verify(exactly = 1) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }
    }

    @Test
    fun `a WorkManager that throws does not stop the scheduler following the switch`() = runTest(UnconfinedTestDispatcher()) {
        every { workManager.enqueueUniquePeriodicWork(any(), any(), any()) } throws IOException("fixture: no database") andThen mockk<Operation>()
        follow()

        switch.value = true
        switch.value = false
        switch.value = true

        verify(exactly = 2) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }
    }

    @Test
    fun `WorkManager is not touched until the switch has a value to apply`() = runTest(UnconfinedTestDispatcher()) {
        val lazyWorkManager = mockk<Lazy<WorkManager>>()
        every { lazyWorkManager.value } returns workManager

        ExtensionUpdateScheduler(lazyWorkManager, preferences)

        verify(exactly = 0) { lazyWorkManager.value }
    }
}
