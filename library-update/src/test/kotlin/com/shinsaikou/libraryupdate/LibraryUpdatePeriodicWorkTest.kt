package com.shinsaikou.libraryupdate

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.TimeUnit

/**
 * The periodic run's constraints (phase 3 unit AD). "Only on Wi-Fi" is a `NetworkRequest` with the
 * Wi-Fi transport, which WorkManager applies from API 28; before this unit none was set, so from API
 * 28 the restriction constrained nothing. On the JVM the platform's builder is a stub and WorkManager
 * keeps only the network type below API 28, so the request is checked where it is built and where it
 * is handed to [Constraints.Builder]; the device check reads the job the scheduler holds.
 */
class LibraryUpdatePeriodicWorkTest {

    private val preferences = LibraryPreferences(InMemoryPreferenceStore())
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val request = slot<PeriodicWorkRequest>()
    private val built = mockk<NetworkRequest>()

    @BeforeEach
    fun setUp() {
        mockkConstructor(NetworkRequest.Builder::class, Constraints.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().build() } returns built
        every {
            workManager.enqueueUniquePeriodicWork(LibraryUpdateWorker.WORK_NAME_AUTO, ExistingPeriodicWorkPolicy.UPDATE, capture(request))
        } returns mockk()
        preferences.autoUpdateInterval.set(12)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `wifi only asks for the wifi transport over any network a VPN may carry`() {
        preferences.autoUpdateDeviceRestrictions.get() shouldBe setOf(LibraryUpdateRestrictions.DEVICE_ONLY_ON_WIFI)

        LibraryUpdateWorker.setupPeriodic(workManager, preferences)

        verify { anyConstructed<NetworkRequest.Builder>().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
        verify(exactly = 1) { anyConstructed<NetworkRequest.Builder>().addTransportType(NetworkCapabilities.TRANSPORT_WIFI) }
        verify(exactly = 0) { anyConstructed<NetworkRequest.Builder>().addCapability(any()) }
        verify { anyConstructed<Constraints.Builder>().setRequiredNetworkRequest(built, NetworkType.CONNECTED) }
        request.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        request.captured.workSpec.intervalDuration shouldBe TimeUnit.HOURS.toMillis(12)
    }

    @Test
    fun `an unmetered network and charging, without wifi, ask for not metered and no transport`() {
        preferences.autoUpdateDeviceRestrictions.set(
            setOf(LibraryUpdateRestrictions.DEVICE_NETWORK_NOT_METERED, LibraryUpdateRestrictions.DEVICE_CHARGING),
        )

        LibraryUpdateWorker.setupPeriodic(workManager, preferences)

        verify(exactly = 0) { anyConstructed<NetworkRequest.Builder>().addTransportType(any()) }
        verify(exactly = 1) {
            anyConstructed<NetworkRequest.Builder>().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        verify { anyConstructed<Constraints.Builder>().setRequiredNetworkRequest(built, NetworkType.UNMETERED) }
        request.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
        request.captured.workSpec.constraints.requiresCharging() shouldBe true
    }

    @Test
    fun `no restriction still takes any network a VPN may carry`() {
        preferences.autoUpdateDeviceRestrictions.set(emptySet())

        LibraryUpdateWorker.setupPeriodic(workManager, preferences)

        verify { anyConstructed<NetworkRequest.Builder>().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
        verify(exactly = 0) { anyConstructed<NetworkRequest.Builder>().addTransportType(any()) }
        verify(exactly = 0) { anyConstructed<NetworkRequest.Builder>().addCapability(any()) }
        request.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        request.captured.workSpec.constraints.requiresCharging() shouldBe false
    }

    @Test
    fun `an interval of off cancels the periodic run and builds no request`() {
        preferences.autoUpdateInterval.set(0)

        LibraryUpdateWorker.setupPeriodic(workManager, preferences)

        verify { workManager.cancelUniqueWork(LibraryUpdateWorker.WORK_NAME_AUTO) }
        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }
        verify(exactly = 0) { anyConstructed<NetworkRequest.Builder>().build() }
    }
}
