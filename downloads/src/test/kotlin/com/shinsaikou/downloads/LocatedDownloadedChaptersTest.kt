package com.shinsaikou.downloads

import app.cash.turbine.test
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The folder-aware answer the reader and the chapter list read (phase 2 decision Q4): a manifest row
 * counts while its folder is on disk and not after, and the chapter list's set follows the manifest.
 */
class LocatedDownloadedChaptersTest : DownloadTestBase() {

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1)
        insertManga(id = 2)
        insertChapter(id = 20, mangaId = 2)
    }

    private fun TestScope.downloaded() =
        LocatedDownloadedChapters(manifestRepository, locator(StandardTestDispatcher(testScheduler)))

    private suspend fun record(chapterId: Long, withFolder: Boolean): File {
        val chapter = chapter(chapterId)
        val manga = manga(chapter.mangaId)
        realManifestRepository.recordDownload(manifestEntry(chapter, manga, chapterDirName(chapterId)))
        return finalDir(chapterId).also { if (withFolder) it.mkdirs() }
    }

    @Test
    fun `a row finds its folder, and a row whose folder is gone finds nothing`() = runTest {
        val folder = record(10, withFolder = true)
        record(11, withFolder = false)

        downloaded().find(10) shouldBe folder
        downloaded().find(11).shouldBeNull()
        downloaded().find(99).shouldBeNull()
    }

    @Test
    fun `the set for an entry holds its chapters whose folders exist, and follows the manifest`() = runTest {
        record(10, withFolder = true)
        record(11, withFolder = false)
        record(20, withFolder = true)

        downloaded().subscribeByMangaId(1).test {
            awaitItem() shouldBe setOf(10L)

            finalDir(11).mkdirs()
            realManifestRepository.remove(listOf(10))
            awaitItem() shouldBe setOf(11L)
        }
    }

    /** The Updates tab's downloaded filter (phase 4 unit UF): every entry, and a missing folder counts as not downloaded. */
    @Test
    fun `the set for every entry holds the chapters whose folders exist, and follows the manifest`() = runTest {
        record(10, withFolder = true)
        record(11, withFolder = false)
        record(20, withFolder = true)

        downloaded().subscribeAll().test {
            awaitItem() shouldBe setOf(10L, 20L)

            finalDir(11).mkdirs()
            realManifestRepository.remove(listOf(20))
            awaitItem() shouldBe setOf(10L, 11L)
        }
    }

    /**
     * The Updates rows' download controls (phase 5 unit ID3): only the chapters asked about, across
     * entries, a missing folder counting as not downloaded, and the set following the manifest.
     */
    @Test
    fun `the set for some chapters holds those of them whose folders exist, and follows the manifest`() = runTest {
        record(10, withFolder = true)
        record(11, withFolder = false)
        record(20, withFolder = true)

        downloaded().subscribeByChapterIds(setOf(11L, 20L)).test {
            awaitItem() shouldBe setOf(20L)

            finalDir(11).mkdirs()
            realManifestRepository.remove(listOf(10))
            awaitItem() shouldBe setOf(11L, 20L)
        }
    }

    @Test
    fun `no chapters asked about is an empty set`() = runTest {
        record(10, withFolder = true)

        downloaded().subscribeByChapterIds(emptySet()).test {
            awaitItem() shouldBe emptySet()
            awaitComplete()
        }
    }
}
