package com.shinsaikou.appupdate

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The release object as GitHub's latest release endpoint answers it, and the address asked. */
class GitHubReleaseTest {

    @Test
    fun `the fields the updater reads come out of a full answer and the rest is ignored`() {
        val release = GitHubRelease.parse(RELEASE).shouldNotBeNull()

        release.tagName shouldBe "v0.0.2"
        release.body shouldBe "Fixes.\r\n\r\n- One\r\n- Two"
        release.draft shouldBe false
        release.prerelease shouldBe false
        release.assets shouldContainExactly listOf(
            GitHubAsset(
                name = "saikou-0.0.2-arm64-v8a.apk",
                downloadUrl = "https://github.com/example/app/releases/download/v0.0.2/saikou-0.0.2-arm64-v8a.apk",
                size = 31_457_280,
                digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            GitHubAsset(
                name = "notes.txt",
                downloadUrl = "https://github.com/example/app/releases/download/v0.0.2/notes.txt",
                size = 12,
                digest = null,
            ),
        )
    }

    @Test
    fun `a null body and missing flags take their defaults`() {
        val release = GitHubRelease.parse("""{"tag_name":"v1.0.0","body":null,"assets":[]}""").shouldNotBeNull()

        release.body.shouldBeNull()
        release.draft shouldBe false
        release.prerelease shouldBe false
    }

    @Test
    fun `an answer that is not a release does not parse`() {
        GitHubRelease.parse("""{"message":"Not Found","documentation_url":"https://docs.github.com"}""").shouldBeNull()
        GitHubRelease.parse("<html>rate limited</html>").shouldBeNull()
        GitHubRelease.parse("""{"tag_name":"v1.0""").shouldBeNull()
        GitHubRelease.parse("").shouldBeNull()
    }

    @Test
    fun `the latest release address names the repository`() {
        GitHubRelease.latestUrl("ShinSaikou/saikou").toString() shouldBe
            "https://api.github.com/repos/ShinSaikou/saikou/releases/latest"
        GitHubRelease.latestUrl("some-fork/saikou.app").toString() shouldBe
            "https://api.github.com/repos/some-fork/saikou.app/releases/latest"
    }

    private companion object {
        val RELEASE = """
            {
              "url": "https://api.github.com/repos/example/app/releases/1",
              "html_url": "https://github.com/example/app/releases/tag/v0.0.2",
              "id": 1,
              "author": {"login": "example", "id": 2},
              "tag_name": "v0.0.2",
              "target_commitish": "main",
              "name": "0.0.2",
              "draft": false,
              "prerelease": false,
              "created_at": "2026-10-30T10:00:00Z",
              "published_at": "2026-10-30T10:05:00Z",
              "assets": [
                {
                  "url": "https://api.github.com/repos/example/app/releases/assets/3",
                  "id": 3,
                  "name": "saikou-0.0.2-arm64-v8a.apk",
                  "label": "",
                  "content_type": "application/vnd.android.package-archive",
                  "state": "uploaded",
                  "size": 31457280,
                  "digest": "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                  "download_count": 0,
                  "browser_download_url": "https://github.com/example/app/releases/download/v0.0.2/saikou-0.0.2-arm64-v8a.apk"
                },
                {
                  "id": 4,
                  "name": "notes.txt",
                  "size": 12,
                  "digest": null,
                  "browser_download_url": "https://github.com/example/app/releases/download/v0.0.2/notes.txt"
                }
              ],
              "body": "Fixes.\r\n\r\n- One\r\n- Two"
            }
        """.trimIndent()
    }
}
