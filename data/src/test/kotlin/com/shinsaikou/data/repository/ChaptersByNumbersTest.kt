package com.shinsaikou.data.repository

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.chapter.ChapterRepositoryImpl

/** `getChaptersByMangaIdsAndNumbers` (chapters.sq): the copies the Updates tab's fold compares. */
class ChaptersByNumbersTest : RepositoryTest() {

    private val repository by lazy { ChapterRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertManga(id = 2)
        insertManga(id = 3)
    }

    private fun chapter(id: Long, mangaId: Long, number: Double, scanlator: String = "Group", deletedAt: Long? = null) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo, deleted_at)
        VALUES ($id, $mangaId, '/c/$id', 'Chapter $id', '$scanlator', 0, 0, 0, $number, $id, 0, 0,
            CAST('{}' AS BLOB), ${deletedAt ?: "NULL"})
        """,
    )

    private suspend fun ids(mangaIds: List<Long>, numbers: List<Double>, applyScanlatorFilter: Boolean = false) =
        repository.getChaptersByNumbers(mangaIds, numbers, applyScanlatorFilter).map { it.id }

    @Test
    fun `only the entries and numbers asked for, and no deleted row`() = runTest {
        chapter(10, mangaId = 1, number = 1.0)
        chapter(11, mangaId = 1, number = 2.5)
        chapter(12, mangaId = 2, number = 1.0)
        chapter(13, mangaId = 2, number = 3.0)
        chapter(14, mangaId = 3, number = 1.0)
        chapter(15, mangaId = 1, number = 1.0, deletedAt = 5)

        ids(listOf(1, 2), listOf(1.0, 2.5)) shouldContainExactlyInAnyOrder listOf(10L, 11L, 12L)
    }

    @Test
    fun `the scanlator filter leaves out an entry's excluded scanlator`() = runTest {
        chapter(10, mangaId = 1, number = 1.0, scanlator = "Kept")
        chapter(11, mangaId = 1, number = 1.0, scanlator = "Hidden")
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Hidden')")

        ids(listOf(1), listOf(1.0)) shouldContainExactlyInAnyOrder listOf(10L, 11L)
        ids(listOf(1), listOf(1.0), applyScanlatorFilter = true) shouldContainExactlyInAnyOrder listOf(10L)
    }
}
