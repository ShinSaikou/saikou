package com.shinsaikou.downloads

import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.source.model.MangaInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.FetchInterval
import java.io.File

/**
 * Update library manga titles to match source, against the real tables (F9). Upstream's text warns
 * that a renamed entry leaves the download queue, which is what upstream's `renameManga` does when it
 * renames the folder. Saikou's title write touches the entry row only: the queue rows hold no title
 * or path, and a download's folder is found through its manifest row's own title, the one the
 * folder was named with. This is the evidence the list asked a device for, on the JVM.
 */
class TitleUpdateKeepsDownloadsTest : DownloadTestBase() {

    @BeforeEach
    fun seed() {
        // insertManga writes a favourite, so the title follows the source only with the setting on.
        insertManga(id = 1, title = OLD_TITLE)
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1)
    }

    private val updateManga by lazy {
        UpdateManga(mangaRepository, FetchInterval(GetChaptersByMangaId(chapterRepository)))
    }

    private fun TestScope.locator() = locator(StandardTestDispatcher(testScheduler))

    /** Chapter 10 downloaded under the old title, its folder on disk; chapter 11 waiting in the queue. */
    private suspend fun downloadedAndQueued(): File {
        val chapter = chapter(10)
        realManifestRepository.recordDownload(manifestEntry(chapter, manga(1), chapterDirName(10)))
        queueRepository.enqueue(chapterId = 11, mangaId = 1, now = 0)
        return finalDir(10).also { it.mkdirs() }
    }

    private suspend fun renameFromSource() {
        val updated = updateManga.awaitUpdateFromSource(
            localManga = manga(1),
            remote = MangaInfo(url = "/manga/1", title = NEW_TITLE),
            manualFetch = false,
            updateTitles = true,
        )
        updated shouldBe true
        manga(1).title shouldBe NEW_TITLE
    }

    @Test
    fun `a title update leaves the queue rows and the manifest row as they were, and the folder is still found`() = runTest {
        val folder = downloadedAndQueued()
        val queueBefore = queueRepository.getByMangaId(1)
        val manifestBefore = realManifestRepository.getByChapterId(10)!!

        renameFromSource()

        queueRepository.getByMangaId(1) shouldBe queueBefore
        queueBefore.map { it.chapterId } shouldBe listOf(11L)
        val manifestAfter = realManifestRepository.getByChapterId(10)!!
        manifestAfter shouldBe manifestBefore
        manifestAfter.mangaTitle shouldBe OLD_TITLE
        locator().findChapterDir(manifestAfter) shouldBe folder
    }

    /**
     * A chapter downloaded after the rename goes under the new title, so the entry has two folders;
     * each manifest row names its own, and the locator finds both.
     */
    @Test
    fun `after a title update an entry's old and new folders are both found, row by row`() = runTest {
        val oldFolder = downloadedAndQueued()

        renameFromSource()
        realManifestRepository.recordDownload(manifestEntry(chapter(11), manga(1), chapterDirName(11)))
        val newFolder = finalDir(11).also { it.mkdirs() }

        newFolder.parentFile shouldNotBe oldFolder.parentFile
        locator().findChapterDir(realManifestRepository.getByChapterId(10)!!) shouldBe oldFolder
        locator().findChapterDir(realManifestRepository.getByChapterId(11)!!) shouldBe newFolder
    }

    private companion object {
        const val OLD_TITLE = "Fixture Title Before"
        const val NEW_TITLE = "Fixture Title After"
    }
}
