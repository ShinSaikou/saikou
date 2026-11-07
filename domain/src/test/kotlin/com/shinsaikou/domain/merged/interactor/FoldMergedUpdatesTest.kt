package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * One row per number of a merged title in the Updates list, by the entry's own rule: entry 1 is the
 * main entry and the first source, entry 2 the second. Entry 9 is on its own.
 */
class FoldMergedUpdatesTest {

    private val chapters = mutableListOf<Chapter>()
    private val chapterRepository = mockk<ChapterRepository> {
        coEvery { getChaptersByIds(any()) } answers {
            val ids = firstArg<List<Long>>()
            chapters.filter { it.id in ids }
        }
        coEvery { getChaptersByNumbers(any(), any(), any()) } answers {
            val ids = firstArg<List<Long>>()
            val numbers = secondArg<List<Double>>()
            chapters.filter { it.mangaId in ids && it.chapterNumber in numbers }
        }
    }
    private val getManga = mockk<GetManga> {
        coEvery { await(1L) } returns Manga.create().copy(id = 1L, source = 1L, title = "Main", thumbnailUrl = "/main.jpg")
    }
    private val sourceManager = mockk<SourceManager>().also { every { it.get(any()) } returns mockk<Source>() }
    private val fold = FoldMergedUpdates(chapterRepository, getManga, GetMergedChapters(chapterRepository, sourceManager))

    private fun group(showAll: Boolean = false) = MergedGroup(
        id = 7L,
        mainMangaId = 1L,
        showAll = showAll,
        members = listOf(1L, 2L).mapIndexed { index, id ->
            MergedMember(id, source = id, url = "/$id", position = index.toLong(), checkUpdates = true)
        },
    )

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean = false) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number, read = read).also { chapters += it }

    private fun row(chapter: Chapter) = UpdatesWithRelations(
        mangaId = chapter.mangaId,
        mangaTitle = "Source ${chapter.mangaId}",
        displayTitle = "Source ${chapter.mangaId}",
        chapterId = chapter.id,
        chapterName = "Chapter",
        scanlator = null,
        chapterUrl = "/${chapter.id}",
        read = chapter.read,
        bookmark = false,
        lastPageRead = 0L,
        sourceId = chapter.mangaId,
        dateFetch = chapter.id,
        coverData = MangaCover(chapter.mangaId, chapter.mangaId, true, null, 0L),
    )

    private suspend fun folded(
        rows: List<UpdatesWithRelations>,
        groups: List<MergedGroup> = listOf(group()),
        memberShown: (Long) -> Boolean = { true },
        copyShown: (Chapter) -> Boolean = { true },
    ) = fold.await(rows, groups, hideExcludedScanlators = false, memberShown = { memberShown(it) }, copyShown = copyShown)

    @Test
    fun `no group reads nothing and leaves the rows alone`() = runTest {
        val rows = listOf(row(chapter(1, 1, 1.0)))

        folded(rows, groups = emptyList()) shouldBe rows

        coVerify(exactly = 0) { chapterRepository.getChaptersByIds(any()) }
    }

    @Test
    fun `rows of no merged title read nothing and stay as they are`() = runTest {
        val rows = listOf(row(chapter(1, 9, 1.0)))

        folded(rows) shouldBe rows

        coVerify(exactly = 0) { chapterRepository.getChaptersByIds(any()) }
    }

    @Test
    fun `a number with a copy from each source has the first source's row, under the main entry`() = runTest {
        val first = chapter(1, 1, 5.0)
        val second = chapter(2, 2, 5.0)

        val rows = folded(listOf(row(second), row(first)))

        rows.map { it.chapterId } shouldContainExactly listOf(1L)
        rows.single().titleId shouldBe 1L
    }

    @Test
    fun `a chapter only the second source has keeps its own entry and source under the main title`() = runTest {
        val own = chapter(2, 2, 6.0)

        val row = folded(listOf(row(own))).single()

        row.chapterId shouldBe 2L
        row.mangaId shouldBe 2L
        row.sourceId shouldBe 2L
        row.titleId shouldBe 1L
        row.displayTitle shouldBe "Main"
        row.mangaTitle shouldBe "Main"
        row.coverData.mangaId shouldBe 1L
        row.coverData.url shouldBe "/main.jpg"
    }

    @Test
    fun `a new copy from the second source is no row while the first source has the number off the list`() = runTest {
        chapter(1, 1, 5.0)
        val second = chapter(2, 2, 5.0)

        folded(listOf(row(second))) shouldBe emptyList()
    }

    @Test
    fun `Show every copy keeps both rows, each under the main entry`() = runTest {
        val first = chapter(1, 1, 5.0)
        val second = chapter(2, 2, 5.0)

        val rows = folded(listOf(row(first), row(second)), groups = listOf(group(showAll = true)))

        rows.map { it.chapterId } shouldContainExactly listOf(1L, 2L)
        rows.map { it.titleId } shouldContainExactly listOf(1L, 1L)
    }

    @Test
    fun `a chapter with no recognised number is always kept`() = runTest {
        val first = chapter(1, 1, -1.0)
        val second = chapter(2, 2, -1.0)

        folded(listOf(row(first), row(second))).map { it.chapterId } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `the filters run first, so a number whose winning copy fails one keeps the copy that passes`() = runTest {
        chapter(1, 1, 5.0)
        val second = chapter(2, 2, 5.0)

        folded(listOf(row(second)), copyShown = { it.id == 2L }).map { it.chapterId } shouldContainExactly listOf(2L)
    }

    @Test
    fun `a member the category filter leaves out wins no number`() = runTest {
        chapter(1, 1, 5.0)
        val second = chapter(2, 2, 5.0)

        folded(listOf(row(second)), memberShown = { it == 2L }).map { it.chapterId } shouldContainExactly listOf(2L)
    }

    @Test
    fun `a main entry a tick behind the group leaves its rows as they are`() = runTest {
        coEvery { getManga.await(1L) } returns null
        val first = chapter(1, 1, 5.0)
        val second = chapter(2, 2, 5.0)
        val rows = listOf(row(first), row(second))

        folded(rows) shouldBe rows
    }

    @Test
    fun `each title reads its copies once, however many rows it has`() = runTest {
        val rows = listOf(row(chapter(1, 1, 5.0)), row(chapter(2, 1, 6.0)), row(chapter(3, 2, 7.0)))

        folded(rows)

        coVerify(exactly = 1) { chapterRepository.getChaptersByIds(any()) }
        coVerify(exactly = 1) { chapterRepository.getChaptersByNumbers(listOf(1L, 2L), listOf(5.0, 6.0, 7.0), false) }
    }
}
