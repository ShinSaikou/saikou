package com.shinsaikou.appupdate

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** What a latest release means for the installed build, and the release text the prompt shows. */
class ReleaseRulesTest {

    private val apk = GitHubAsset(
        name = "saikou-0.0.2-universal.apk",
        downloadUrl = "https://github.com/example/app/releases/download/v0.0.2/saikou-0.0.2-universal.apk",
        digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    )

    private fun release(
        tag: String = "v0.0.2",
        body: String? = "Fixes.",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GitHubAsset> = listOf(apk),
    ) = GitHubRelease(tagName = tag, body = body, draft = draft, prerelease = prerelease, assets = assets)

    private fun judge(
        release: GitHubRelease = release(),
        versionName: String = "0.0.1",
        skipped: String = "",
        honourSkip: Boolean = true,
    ) = ReleaseRules.judge(release, versionName, skipped, primaryAbi = "arm64-v8a", honourSkip = honourSkip)

    @Test
    fun `a newer release is offered with its file, digest and notes`() {
        judge() shouldBe ReleaseVerdict.Offer(
            AppUpdate(
                version = "0.0.2",
                notes = "Fixes.",
                assetName = apk.name,
                assetUrl = apk.downloadUrl,
                assetDigest = apk.digest,
            ),
        )
    }

    @Test
    fun `the installed debug build compares without its suffix`() {
        judge(versionName = "0.0.1-debug").shouldBeInstanceOf<ReleaseVerdict.Offer>()
        judge(versionName = "0.0.2-debug") shouldBe ReleaseVerdict.UpToDate
    }

    @Test
    fun `the same or an older release is no update`() {
        judge(versionName = "0.0.2") shouldBe ReleaseVerdict.UpToDate
        judge(versionName = "0.0.3") shouldBe ReleaseVerdict.UpToDate
        judge(release(tag = "v0.9.0"), versionName = "0.10.0") shouldBe ReleaseVerdict.UpToDate
    }

    @Test
    fun `a newer minor counts as newer even when its text sorts first`() {
        judge(release(tag = "v0.10.0"), versionName = "0.9.0").shouldBeInstanceOf<ReleaseVerdict.Offer>()
    }

    @Test
    fun `a draft, a prerelease, or a tag that is not a version is no update`() {
        judge(release(draft = true)) shouldBe ReleaseVerdict.UpToDate
        judge(release(prerelease = true)) shouldBe ReleaseVerdict.UpToDate
        judge(release(tag = "nightly")) shouldBe ReleaseVerdict.UpToDate
        judge(release(tag = "v0.0.2-rc1")) shouldBe ReleaseVerdict.UpToDate
        judge(versionName = "dev") shouldBe ReleaseVerdict.UpToDate
    }

    @Test
    fun `a skipped version and anything older stay skipped until a newer one appears`() {
        judge(skipped = "0.0.2") shouldBe ReleaseVerdict.Skipped("0.0.2")
        judge(skipped = "0.0.3") shouldBe ReleaseVerdict.Skipped("0.0.2")
        judge(release(tag = "v0.0.3"), skipped = "0.0.2").shouldBeInstanceOf<ReleaseVerdict.Offer>()
    }

    @Test
    fun `a check the owner asked for offers a skipped version anyway`() {
        judge(skipped = "0.0.2", honourSkip = false).shouldBeInstanceOf<ReleaseVerdict.Offer>()
    }

    @Test
    fun `a newer release with no file for this device is no update, and says so`() {
        val other = apk.copy(name = "saikou-0.0.2-x86_64.apk")
        val notes = apk.copy(name = "notes.txt")
        judge(release(assets = listOf(other, other.copy(name = "saikou-0.0.2-armeabi-v7a.apk")))) shouldBe
            ReleaseVerdict.NoApk("0.0.2")
        judge(release(assets = listOf(notes))) shouldBe ReleaseVerdict.NoApk("0.0.2")
        judge(release(assets = emptyList())) shouldBe ReleaseVerdict.NoApk("0.0.2")
    }

    @Test
    fun `the notes lose their Windows line ends and outer blank lines`() {
        ReleaseRules.trimNotes("\r\n  Fixes.\r\n\r\n- One\r- Two\n\n") shouldBe "Fixes.\n\n- One\n- Two"
        ReleaseRules.trimNotes(null) shouldBe ""
    }

    @Test
    fun `long notes are cut at a line end near the limit, with an ellipsis`() {
        val line = "x".repeat(99)
        val body = List(100) { line }.joinToString("\n")

        val notes = ReleaseRules.trimNotes(body, max = 1_000)

        notes shouldEndWith "\n…"
        notes.removeSuffix("\n…") shouldBe List(10) { line }.joinToString("\n")
    }

    @Test
    fun `long notes with no line end near the limit are cut at the limit`() {
        val notes = ReleaseRules.trimNotes("y".repeat(5_000), max = 1_000)

        notes shouldBe "y".repeat(1_000) + "\n…"
        notes shouldNotContain "\r"
    }

    @Test
    fun `notes within the limit are kept whole`() {
        val body = "z".repeat(ReleaseRules.NOTES_MAX_CHARS)
        ReleaseRules.trimNotes(body) shouldBe body
    }
}
