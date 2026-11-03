package com.shinsaikou.ui.about

import app.cash.turbine.test
import com.shinsaikou.appupdate.ApkKind
import com.shinsaikou.appupdate.AppUpdateBuild
import com.shinsaikou.appupdate.GitHubAsset
import com.shinsaikou.appupdate.GitHubRelease
import com.shinsaikou.appupdate.GitHubReleases
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * About's two jobs of its own: the latest release's APKs, with Retry after GitHub could not be
 * reached, and the bug report handed to the screen to share. GitHub and the report's writer are fakes.
 */
class AboutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val releases = mockk<GitHubReleases>()
    private val reports = mockk<BugReportWriter>()
    private val build = AppUpdateBuild("example/app", "0.1.1", automaticChecks = false, primaryAbi = "x86_64", apkKind = ApkKind.RELEASE)
    private val release = GitHubRelease(
        tagName = "v0.1.2",
        assets = listOf(
            GitHubAsset("app-release.apk", "https://github.com/example/app/releases/download/v0.1.2/app-release.apk", size = 1L),
            GitHubAsset("app-debug.apk", "https://github.com/example/app/releases/download/v0.1.2/app-debug.apk", size = 2L),
        ),
    )

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = AboutViewModel(releases = releases, build = build, reports = reports)

    @Test
    fun `opening reads the latest release's APKs`() = runTest(testDispatcher) {
        coEvery { releases.latest() } returns release
        val viewModel = viewModel()

        viewModel.state.value.builds shouldBe AboutViewModel.Builds.Loading
        advanceUntilIdle()

        val builds = viewModel.state.value.builds.shouldBeInstanceOf<AboutViewModel.Builds.Loaded>()
        builds.assets.map { it.name to it.action } shouldBe listOf(
            "app-release.apk" to BuildAsset.Action.UPDATE,
            "app-debug.apk" to BuildAsset.Action.DOWNLOAD,
        )
    }

    @Test
    fun `GitHub out of reach fails the list, and Retry reads it again`() = runTest(testDispatcher) {
        coEvery { releases.latest() } throws IOException("offline")
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.builds shouldBe AboutViewModel.Builds.Failed

        coEvery { releases.latest() } returns release
        viewModel.loadBuilds()
        viewModel.state.value.builds shouldBe AboutViewModel.Builds.Loading
        advanceUntilIdle()

        viewModel.state.value.builds.shouldBeInstanceOf<AboutViewModel.Builds.Loaded>().assets.size shouldBe 2
        coVerify(exactly = 2) { releases.latest() }
    }

    @Test
    fun `a repository with no release lists nothing`() = runTest(testDispatcher) {
        coEvery { releases.latest() } returns null
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.builds shouldBe AboutViewModel.Builds.Loaded(emptyList())
    }

    @Test
    fun `the written report goes to the screen to share, and a failed write says so`() = runTest(testDispatcher) {
        coEvery { releases.latest() } returns null
        coEvery { reports.write() } returns File("/cache/share/bug-report-1.txt") andThenThrows IOException("full")
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.shareBugReport()
            awaitItem() shouldBe AboutViewModel.Event.ShareReport("/cache/share/bug-report-1.txt")
            viewModel.state.value.writingReport shouldBe false

            viewModel.shareBugReport()
            awaitItem() shouldBe AboutViewModel.Event.ReportFailed
            viewModel.state.value.writingReport shouldBe false
        }
    }

    @Test
    fun `a second tap while the report is written writes no second one`() = runTest(testDispatcher) {
        coEvery { releases.latest() } returns null
        coEvery { reports.write() } returns File("/cache/share/bug-report-1.txt")
        val viewModel = viewModel()

        viewModel.shareBugReport()
        viewModel.state.value.writingReport shouldBe true
        viewModel.shareBugReport()
        advanceUntilIdle()

        coVerify(exactly = 1) { reports.write() }
    }
}
