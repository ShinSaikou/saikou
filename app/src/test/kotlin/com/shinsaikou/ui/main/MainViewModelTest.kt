package com.shinsaikou.ui.main

import android.app.Activity
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator.InFlightSolve
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The two first-launch answers the main activity hosts: the battery exemption prompt and the crash
 * banner. Both come from one disk read in `init`, so every test here subscribes to `state` before
 * asserting on it: the flow is `WhileSubscribed`, and an unsubscribed read only ever sees defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val store = InMemoryPreferenceStore()

    private val basePreferences = BasePreferences(store)
    private val libraryPreferences = LibraryPreferences(store)
    private val extensionsPreferences = ExtensionsPreferences(store)
    private val uiPreferences = UiPreferences(store)

    private val crashLog = mockk<CrashLog>(relaxed = true)
    private val batteryExemption = FakeBatteryExemption()

    /**
     * The coordinator's own `coalesce` stamps `SystemClock`, which this module's JVM tests cannot
     * call, so its published map is stood in for directly.
     */
    private val inFlight = MutableStateFlow<Map<String, InFlightSolve>>(emptyMap())
    private val solveCoordinator = mockk<CloudflareSolveCoordinator> {
        every { inFlight } returns this@MainViewModelTest.inFlight
    }

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        every { crashLog.hasUnexportedCrash() } returns false
        every { crashLog.latest() } returns null
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel(startMode: StartMode = StartMode.NORMAL) = MainViewModel(
        basePreferences = basePreferences,
        libraryPreferences = libraryPreferences,
        extensionsPreferences = extensionsPreferences,
        crashLog = crashLog,
        batteryExemption = batteryExemption,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        uiPreferences = uiPreferences,
        solveCoordinator = solveCoordinator,
        startMode = startMode,
    )

    /** `state` only runs its upstream while something collects it, so every test needs this. */
    private fun TestScope.subscribed(viewModel: MainViewModel): MainViewModel {
        backgroundScope.launch { viewModel.state.collect() }
        return viewModel
    }

    private fun crashFile(name: String): File = File(tempDir, name).apply { writeText("boom") }

    @Test
    fun `the crash banner shows with the newest log when one is unexported`() = runTest(testDispatcher) {
        val newest = crashFile("crash-2.txt")
        every { crashLog.hasUnexportedCrash() } returns true
        every { crashLog.latest() } returns newest

        val viewModel = subscribed(viewModel())
        advanceUntilIdle()

        viewModel.state.value.showCrashBanner shouldBe true
        viewModel.state.value.crashLogPath shouldBe newest.absolutePath
    }

    @Test
    fun `no banner shows when the last crash was already exported`() = runTest(testDispatcher) {
        every { crashLog.hasUnexportedCrash() } returns false

        val viewModel = subscribed(viewModel())
        advanceUntilIdle()

        viewModel.state.value.showCrashBanner shouldBe false
        viewModel.state.value.crashLogPath shouldBe null
    }

    @Test
    fun `dismissing the crash banner hides it and marks the log exported`() = runTest(testDispatcher) {
        every { crashLog.hasUnexportedCrash() } returns true
        every { crashLog.latest() } returns crashFile("crash-1.txt")

        val viewModel = subscribed(viewModel())
        advanceUntilIdle()
        viewModel.state.value.showCrashBanner shouldBe true

        viewModel.dismissCrashBanner()
        advanceUntilIdle()

        viewModel.state.value.showCrashBanner shouldBe false
        viewModel.state.value.crashLogPath shouldBe null
        verify(exactly = 1) { crashLog.markExported() }
    }

    /** The banner is the only word on screen that extensions are off and updates and downloads wait. */
    @Test
    fun `a start without extensions shows its banner from the first state, and a normal start does not`() =
        runTest(testDispatcher) {
            val off = viewModel(StartMode(withoutExtensions = true))
            off.state.value.extensionsOff shouldBe true
            subscribed(off)
            advanceUntilIdle()
            off.state.value.extensionsOff shouldBe true

            val normal = subscribed(viewModel())
            advanceUntilIdle()
            normal.state.value.extensionsOff shouldBe false
        }

    @Test
    fun `a solve in flight reaches the state while it runs and leaves with it`() = runTest(testDispatcher) {
        val viewModel = subscribed(viewModel())
        advanceUntilIdle()
        viewModel.state.value.solves shouldBe emptyMap()

        val solve = InFlightSolve(host = "one.invalid", startedAtElapsedMs = 1_000L, waiters = 2, budgetMs = 30_000L)
        inFlight.value = mapOf(solve.host to solve)
        advanceUntilIdle()
        viewModel.state.value.solves shouldBe mapOf("one.invalid" to solve)

        inFlight.value = emptyMap()
        advanceUntilIdle()
        viewModel.state.value.solves shouldBe emptyMap()
    }

    @Test
    fun `the battery dialog shows when the exemption has never been requested`() = runTest(testDispatcher) {
        batteryExemption.exempt = false

        val viewModel = subscribed(viewModel())
        advanceUntilIdle()

        viewModel.state.value.showBatteryDialog shouldBe true
        basePreferences.batteryExemptionRequested.get() shouldBe false
    }

    @Test
    fun `an already exempt app never shows the battery dialog and records the request`() = runTest(testDispatcher) {
        batteryExemption.exempt = true

        val viewModel = subscribed(viewModel())
        advanceUntilIdle()

        viewModel.state.value.showBatteryDialog shouldBe false
        basePreferences.batteryExemptionRequested.get() shouldBe true
    }

    @Test
    fun `skipping the battery dialog sets the preference and hides it`() = runTest(testDispatcher) {
        val viewModel = subscribed(viewModel())
        advanceUntilIdle()
        viewModel.state.value.showBatteryDialog shouldBe true

        viewModel.skipBatteryExemption()
        advanceUntilIdle()

        viewModel.state.value.showBatteryDialog shouldBe false
        basePreferences.batteryExemptionRequested.get() shouldBe true
        batteryExemption.requests shouldBe emptyList()
    }

    @Test
    fun `allowing the battery exemption asks the platform and sets the preference`() = runTest(testDispatcher) {
        val activity = mockk<Activity>()
        val viewModel = subscribed(viewModel())
        advanceUntilIdle()

        viewModel.requestBatteryExemption(activity)
        advanceUntilIdle()

        batteryExemption.requests shouldContainExactly listOf(activity)
        viewModel.state.value.showBatteryDialog shouldBe false
        basePreferences.batteryExemptionRequested.get() shouldBe true
    }
}

/** Records what the view model asked the platform for, since the platform answers nothing back. */
private class FakeBatteryExemption(var exempt: Boolean = false) : BatteryExemption {

    val requests = mutableListOf<Activity>()

    override fun isExempt(): Boolean = exempt

    override fun request(activity: Activity) {
        requests += activity
    }
}
