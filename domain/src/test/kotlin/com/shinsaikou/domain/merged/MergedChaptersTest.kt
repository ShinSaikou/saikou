package com.shinsaikou.domain.merged

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Section 4 of `design/plans/merged-entries.md`: order is the only rule. */
class MergedChaptersTest {

    private fun member(mangaId: Long, position: Long) =
        MergedMember(mangaId = mangaId, source = mangaId * 10, url = "/$mangaId", position = position, checkUpdates = true)

    private val group = MergedGroup(
        id = 1,
        mainMangaId = 2,
        showAll = false,
        members = listOf(member(1, position = 0), member(2, position = 1)),
    )
    private val allInstalled = setOf(10L, 20L)

    private fun chapter(id: Long, mangaId: Long, number: Double) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number)

    private fun visible(vararg chapters: Chapter, of: MergedGroup = group, installed: Set<Long> = allInstalled) =
        MergedChapters.visible(chapters.toList(), of, installed).map { it.id }

    @Test
    fun `the first member in the order wins a number, whichever is main, and a gap falls through`() {
        visible(chapter(1, 1, 1.0), chapter(2, 2, 1.0), chapter(3, 2, 2.0)) shouldContainExactly listOf(1, 3)
    }

    @Test
    fun `an uninstalled source ranks last`() {
        visible(chapter(1, 1, 1.0), chapter(2, 2, 1.0), installed = setOf(20L)) shouldContainExactly listOf(2)
    }

    @Test
    fun `every row of the winning member stays`() {
        visible(chapter(1, 1, 1.0), chapter(2, 1, 1.0), chapter(3, 2, 1.0)) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a number below zero is always kept`() {
        visible(chapter(1, 1, -1.0), chapter(2, 2, -1.0)) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `show every copy skips the pass`() {
        visible(chapter(1, 1, 1.0), chapter(2, 2, 1.0), of = group.copy(showAll = true)) shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `after the downloaded filter, a lower copy stands in for the winner it removed`() {
        visible(chapter(2, 2, 1.0)) shouldContainExactly listOf(2)
    }

    @Test
    fun `a stored sort by source reads as the number sort, in the same direction`() {
        val stored = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_ASC)

        val sorted = MergedChapters.sorted(stored)

        sorted.sorting shouldBe Manga.CHAPTER_SORTING_NUMBER
        sorted.sortDescending() shouldBe stored.sortDescending()
    }

    @Test
    fun `any other stored sort stays`() {
        val stored = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_UPLOAD_DATE)

        MergedChapters.sorted(stored) shouldBe stored
    }
}

/** The merged list's sort and the hidden-download badge (section 4). */
class MergedChaptersSortTest {

    private fun chapter(id: Long, number: Double, sourceOrder: Long = 0, upload: Long = 0, mangaId: Long = 1) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number, sourceOrder = sourceOrder, dateUpload = upload)

    private fun manga(sorting: Long) = Manga.create().copy(chapterFlags = sorting)

    private fun List<Chapter>.sortedBy(manga: Manga, descending: Boolean) =
        sortedWith(MergedChapters.sort(manga, descending)).map { it.id }

    @Test
    fun `by source order the merged list sorts by number, ascending`() {
        val chapters = listOf(chapter(1, 3.0, sourceOrder = 0), chapter(2, 1.0, sourceOrder = 9), chapter(3, 2.0, sourceOrder = 5))

        chapters.sortedBy(manga(Manga.CHAPTER_SORTING_SOURCE), descending = false) shouldContainExactly listOf(2, 3, 1)
    }

    @Test
    fun `by source order the merged list sorts by number, descending`() {
        val chapters = listOf(chapter(1, 3.0, sourceOrder = 0), chapter(2, 1.0, sourceOrder = 9), chapter(3, 2.0, sourceOrder = 5))

        chapters.sortedBy(manga(Manga.CHAPTER_SORTING_SOURCE), descending = true) shouldContainExactly listOf(1, 3, 2)
    }

    @Test
    fun `by upload date the main entry's own sort applies`() {
        val chapters = listOf(chapter(1, 1.0, upload = 30), chapter(2, 2.0, upload = 10), chapter(3, 3.0, upload = 20))

        chapters.sortedBy(manga(Manga.CHAPTER_SORTING_UPLOAD_DATE), descending = false) shouldContainExactly listOf(2, 3, 1)
    }

    @Test
    fun `by number the main entry's own sort applies`() {
        val chapters = listOf(chapter(1, 3.0), chapter(2, 1.0), chapter(3, 2.0))

        chapters.sortedBy(manga(Manga.CHAPTER_SORTING_NUMBER), descending = true) shouldContainExactly listOf(1, 3, 2)
    }

    @Test
    fun `the direction defaults to the main entry's own`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 2.0))
        val ascending = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_ASC)
        val descending = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_DESC)

        chapters.sortedWith(MergedChapters.sort(ascending)).map { it.id } shouldContainExactly listOf(1, 2)
        chapters.sortedWith(MergedChapters.sort(descending)).map { it.id } shouldContainExactly listOf(2, 1)
    }

    @Test
    fun `a hidden copy on disk badges the shown row of its number`() {
        val shown = chapter(1, 1.0, mangaId = 1)
        val other = chapter(2, 2.0, mangaId = 1)
        val hidden = chapter(3, 1.0, mangaId = 2)

        MergedChapters.withHiddenDownload(all = listOf(shown, other, hidden), shown = listOf(shown, other), downloaded = setOf(3L)) shouldBe setOf(1L)
    }

    @Test
    fun `a shown downloaded copy badges nothing`() {
        val shown = chapter(1, 1.0, mangaId = 1)
        val hidden = chapter(3, 1.0, mangaId = 2)

        MergedChapters.withHiddenDownload(listOf(shown, hidden), listOf(shown), downloaded = setOf(1L)) shouldBe emptySet()
    }

    @Test
    fun `a hidden copy with no recognised number badges nothing`() {
        val shown = chapter(1, -1.0, mangaId = 1)
        val hidden = chapter(3, -1.0, mangaId = 2)

        MergedChapters.withHiddenDownload(listOf(shown, hidden), listOf(shown), downloaded = setOf(3L)) shouldBe emptySet()
    }

    @Test
    fun `a hidden copy that is not downloaded badges nothing`() {
        val shown = chapter(1, 1.0, mangaId = 1)
        val hidden = chapter(3, 1.0, mangaId = 2)

        MergedChapters.withHiddenDownload(listOf(shown, hidden), listOf(shown), downloaded = emptySet()) shouldBe emptySet()
    }
}
