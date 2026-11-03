package com.shinsaikou.appupdate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The checker over a fake GitHub: when the foreground arrival asks, what it records, what it offers,
 * and how a check the owner asked for answers. The clock is fixed, and nothing here reaches GitHub.
 */
class AppUpdateCheckerTest {

    private var now: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000L)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val preferences = AppUpdatePreferences(InMemoryPreferenceStore())
    private val releases = mockk<GitHubReleases>()

    private val apk = GitHubAsset(
        name = "saikou-0.0.2-arm64-v8a.apk",
        downloadUrl = "https://github.com/example/app/releases/download/v0.0.2/saikou-0.0.2-arm64-v8a.apk",
        digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    )
    private val newer = GitHubRelease(tagName = "v0.0.2", body = "Fixes.", assets = listOf(apk))

    private fun checker(
        automaticChecks: Boolean = true,
        versionName: String = "0.0.1",
        apkKind: ApkKind = ApkKind.RELEASE,
    ) = AppUpdateChecker(
        releases = releases,
        preferences = preferences,
        build = AppUpdateBuild(
            repository = "example/app",
            versionName = versionName,
            automaticChecks = automaticChecks,
            primaryAbi = "arm64-v8a",
            apkKind = apkKind,
        ),
        clock = clock,
    )

    @Test
    fun `the first arrival asks GitHub, records the time, and offers a newer release`() = runTest {
        coEvery { releases.latest() } returns newer
        val checker = checker()

        checker.checkAutomatically()

        val offer = checker.offer.value.shouldNotBeNull()
        offer.version shouldBe "0.0.2"
        offer.notes shouldBe "Fixes."
        offer.assetUrl shouldBe apk.downloadUrl
        preferences.lastCheckAt.get() shouldBe now.toEpochMilliseconds()
    }

    @Test
    fun `a second arrival within a day does not ask again, one a day later does`() = runTest {
        coEvery { releases.latest() } returns newer
        val checker = checker()

        checker.checkAutomatically()
        now += 23.hours
        checker.checkAutomatically()
        coVerify(exactly = 1) { releases.latest() }

        now += 1.hours
        checker.checkAutomatically()
        coVerify(exactly = 2) { releases.latest() }
    }

    @Test
    fun `the setting off or a debug build never asks by itself`() = runTest {
        coEvery { releases.latest() } returns newer

        preferences.autoCheck.set(false)
        checker().checkAutomatically()
        preferences.autoCheck.set(true)
        checker(automaticChecks = false).checkAutomatically()

        coVerify(exactly = 0) { releases.latest() }
        preferences.lastCheckAt.get() shouldBe 0L
    }

    @Test
    fun `an unreachable GitHub is silent on arrival and still counts as the day's check`() = runTest {
        coEvery { releases.latest() } throws IOException("offline")
        val checker = checker()

        checker.checkAutomatically()

        checker.offer.value.shouldBeNull()
        preferences.lastCheckAt.get() shouldBe now.toEpochMilliseconds()
    }

    @Test
    fun `a skipped version is not offered on arrival, and a newer one is`() = runTest {
        coEvery { releases.latest() } returns newer
        val checker = checker()

        checker.checkAutomatically()
        checker.skip(checker.offer.value.shouldNotBeNull())
        checker.offer.value.shouldBeNull()
        preferences.skippedVersion.get() shouldBe "0.0.2"

        now += 24.hours
        checker.checkAutomatically()
        checker.offer.value.shouldBeNull()

        coEvery { releases.latest() } returns newer.copy(tagName = "v0.0.3")
        now += 24.hours
        checker.checkAutomatically()
        checker.offer.value.shouldNotBeNull().version shouldBe "0.0.3"
    }

    @Test
    fun `not now clears the offer until the next check`() = runTest {
        coEvery { releases.latest() } returns newer
        val checker = checker()

        checker.checkAutomatically()
        checker.dismiss()

        checker.offer.value.shouldBeNull()
        preferences.skippedVersion.get() shouldBe ""
    }

    @Test
    fun `a check the owner asks for ignores the day, the setting and the build, and offers a skipped version`() = runTest {
        coEvery { releases.latest() } returns newer
        preferences.autoCheck.set(false)
        preferences.skippedVersion.set("0.0.2")
        preferences.lastCheckAt.set(now.toEpochMilliseconds())
        val checker = checker(automaticChecks = false, versionName = "0.0.1-debug")

        val result = checker.checkNow()

        result shouldBe AppUpdateChecker.Result.Available(checker.offer.value.shouldNotBeNull())
        checker.checking.value shouldBe false
    }

    @Test
    fun `a check the owner asks for says when GitHub could not be reached`() = runTest {
        coEvery { releases.latest() } throws IOException("offline")
        val checker = checker()

        checker.checkNow() shouldBe AppUpdateChecker.Result.Failed
        checker.offer.value.shouldBeNull()
        checker.checking.value shouldBe false
    }

    @Test
    fun `a check the owner asks for says up to date for the installed version or no release at all`() = runTest {
        coEvery { releases.latest() } returns newer
        checker(versionName = "0.0.2").checkNow() shouldBe AppUpdateChecker.Result.UpToDate

        coEvery { releases.latest() } returns null
        checker().checkNow() shouldBe AppUpdateChecker.Result.UpToDate
    }

    @Test
    fun `a check the owner asks for says when the release has no file for this device`() = runTest {
        coEvery { releases.latest() } returns newer.copy(assets = listOf(apk.copy(name = "notes.txt")))
        val checker = checker()

        checker.checkNow() shouldBe AppUpdateChecker.Result.NoApk("0.0.2")
        checker.offer.value.shouldBeNull()
    }

    @Test
    fun `a check the owner asks for holds back the next automatic one for a day`() = runTest {
        coEvery { releases.latest() } returns null
        val checker = checker()

        checker.checkNow()
        now += 1.hours
        checker.checkAutomatically()

        coVerify(exactly = 1) { releases.latest() }
    }

    @Test
    fun `a release with both APKs offers each build its own`() = runTest {
        val base = "https://github.com/example/app/releases/download/v0.0.2/"
        val releaseApk = GitHubAsset(name = "app-release.apk", downloadUrl = base + "app-release.apk")
        val debugApk = GitHubAsset(name = "app-debug.apk", downloadUrl = base + "app-debug.apk")
        coEvery { releases.latest() } returns newer.copy(assets = listOf(releaseApk, debugApk))

        val release = checker().checkNow()
        val debug = checker(versionName = "0.0.1-debug", apkKind = ApkKind.DEBUG).checkNow()

        (release as AppUpdateChecker.Result.Available).update.assetName shouldBe "app-release.apk"
        (debug as AppUpdateChecker.Result.Available).update.assetName shouldBe "app-debug.apk"
    }
}
