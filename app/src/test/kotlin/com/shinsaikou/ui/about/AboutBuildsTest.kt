package com.shinsaikou.ui.about

import com.shinsaikou.appupdate.ApkKind
import com.shinsaikou.appupdate.AppUpdateBuild
import com.shinsaikou.appupdate.GitHubAsset
import com.shinsaikou.appupdate.GitHubRelease
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Builds list: the release's APKs only, the release build first, and what a tap does on each
 * build. Only this build's own APK is ever installed in place, and never at the version installed.
 */
class AboutBuildsTest {

    private fun asset(name: String, size: Long = 1_000L) =
        GitHubAsset(name = name, downloadUrl = "https://github.com/example/app/releases/download/v0.1.2/$name", size = size)

    private val releaseApk = asset("app-release.apk", size = 24_000_000L)
    private val debugApk = asset("app-debug.apk", size = 60_000_000L)
    private val release = GitHubRelease(
        tagName = "v0.1.2",
        assets = listOf(debugApk, asset("notes.txt"), releaseApk, asset("mapping.zip")),
    )

    private fun build(versionName: String, kind: ApkKind) =
        AppUpdateBuild("example/app", versionName, automaticChecks = false, primaryAbi = "arm64-v8a", apkKind = kind)

    private fun actions(versionName: String, kind: ApkKind) =
        AboutBuilds.of(release, build(versionName, kind)).map { it.name to it.action }

    @Test
    fun `the APKs alone are listed, the release build first, with their size and the release's version`() {
        val rows = AboutBuilds.of(release, build("0.1.1", ApkKind.RELEASE))

        rows.map { it.name } shouldBe listOf("app-release.apk", "app-debug.apk")
        rows.map { it.kind } shouldBe listOf(ApkKind.RELEASE, ApkKind.DEBUG)
        rows.map { it.sizeBytes } shouldBe listOf(24_000_000L, 60_000_000L)
        rows.map { it.version }.distinct() shouldBe listOf("0.1.2")
        rows.map { it.url } shouldBe listOf(releaseApk.downloadUrl, debugApk.downloadUrl)
    }

    @Test
    fun `an older release app updates from the release APK and downloads the debug one`() {
        actions("0.1.1", ApkKind.RELEASE) shouldBe listOf(
            "app-release.apk" to BuildAsset.Action.UPDATE,
            "app-debug.apk" to BuildAsset.Action.DOWNLOAD,
        )
    }

    @Test
    fun `the release APK of the version installed is not offered to install`() {
        actions("0.1.2", ApkKind.RELEASE) shouldBe listOf(
            "app-release.apk" to BuildAsset.Action.INSTALLED,
            "app-debug.apk" to BuildAsset.Action.DOWNLOAD,
        )
    }

    @Test
    fun `the debug build updates from the debug APK and downloads the release one`() {
        actions("0.1.1-debug", ApkKind.DEBUG) shouldBe listOf(
            "app-release.apk" to BuildAsset.Action.DOWNLOAD,
            "app-debug.apk" to BuildAsset.Action.UPDATE,
        )
        actions("0.1.2-debug", ApkKind.DEBUG) shouldBe listOf(
            "app-release.apk" to BuildAsset.Action.DOWNLOAD,
            "app-debug.apk" to BuildAsset.Action.INSTALLED,
        )
    }

    @Test
    fun `a build newer than the release, or a tag that is not a version, only downloads`() {
        actions("0.2.0", ApkKind.RELEASE).map { it.second }.distinct() shouldBe listOf(BuildAsset.Action.DOWNLOAD)

        val nightly = AboutBuilds.of(release.copy(tagName = "nightly"), build("0.1.1", ApkKind.RELEASE))
        nightly.map { it.action }.distinct() shouldBe listOf(BuildAsset.Action.DOWNLOAD)
        nightly.map { it.version }.distinct() shouldBe listOf("nightly")
    }

    @Test
    fun `a release without APKs lists nothing`() {
        AboutBuilds.of(release.copy(assets = listOf(asset("notes.txt"))), build("0.1.1", ApkKind.RELEASE)).shouldBeEmpty()
    }
}
