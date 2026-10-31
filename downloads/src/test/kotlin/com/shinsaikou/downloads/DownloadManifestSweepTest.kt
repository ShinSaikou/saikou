package com.shinsaikou.downloads

import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/** Phase 2 decision Q7(b): the re-match of orphan rows and the removal of rows whose folder is gone. */
class DownloadManifestSweepTest : DownloadTestBase() {

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertChapter(id = 10, mangaId = 1)
    }

    private fun TestScope.sweep() = DownloadManifestSweep(
        manifestRepository,
        chapterRepository,
        locator(StandardTestDispatcher(testScheduler)),
        libraryPreferences,
        writeLock,
    )

    /** A manifest row for [chapterId] under [dirName], with its folder on disk unless [withFolder] is false. */
    private suspend fun record(chapterId: Long, dirName: String, withFolder: Boolean = true): File {
        val chapter = chapter(chapterId)
        val manga = manga(chapter.mangaId)
        realManifestRepository.recordDownload(manifestEntry(chapter, manga, dirName))
        return File(mangaDir(manga.id), dirName).also {
            if (withFolder) {
                it.mkdirs()
                File(it, "001.png").writeBytes(byteArrayOf(1))
            }
        }
    }

    @Test
    fun `a row whose chapter row was replaced is repointed at the chapter its folder name belongs to`() = runTest {
        val folder = record(10, chapterDirName(10))
        // The source dropped and re-listed the chapter: same url and name, new row.
        exec("DELETE FROM chapters WHERE _id = 10")
        insertChapter(id = 20, mangaId = 1, name = "Chapter 10", url = "/ch/10")

        sweep().run() shouldBe DownloadManifestSweep.Result(rematched = 1, removed = 0, unmatched = 0)

        realManifestRepository.getByChapterId(10).shouldBeNull()
        val moved = realManifestRepository.getByChapterId(20).shouldNotBeNull()
        moved.chapterDirName shouldBe folder.name
        manifest(StandardTestDispatcher(testScheduler)).isDownloaded(20) shouldBe true
    }

    @Test
    fun `rematch alone repoints a row and leaves a row whose folder is missing`() = runTest {
        insertChapter(id = 11, mangaId = 1)
        record(10, chapterDirName(10))
        record(11, chapterDirName(11), withFolder = false)
        exec("DELETE FROM chapters WHERE _id = 10")
        insertChapter(id = 20, mangaId = 1, name = "Chapter 10", url = "/ch/10")

        sweep().rematch() shouldBe 1

        realManifestRepository.getByChapterId(20).shouldNotBeNull()
        realManifestRepository.getByChapterId(11).shouldNotBeNull()
    }

    @Test
    fun `a legacy folder name re-matches too`() = runTest {
        val chapter = chapter(10)
        val legacy = DownloadProvider.getLegacyChapterDirNames(chapter.name, chapter.scanlator, chapter.url, false)
            .first()
        record(10, legacy)
        exec("DELETE FROM chapters WHERE _id = 10")
        insertChapter(id = 20, mangaId = 1, name = "Chapter 10", url = "/ch/10")

        sweep().run().rematched shouldBe 1

        realManifestRepository.getByChapterId(20).shouldNotBeNull().chapterDirName shouldBe legacy
    }

    @Test
    fun `a row that matches nothing keeps its row and its files`() = runTest {
        val folder = record(10, chapterDirName(10))
        exec("DELETE FROM chapters WHERE _id = 10")
        insertChapter(id = 20, mangaId = 1, name = "Chapter 10", url = "/ch/10-reuploaded")

        sweep().run() shouldBe DownloadManifestSweep.Result(rematched = 0, removed = 0, unmatched = 1)

        realManifestRepository.getWithoutChapter().map { it.chapterId } shouldContainExactlyInAnyOrder listOf(10L)
        folder.isDirectory shouldBe true
    }

    @Test
    fun `two chapters answering to one folder name leave the row unmatched`() = runTest {
        // The pre-hash legacy name carries no url, so two uploads of one chapter both answer to it.
        val legacy = DownloadProvider.getLegacyChapterDirNames("Chapter 10", null, "/ch/10", false).first()
        record(10, legacy)
        exec("DELETE FROM chapters WHERE _id = 10")
        insertChapter(id = 20, mangaId = 1, name = "Chapter 10", url = "/ch/10-a")
        insertChapter(id = 21, mangaId = 1, name = "Chapter 10", url = "/ch/10-b")

        sweep().run() shouldBe DownloadManifestSweep.Result(rematched = 0, removed = 0, unmatched = 1)

        realManifestRepository.getByChapterId(10).shouldNotBeNull()
    }

    @Test
    fun `a second chapter answering to the folder name leaves the row unmatched though it has a download`() =
        runTest {
            val legacy = DownloadProvider.getLegacyChapterDirNames("Chapter 10", null, "/ch/10", false).first()
            record(10, legacy)
            exec("DELETE FROM chapters WHERE _id = 10")
            insertChapter(id = 20, mangaId = 1, name = "Chapter 10", url = "/ch/10-a")
            insertChapter(id = 21, mangaId = 1, name = "Chapter 10", url = "/ch/10-b")
            // 20 has a download of its own, yet the old folder could as well hold 20's pages as 21's.
            record(20, chapterDirName(20))

            sweep().run() shouldBe DownloadManifestSweep.Result(rematched = 0, removed = 0, unmatched = 1)

            realManifestRepository.getByChapterId(10).shouldNotBeNull()
            realManifestRepository.getByChapterId(21).shouldBeNull()
        }

    @Test
    fun `a row whose folder is missing is removed and one whose folder is there is kept`() = runTest {
        insertChapter(id = 11, mangaId = 1)
        record(10, chapterDirName(10))
        record(11, chapterDirName(11), withFolder = false)

        sweep().run() shouldBe DownloadManifestSweep.Result(rematched = 0, removed = 1, unmatched = 0)

        realManifestRepository.getByChapterId(10).shouldNotBeNull()
        realManifestRepository.getByChapterId(11).shouldBeNull()
    }

    @Test
    fun `a folder under a source folder of another name still counts, so a renamed source loses nothing`() = runTest {
        val folder = record(10, chapterDirName(10))
        val renamedSource = File(downloadRoot.root(), "Renamed Source")
        folder.parentFile!!.parentFile!!.renameTo(renamedSource)

        sweep().run().removed shouldBe 0

        realManifestRepository.getByChapterId(10).shouldNotBeNull()
        manifest(StandardTestDispatcher(testScheduler)).getChapterDir(10)!!.parentFile!!.parentFile shouldBe
            renamedSource
    }

    @Test
    fun `a folder named under the other ascii setting still counts`() = runTest {
        insertManga(id = 2, title = "Titlé")
        insertChapter(id = 30, mangaId = 2, name = "Chapitré")
        val chapter = chapter(30)
        val manga = manga(2)
        val asciiName = DownloadProvider.getChapterDirName(chapter.name, null, chapter.url, disallowNonAscii = true)
        realManifestRepository.recordDownload(manifestEntry(chapter, manga, asciiName))
        File(
            File(downloadRoot.root(), DownloadProvider.getSourceDirName(source.name, true)),
            DownloadProvider.getMangaDirName(manga.title, true) + "/" + asciiName,
        ).mkdirs()

        sweep().run().removed shouldBe 0

        realManifestRepository.getByChapterId(30).shouldNotBeNull()
    }

    @Test
    fun `a manifest with no downloads folder at all loses every row`() = runTest {
        record(10, chapterDirName(10), withFolder = false)
        downloadRoot.root().exists() shouldBe false

        sweep().run() shouldBe DownloadManifestSweep.Result(rematched = 0, removed = 1, unmatched = 0)
    }

    /**
     * Unit migrate-downloads: a migration renames a folder and then repoints its row, both under the
     * write lock; a removal run between the two would delete the row of a download that is there.
     */
    @Test
    fun `the removal of rows whose folder is gone waits for the write lock`() = runTest {
        record(10, chapterDirName(10), withFolder = false)
        val sweep = sweep()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        launch {
            writeLock.withLock {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        val run = async { sweep.run() }
        advanceUntilIdle()

        run.isCompleted shouldBe false
        realManifestRepository.getByChapterId(10).shouldNotBeNull()
        release.complete(Unit)
        run.await() shouldBe DownloadManifestSweep.Result(rematched = 0, removed = 1, unmatched = 0)
    }

    @Test
    fun `deleting a download removes its folder and its empty parents before its row`() = runTest {
        val folder = record(10, chapterDirName(10))
        val manifest = manifest(StandardTestDispatcher(testScheduler))

        manifest.delete(listOf(10))

        folder.exists() shouldBe false
        folder.parentFile!!.exists() shouldBe false
        folder.parentFile!!.parentFile!!.exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldBeNull()
    }

    @Test
    fun `a row whose folder is missing is not downloaded`() = runTest {
        record(10, chapterDirName(10), withFolder = false)
        val manifest = manifest(StandardTestDispatcher(testScheduler))

        manifest.isDownloaded(10) shouldBe false
        manifest.getPages(10).shouldBeNull()
        manifest.getEntry(10).shouldNotBeNull()
    }
}
