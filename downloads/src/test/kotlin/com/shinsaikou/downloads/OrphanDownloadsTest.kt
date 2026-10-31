package com.shinsaikou.downloads

import com.shinsaikou.domain.download.model.DownloadManifestEntry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The storage screen's list of downloads not in the library (phase 4 unit ST) over the real
 * manifest query and real folders: a removed favourite and an entry whose manga row is gone are
 * listed, a library entry is not, and a delete removes exactly the confirmed titles' rows and files.
 */
class OrphanDownloadsTest : DownloadTestBase() {

    @BeforeEach
    fun seed() {
        insertManga(id = LIBRARY, title = "Kept Title")
        insertManga(id = REMOVED, title = "Removed Title")
        // Removed from the library: the row stays as a non-favourite.
        exec("UPDATE mangas SET favorite = 0 WHERE _id = $REMOVED")
        // MISSING has no manga row at all, as after clear database deleted it.
        insertChapter(id = 10, mangaId = LIBRARY)
        insertChapter(id = 20, mangaId = REMOVED)
        insertChapter(id = 21, mangaId = REMOVED)
    }

    private fun TestScope.orphans() = OrphanDownloads(
        manifestRepository,
        manifest(StandardTestDispatcher(testScheduler)),
    )

    /** A manifest row for [chapterId] of [mangaId] titled [title], with a one-file folder on disk. */
    private suspend fun record(chapterId: Long, mangaId: Long, title: String, sizeBytes: Long, downloadedAt: Long = 0): File {
        realManifestRepository.recordDownload(
            DownloadManifestEntry(
                id = 0,
                chapterId = chapterId,
                mangaId = mangaId,
                sourceId = SOURCE_ID,
                mangaTitle = title,
                mangaUrl = "/manga/$mangaId",
                chapterUrl = "/ch/$chapterId",
                chapterName = "Chapter $chapterId",
                scanlator = null,
                chapterDirName = "Chapter $chapterId",
                pageCount = PAGES.toLong(),
                sizeBytes = sizeBytes,
                dateDownloaded = downloadedAt,
            ),
        )
        return File(downloadRoot.mangaDir(source.name, title), "Chapter $chapterId").also {
            it.mkdirs()
            File(it, "001.png").writeBytes(byteArrayOf(1))
        }
    }

    @Test
    fun `a removed favourite and a missing manga row are listed, grouped, and a library entry is not`() = runTest {
        record(10, LIBRARY, "Kept Title", sizeBytes = 100)
        record(20, REMOVED, "Removed Title", sizeBytes = 300)
        record(21, REMOVED, "Removed Title", sizeBytes = 400)
        record(30, MISSING, "Gone Title", sizeBytes = 50)

        orphans().list() shouldContainExactly listOf(
            OrphanTitle(mangaId = MISSING, title = "Gone Title", chapterIds = listOf(30), sizeBytes = 50),
            OrphanTitle(mangaId = REMOVED, title = "Removed Title", chapterIds = listOf(20, 21), sizeBytes = 700),
        )
    }

    @Test
    fun `the newest download's title names a title whose rows disagree`() = runTest {
        record(20, REMOVED, "Old Name", sizeBytes = 1, downloadedAt = 100)
        record(21, REMOVED, "New Name", sizeBytes = 1, downloadedAt = 200)

        orphans().list().map { it.title } shouldContainExactly listOf("New Name")
    }

    @Test
    fun `a delete removes the confirmed titles' folders and rows and nothing else`() = runTest {
        val kept = record(10, LIBRARY, "Kept Title", sizeBytes = 100)
        val removedFolders = listOf(
            record(20, REMOVED, "Removed Title", sizeBytes = 300),
            record(21, REMOVED, "Removed Title", sizeBytes = 400),
        )
        val missing = record(30, MISSING, "Gone Title", sizeBytes = 50)
        val orphans = orphans()
        val confirmed = orphans.list().filter { it.mangaId == REMOVED }

        orphans.delete(confirmed) shouldContainExactlyInAnyOrder listOf(20L, 21L)

        removedFolders.map { it.exists() } shouldBe listOf(false, false)
        realManifestRepository.getAll().map { it.chapterId } shouldContainExactlyInAnyOrder listOf(10L, 30L)
        kept.exists() shouldBe true
        missing.exists() shouldBe true
    }

    @Test
    fun `a title added back and a row downloaded after the confirmation opened keep their files`() = runTest {
        record(20, REMOVED, "Removed Title", sizeBytes = 300)
        record(30, MISSING, "Gone Title", sizeBytes = 50)
        val orphans = orphans()
        val confirmed = orphans.list()
        // After the confirmation opened: REMOVED goes back into the library, MISSING gets a new row.
        exec("UPDATE mangas SET favorite = 1 WHERE _id = $REMOVED")
        val later = record(31, MISSING, "Gone Title", sizeBytes = 60)

        orphans.delete(confirmed) shouldContainExactly listOf(30L)

        realManifestRepository.getAll().map { it.chapterId } shouldContainExactlyInAnyOrder listOf(20L, 31L)
        later.exists() shouldBe true
    }

    private companion object {
        const val LIBRARY = 1L
        const val REMOVED = 2L
        const val MISSING = 3L
    }
}
