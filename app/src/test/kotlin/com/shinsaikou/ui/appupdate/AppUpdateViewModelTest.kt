package com.shinsaikou.ui.appupdate

import com.shinsaikou.appupdate.AppUpdate
import com.shinsaikou.appupdate.AppUpdateBuild
import com.shinsaikou.appupdate.AppUpdateChecker
import com.shinsaikou.appupdate.AppUpdateInstaller
import com.shinsaikou.appupdate.AppUpdatePreferences
import com.shinsaikou.appupdate.AppUpdateScheduler
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The prompt's answers: Update starts the download at once when the app may install apps, and goes
 * by Android's "Install unknown apps" page first when it may not; Not now and Skip this version go
 * to the checker. The checker, the scheduler and the installer are fakes.
 */
class AppUpdateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val update = AppUpdate(
        version = "0.0.2",
        notes = "Fixes.",
        assetName = "saikou-0.0.2-universal.apk",
        assetUrl = "https://github.com/example/app/releases/download/v0.0.2/saikou-0.0.2-universal.apk",
        assetDigest = null,
    )
    private val offer = MutableStateFlow<AppUpdate?>(update)
    private val checker = mockk<AppUpdateChecker>(relaxed = true) {
        every { offer } returns this@AppUpdateViewModelTest.offer
        every { checking } returns MutableStateFlow(false)
        every { dismiss() } answers { this@AppUpdateViewModelTest.offer.value = null }
    }
    private val scheduler = mockk<AppUpdateScheduler>(relaxed = true)
    private val installer = mockk<AppUpdateInstaller>()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun TestScope.viewModel(): AppUpdateViewModel {
        val viewModel = AppUpdateViewModel(
            checker = checker,
            scheduler = scheduler,
            installer = installer,
            preferences = AppUpdatePreferences(InMemoryPreferenceStore()),
            build = AppUpdateBuild("example/app", "0.0.1", automaticChecks = true, primaryAbi = "arm64-v8a"),
        )
        backgroundScope.launch { viewModel.state.collect() }
        return viewModel
    }

    @Test
    fun `update with installs allowed starts the download at once and closes the prompt`() = runTest(testDispatcher) {
        coEvery { installer.canRequestInstalls() } returns true
        val viewModel = viewModel()

        viewModel.update()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.start(update) }
        viewModel.state.value.offer.shouldBeNull()
        viewModel.state.value.askInstallPermission shouldBe false
        viewModel.state.value.notice shouldBe AppUpdateViewModel.Notice.Downloading("0.0.2")
    }

    @Test
    fun `update without installs allowed asks for the page first, then starts once allowed`() = runTest(testDispatcher) {
        coEvery { installer.canRequestInstalls() } returns false
        val viewModel = viewModel()

        viewModel.update()
        advanceUntilIdle()
        viewModel.state.value.askInstallPermission shouldBe true
        verify(exactly = 0) { scheduler.start(any()) }

        viewModel.installPermissionAsked()
        coEvery { installer.canRequestInstalls() } returns true
        viewModel.onInstallPermissionResult()
        advanceUntilIdle()

        viewModel.state.value.askInstallPermission shouldBe false
        verify(exactly = 1) { scheduler.start(update) }
    }

    @Test
    fun `coming back without the permission starts nothing and says why`() = runTest(testDispatcher) {
        coEvery { installer.canRequestInstalls() } returns false
        val viewModel = viewModel()

        viewModel.update()
        advanceUntilIdle()
        viewModel.installPermissionAsked()
        viewModel.onInstallPermissionResult()
        advanceUntilIdle()

        verify(exactly = 0) { scheduler.start(any()) }
        viewModel.state.value.notice shouldBe AppUpdateViewModel.Notice.InstallNotAllowed
        viewModel.noticeShown()
        advanceUntilIdle()
        viewModel.state.value.notice.shouldBeNull()
    }

    @Test
    fun `a device without the page starts the download and leaves the question to Android's prompt`() = runTest(testDispatcher) {
        coEvery { installer.canRequestInstalls() } returns false
        val viewModel = viewModel()

        viewModel.update()
        advanceUntilIdle()
        viewModel.onInstallPermissionResult(pageOpened = false)
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.start(update) }
    }

    @Test
    fun `not now and skip go to the checker`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.skip()
        verify(exactly = 1) { checker.skip(update) }

        viewModel.notNow()
        verify { checker.dismiss() }
    }
}
