// Fresh: the test sits in the vendored package so it names the object without an import alias.
package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * These strings are the Phase 2 adoption contract: a folder an upstream install already wrote must be
 * found by the name this object produces. Changing an expectation here orphans real downloads.
 */
class DownloadProviderTest {

    @Test
    fun `a chapter folder is scanlator, name and six characters of the url hash`() {
        DownloadProvider.getChapterDirName(NAME, SCANLATOR, URL, disallowNonAscii = false) shouldBe
            "Group A_Ch. 1_ Start_$HASH"
    }

    @Test
    fun `a chapter with no scanlator drops the prefix`() {
        DownloadProvider.getChapterDirName(NAME, null, URL, disallowNonAscii = false) shouldBe
            "Ch. 1_ Start_$HASH"
        DownloadProvider.getChapterDirName(NAME, "  ", URL, disallowNonAscii = false) shouldBe
            "Ch. 1_ Start_$HASH"
    }

    @Test
    fun `non ascii characters become hex only when the setting says so`() {
        DownloadProvider.getChapterDirName("Ch. é", null, URL, disallowNonAscii = false) shouldBe
            "Ch. é_$HASH"
        DownloadProvider.getChapterDirName("Ch. é", null, URL, disallowNonAscii = true) shouldBe
            "Ch. c3a9_$HASH"
    }

    @Test
    fun `a blank chapter name becomes Chapter`() {
        DownloadProvider.sanitizeChapterName("   ") shouldBe "Chapter"
        DownloadProvider.getChapterDirName("", null, URL, disallowNonAscii = false) shouldBe "Chapter_$HASH"
    }

    @Test
    fun `the name is truncated to leave room for the hash and the cbz suffix`() {
        val long = "a".repeat(500)

        val name = DownloadProvider.getChapterDirName(long, null, URL, disallowNonAscii = false)

        // 240 bytes minus 11 reserved, then "_" and six hash characters.
        name shouldHaveLength 229 + 7
        name shouldEndWith "_$HASH"
    }

    @Test
    fun `the legacy names are the pre hash name and the name under the other ascii setting`() {
        DownloadProvider.getLegacyChapterDirNames(NAME, SCANLATOR, URL, disallowNonAscii = false) shouldBe listOf(
            "Group A_Ch. 1_ Start",
            DownloadProvider.getChapterDirName(NAME, SCANLATOR, URL, disallowNonAscii = true),
        )
    }

    @Test
    fun `valid names are the folder, its cbz, then every legacy name and its cbz`() {
        val current = DownloadProvider.getChapterDirName(NAME, SCANLATOR, URL, disallowNonAscii = false)
        val legacy = DownloadProvider.getLegacyChapterDirNames(NAME, SCANLATOR, URL, disallowNonAscii = false)

        DownloadProvider.getValidChapterDirNames(NAME, SCANLATOR, URL, disallowNonAscii = false) shouldBe listOf(
            current,
            "$current.cbz",
            legacy[0],
            "${legacy[0]}.cbz",
            legacy[1],
            "${legacy[1]}.cbz",
        )
    }

    @Test
    fun `folders upstream 0_21_0 and 0_21_1 named apart from older ones are found`() {
        // Those versions made the scanlator and the name valid apart, which trims a leading space or
        // dot from the name, a trailing one from the scanlator, and keeps a non-ASCII scanlator.
        val cases = listOf(
            Triple(" Chapter 1", "Group", false) to "Group_Chapter 1_$HASH",
            Triple(".Chapter 1", "Group", false) to "Group_Chapter 1_$HASH",
            Triple("Chapter 1", "Group ", false) to "Group_Chapter 1_$HASH",
            Triple("Chapter 1", "A/B ", false) to "A_B_Chapter 1_$HASH",
            Triple("Chapter 1", "翻訳グループ", true) to "翻訳グループ_Chapter 1_$HASH",
            Triple("Chapter ".repeat(40), "Group", false) to
                "Group_" + DiskUtil.buildValidFilename("Chapter ".repeat(40), DiskUtil.MAX_FILE_NAME_BYTES - 11) +
                "_$HASH",
        )

        cases.forEach { (chapter, folder) ->
            val (name, scanlator, disallowNonAscii) = chapter
            val names = DownloadProvider.getValidChapterDirNames(name, scanlator, URL, disallowNonAscii)

            names shouldContain folder
            names shouldContain folder.removeSuffix("_$HASH")
            // New downloads keep the older name.
            DownloadProvider.getChapterDirName(name, scanlator, URL, disallowNonAscii) shouldNotBe folder
        }
    }

    @Test
    fun `a chapter with no scanlator is found under the name those versions gave it without the hash`() {
        // The name alone, made valid under the hash's byte limit; the pre-hash name has a longer limit
        // and keeps non-ASCII characters.
        val long = "Chapter ".repeat(40)
        val cases = listOf(
            Triple("Ch. é", null, false) to "Ch. c3a9",
            Triple("Ch. é", "  ", true) to "Ch. c3a9",
            Triple(long, null, false) to DiskUtil.buildValidFilename(long, DiskUtil.MAX_FILE_NAME_BYTES - 11),
        )

        cases.forEach { (chapter, folder) ->
            val (name, scanlator, disallowNonAscii) = chapter
            DownloadProvider.getValidChapterDirNames(name, scanlator, URL, disallowNonAscii) shouldContain folder
        }
    }

    @Test
    fun `folders upstream 0_21_2 and later named without the hash are found under either ascii setting`() {
        // With the hash setting off those versions write the folder name without its hash: the
        // scanlator and the name made valid as one string under the hash's byte limit. So a non-ASCII
        // scanlator is hex under the non-ASCII setting, and a long name is cut shorter than the
        // pre-hash name.
        val long = "Chapter ".repeat(40)
        val cases = listOf(
            Triple("Chapter 1", "翻訳グループ", true) to "e7bfbbe8a8b3e382b0e383abe383bce38397_Chapter 1",
            Triple(long, "Group", false) to
                DiskUtil.buildValidFilename("Group_$long", DiskUtil.MAX_FILE_NAME_BYTES - 11),
        )

        cases.forEach { (chapter, folder) ->
            val (name, scanlator, upstreamSetting) = chapter
            DownloadProvider.getChapterDirName(name, scanlator, URL, upstreamSetting) shouldBe "${folder}_$HASH"
            // The owner's setting need not be the one upstream had.
            listOf(false, true).forEach { disallowNonAscii ->
                DownloadProvider.getValidChapterDirNames(name, scanlator, URL, disallowNonAscii) shouldContain folder
            }
        }
    }

    @Test
    fun `the names without the hash come with the non-ascii setting off first, as upstream looks for them`() {
        // A long name, so the name kept as it is differs from the pre-hash name and is listed here.
        val long = "Chapter ".repeat(40)
        val (asIs, hex) = listOf(false, true).map {
            DownloadProvider.getChapterDirName(long, "翻訳グループ", URL, it).removeSuffix("_$HASH")
        }

        val names = DownloadProvider.getLegacyChapterDirNames(long, "翻訳グループ", URL, disallowNonAscii = true)

        names shouldContain asIs
        names shouldContain hex
        names.indexOf(asIs) shouldBeLessThan names.indexOf(hex)
    }

    @Test
    fun `a chapter those versions named the same way gets no extra names`() {
        DownloadProvider.getLegacyChapterDirNames(" Chapter 1", null, URL, disallowNonAscii = false) shouldHaveSize 2
        DownloadProvider.getLegacyChapterDirNames(NAME, SCANLATOR, URL, disallowNonAscii = true) shouldHaveSize 2
    }

    @Test
    fun `source and manga folders are the sanitized name`() {
        DownloadProvider.getSourceDirName("Fixture Source A", disallowNonAscii = false) shouldBe "Fixture Source A"
        DownloadProvider.getMangaDirName("Fixture: Manga/A", disallowNonAscii = false) shouldBe "Fixture_ Manga_A"
    }

    @Test
    fun `a rename that changes the folder name is detected`() {
        val old = Chapter.create().copy(name = NAME, scanlator = SCANLATOR, url = URL)

        DownloadProvider.isChapterDirNameChanged(old, old, disallowNonAscii = false) shouldBe false
        DownloadProvider.isChapterDirNameChanged(
            old,
            old.copy(name = "Ch. 1: Restart"),
            disallowNonAscii = false,
        ) shouldBe true
    }

    private companion object {
        const val NAME = "Ch. 1: Start"
        const val SCANLATOR = "Group A"
        const val URL = "/manga/1/ch/1"
        const val HASH = "4b6268"
    }
}
