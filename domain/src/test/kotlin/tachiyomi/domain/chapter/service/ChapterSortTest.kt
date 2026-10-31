// Fresh: written for Saikou. Placed in the vendored package so it can read internals.
package tachiyomi.domain.chapter.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

@Execution(ExecutionMode.CONCURRENT)
class ChapterSortTest {

    @Test
    fun `source order descending keeps the source's own order`() {
        val sorted = chapters.sortedWith(getChapterSort(mangaSortedBy(Manga.CHAPTER_SORTING_SOURCE), true))

        sorted.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L)
    }

    @Test
    fun `source order ascending reverses the source's own order`() {
        val sorted = chapters.sortedWith(getChapterSort(mangaSortedBy(Manga.CHAPTER_SORTING_SOURCE), false))

        sorted.map { it.sourceOrder } shouldBe listOf(2L, 1L, 0L)
    }

    @Test
    fun `chapter number ascending and descending`() {
        val manga = mangaSortedBy(Manga.CHAPTER_SORTING_NUMBER)

        chapters.sortedWith(getChapterSort(manga, false)).map { it.chapterNumber } shouldBe listOf(1.0, 2.0, 3.0)
        chapters.sortedWith(getChapterSort(manga, true)).map { it.chapterNumber } shouldBe listOf(3.0, 2.0, 1.0)
    }

    @Test
    fun `upload date ascending and descending`() {
        val manga = mangaSortedBy(Manga.CHAPTER_SORTING_UPLOAD_DATE)

        chapters.sortedWith(getChapterSort(manga, false)).map { it.dateUpload } shouldBe listOf(100L, 200L, 300L)
        chapters.sortedWith(getChapterSort(manga, true)).map { it.dateUpload } shouldBe listOf(300L, 200L, 100L)
    }

    @Test
    fun `alphabetical uses the collator, so case and accents do not split the order`() {
        val manga = mangaSortedBy(Manga.CHAPTER_SORTING_ALPHABET)
        val named = listOf(chapter(0, "beta"), chapter(1, "Alpha"), chapter(2, "alpha"))

        named.sortedWith(getChapterSort(manga, false)).map { it.name } shouldBe listOf("Alpha", "alpha", "beta")
        named.sortedWith(getChapterSort(manga, true)).map { it.name } shouldBe listOf("beta", "Alpha", "alpha")
    }

    @Test
    fun `the default sort direction comes from the manga's own flag`() {
        val descending = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_DESC)
        val ascending = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC)

        chapters.sortedWith(getChapterSort(descending)).map { it.chapterNumber } shouldBe listOf(3.0, 2.0, 1.0)
        chapters.sortedWith(getChapterSort(ascending)).map { it.chapterNumber } shouldBe listOf(1.0, 2.0, 3.0)
    }

    @Test
    fun `an unknown sorting value throws`() {
        val manga = mockk<Manga> {
            every { sorting } returns 0x00000400L
        }

        shouldThrow<NotImplementedError> { getChapterSort(manga, false) }
    }

    private fun mangaSortedBy(sorting: Long) = Manga.create().copy(chapterFlags = sorting)

    private fun chapter(sourceOrder: Long, name: String = "Chapter $sourceOrder") = Chapter.create().copy(
        sourceOrder = sourceOrder,
        name = name,
        chapterNumber = 3.0 - sourceOrder,
        dateUpload = 300L - sourceOrder * 100L,
    )

    private val chapters = listOf(chapter(0), chapter(1), chapter(2))
}
