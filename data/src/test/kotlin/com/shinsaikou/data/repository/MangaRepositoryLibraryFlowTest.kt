package com.shinsaikou.data.repository

import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.model.LibraryManga
import java.util.Date

/**
 * The library flow over schema version 3 (phase 5 unit SC4): `libraryView` reads
 * `library_summary`, which the chapter, history and exclusion triggers write, and the generated
 * statements of those three tables notify `library_summary` (SQLDelight 2.3.2 adds every table a
 * trigger writes to a statement's notify set). So the flow, which listens on `mangas`,
 * `library_summary` and `mangas_categories`, re-emits on each, as these writes through the
 * repositories show.
 */
class MangaRepositoryLibraryFlowTest : RepositoryTest() {

    private val mangaRepository by lazy { MangaRepositoryImpl(handler) }
    private val chapterRepository by lazy { ChapterRepositoryImpl(handler) }
    private val historyRepository by lazy { HistoryRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1)
        exec("UPDATE chapters SET scanlator = 'A' WHERE _id = 11")
    }

    @Test
    fun `a chapter marked read reaches the library flow`() = runTest {
        mangaRepository.getLibraryMangaAsFlow().test {
            awaitItem().single().counts() shouldBe Counts(total = 2, read = 0, bookmarks = 0, lastRead = 0)

            chapterRepository.update(ChapterUpdate(id = 10, read = true))
            awaitItem().single().counts() shouldBe Counts(total = 2, read = 1, bookmarks = 0, lastRead = 0)

            chapterRepository.update(ChapterUpdate(id = 11, bookmark = true))
            awaitItem().single().counts() shouldBe Counts(total = 2, read = 1, bookmarks = 1, lastRead = 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a history write and an exclusion reach the library flow`() = runTest {
        mangaRepository.getLibraryMangaAsFlow().test {
            awaitItem().single().counts() shouldBe Counts(total = 2, read = 0, bookmarks = 0, lastRead = 0)

            historyRepository.upsertHistory(HistoryUpdate(chapterId = 11, readAt = Date(500), sessionReadDuration = 1))
            awaitItem().single().counts() shouldBe Counts(total = 2, read = 0, bookmarks = 0, lastRead = 500)

            mangaRepository.setExcludedScanlators(1, setOf("A"))
            // The exclusion's own triggers write mangas too, so wait for the counts rather than count emissions.
            var library = awaitItem().single()
            while (library.totalChapters != 1L) library = awaitItem().single()
            library.counts() shouldBe Counts(total = 1, read = 0, bookmarks = 0, lastRead = 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private data class Counts(val total: Long, val read: Long, val bookmarks: Long, val lastRead: Long)

    private fun LibraryManga.counts() = Counts(totalChapters, readCount, bookmarkCount, lastRead)
}
