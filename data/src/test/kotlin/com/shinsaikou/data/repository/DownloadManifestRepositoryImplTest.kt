package com.shinsaikou.data.repository

import app.cash.turbine.test
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DownloadManifestRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { DownloadManifestRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertManga(id = 2, favorite = false)
        (10L..13L).forEach { insertChapter(id = it, mangaId = 1) }
    }

    @Test
    fun `recordDownload round-trips every column`() = runTest {
        repository.recordDownload(entry(chapterId = 10))

        val stored = repository.getByChapterId(10).shouldNotBeNull()
        stored shouldBe entry(chapterId = 10).copy(id = stored.id)
    }

    @Test
    fun `recording the same chapter twice updates size, pages, directory and date`() = runTest {
        repository.recordDownload(entry(chapterId = 10))
        repository.recordDownload(
            entry(chapterId = 10, pageCount = 30, sizeBytes = 900, dirName = "d2", downloadedAt = 700),
        )

        val stored = repository.getByChapterId(10).shouldNotBeNull()
        stored.pageCount shouldBe 30
        stored.sizeBytes shouldBe 900
        stored.chapterDirName shouldBe "d2"
        stored.dateDownloaded shouldBe 700
        repository.getByMangaId(1).size shouldBe 1
    }

    @Test
    fun `getTotalSizeBytes is zero on an empty table and sums otherwise`() = runTest {
        repository.getTotalSizeBytes() shouldBe 0

        repository.recordDownload(entry(chapterId = 10, sizeBytes = 100))
        repository.recordDownload(entry(chapterId = 11, sizeBytes = 250))

        repository.getTotalSizeBytes() shouldBe 350
    }

    @Test
    fun `getOrphans returns rows whose manga is missing or not a favourite`() = runTest {
        repository.recordDownload(entry(chapterId = 10, mangaId = 1))
        repository.recordDownload(entry(chapterId = 11, mangaId = 2))
        // No foreign key on this table, so a row may point at a manga that no longer exists.
        repository.recordDownload(entry(chapterId = 12, mangaId = 404))

        repository.getOrphans().map { it.chapterId } shouldContainExactlyInAnyOrder listOf(11L, 12L)
    }

    @Test
    fun `getAll returns every row, orphans included`() = runTest {
        repository.recordDownload(entry(chapterId = 10, mangaId = 1))
        repository.recordDownload(entry(chapterId = 12, mangaId = 404))

        repository.getAll().map { it.chapterId } shouldContainExactlyInAnyOrder listOf(10L, 12L)
    }

    @Test
    fun `getWithoutChapter returns rows whose chapter is deleted or soft deleted, and no other`() = runTest {
        repository.recordDownload(entry(chapterId = 10))
        repository.recordDownload(entry(chapterId = 11))
        repository.recordDownload(entry(chapterId = 12))
        exec("DELETE FROM chapters WHERE _id = 11")
        exec("UPDATE chapters SET deleted_at = 1 WHERE _id = 12")

        repository.getWithoutChapter().map { it.chapterId } shouldContainExactlyInAnyOrder listOf(11L, 12L)
    }

    @Test
    fun `repoint moves a row to another chapter and keeps its folder name`() = runTest {
        repository.recordDownload(entry(chapterId = 10, dirName = "old folder"))

        repository.repoint(
            fromChapterId = 10,
            toChapterId = 13,
            chapterUrl = "https://example.invalid/ch/13",
            chapterName = "Chapter 13",
            scanlator = "Group B",
        )

        repository.getByChapterId(10) shouldBe null
        val moved = repository.getByChapterId(13).shouldNotBeNull()
        moved.chapterUrl shouldBe "https://example.invalid/ch/13"
        moved.chapterName shouldBe "Chapter 13"
        moved.scanlator shouldBe "Group B"
        moved.chapterDirName shouldBe "old folder"
        moved.pageCount shouldBe 20
    }

    @Test
    fun `moveToChapter moves a row to another entry's chapter with every identity column and keeps its counts`() = runTest {
        repository.recordDownload(entry(chapterId = 10, mangaId = 1, dirName = "old folder"))
        val target = entry(chapterId = 30, mangaId = 3, pageCount = 99, sizeBytes = 1, dirName = "new folder", downloadedAt = 9)
            .copy(sourceId = 2, scanlator = "Group B")

        repository.moveToChapter(fromChapterId = 10, entry = target)

        repository.getByChapterId(10) shouldBe null
        repository.getByMangaId(1) shouldBe emptyList()
        val moved = repository.getByChapterId(30).shouldNotBeNull()
        moved.copy(id = 0) shouldBe target.copy(pageCount = 20, sizeBytes = 1_000, dateDownloaded = 500)
    }

    @Test
    fun `subscribeCountsByManga counts the rows of each manga`() = runTest {
        repository.recordDownload(entry(chapterId = 10, mangaId = 1))
        repository.recordDownload(entry(chapterId = 11, mangaId = 1))
        repository.recordDownload(entry(chapterId = 20, mangaId = 2))

        repository.subscribeCountsByManga().first() shouldBe mapOf(1L to 2L, 2L to 1L)
    }

    @Test
    fun `subscribeCountsByManga re-emits when the last row of a manga goes`() = runTest {
        repository.recordDownload(entry(chapterId = 10, mangaId = 1))

        repository.subscribeCountsByManga().test {
            awaitItem() shouldBe mapOf(1L to 1L)

            repository.remove(listOf(10))
            awaitItem() shouldBe emptyMap()
        }
    }

    @Test
    fun `subscribeByMangaId re-emits when a row is recorded`() = runTest {
        repository.subscribeByMangaId(1).test {
            awaitItem() shouldBe emptyList()

            repository.recordDownload(entry(chapterId = 10))
            awaitItem().map { it.chapterId } shouldContainExactly listOf(10L)
        }
    }

    @Test
    fun `getByChapterDirNames returns rows of every manga with a listed name and no other`() = runTest {
        repository.recordDownload(entry(chapterId = 10, mangaId = 1, dirName = "Ch 1"))
        repository.recordDownload(entry(chapterId = 20, mangaId = 2, dirName = "Ch 1"))
        repository.recordDownload(entry(chapterId = 21, mangaId = 2, dirName = "Ch 2"))
        repository.recordDownload(entry(chapterId = 11, mangaId = 1, dirName = "Ch 3"))

        repository.getByChapterDirNames(listOf("Ch 1", "Ch 2")).map { it.chapterId } shouldContainExactlyInAnyOrder
            listOf(10L, 20L, 21L)
    }

    @Test
    fun `getByChapterDirNames matches names exactly, not by case or extension`() = runTest {
        repository.recordDownload(entry(chapterId = 10, dirName = "Ch 1.cbz"))
        repository.recordDownload(entry(chapterId = 11, dirName = "ch 1"))
        repository.recordDownload(entry(chapterId = 12, dirName = "Ch 1"))

        repository.getByChapterDirNames(listOf("Ch 1")).map { it.chapterId } shouldContainExactly listOf(12L)
        repository.getByChapterDirNames(listOf("CH 1")) shouldBe emptyList()
    }

    @Test
    fun `getByChapterDirNames returns an empty list for an empty collection`() = runTest {
        repository.recordDownload(entry(chapterId = 10, dirName = "Ch 1"))

        repository.getByChapterDirNames(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `getByChapterDirNames does not repeat a row when a name is listed twice`() = runTest {
        repository.recordDownload(entry(chapterId = 10, dirName = "Ch 1"))

        repository.getByChapterDirNames(listOf("Ch 1", "Ch 1", "Ch 1")).map { it.chapterId } shouldContainExactly
            listOf(10L)
    }

    @Test
    fun `getByChapterDirNames over 999 names returns each matching row once from every chunk`() = runTest {
        val names = (0 until 2_500).map { "name $it" }
        // Chunks are names 0-998, 999-1997 and 1998-2499: one match in the first, middle and last.
        val matching = listOf(5, 1_500, 2_499)
        matching.forEachIndexed { i, index ->
            repository.recordDownload(entry(chapterId = 100L + i, dirName = names[index]))
        }
        repository.recordDownload(entry(chapterId = 200, dirName = "not listed"))

        repository.getByChapterDirNames(names).map { it.chapterDirName } shouldContainExactlyInAnyOrder
            matching.map { names[it] }
    }

    @Test
    fun `a manifest row outlives its chapter and its manga`() = runTest {
        repository.recordDownload(entry(chapterId = 10))

        exec("DELETE FROM chapters WHERE _id = 10")
        exec("DELETE FROM mangas WHERE _id = 1")

        repository.getByChapterId(10).shouldNotBeNull()
    }

    private fun entry(
        chapterId: Long,
        mangaId: Long = 1,
        pageCount: Long = 20,
        sizeBytes: Long = 1_000,
        dirName: String = "d1",
        downloadedAt: Long = 500,
    ) = DownloadManifestEntry(
        id = 0,
        chapterId = chapterId,
        mangaId = mangaId,
        sourceId = 1,
        mangaTitle = "Title $mangaId",
        mangaUrl = "https://example.invalid/manga/$mangaId",
        chapterUrl = "https://example.invalid/ch/$chapterId",
        chapterName = "Chapter $chapterId",
        scanlator = null,
        chapterDirName = dirName,
        pageCount = pageCount,
        sizeBytes = sizeBytes,
        dateDownloaded = downloadedAt,
    )
}
