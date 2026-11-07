package com.shinsaikou.reader

import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
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
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/** The reader's entries (R163): a title is one entry unless it is merged, then every live member. */
class AppReaderEntriesTest {

    private val getMergedGroup = mockk<GetMergedGroup>()
    private val getManga = mockk<GetManga>()
    private val installed = mockk<Source>()
    private val sourceManager = mockk<SourceManager>().also { every { it.get(any()) } returns installed }
    private val entries = AppReaderEntries(getMergedGroup, GetMergedChapters(mockk(), sourceManager), getManga)

    private fun manga(id: Long) = Manga.create().copy(id = id, source = id * 10, title = "Entry $id")

    private fun group(main: Long, vararg ids: Long, showAll: Boolean = false) = MergedGroup(
        id = 1,
        mainMangaId = main,
        showAll = showAll,
        members = ids.mapIndexed { index, id ->
            MergedMember(id, source = id * 10, url = "/$id", position = index.toLong(), checkUpdates = true)
        },
    )

    private fun chapter(id: Long, mangaId: Long, number: Double) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number)

    init {
        listOf(1L, 2L, 3L).forEach { coEvery { getManga.await(it) } returns manga(it) }
    }

    @Test
    fun `an entry in no group is the only entry, and nothing else is read`() = runTest {
        coEvery { getMergedGroup.await(1) } returns null

        entries.of(manga(1)) shouldContainExactly listOf(manga(1))

        coVerify(exactly = 0) { getManga.await(any()) }
    }

    @Test
    fun `a merged title lists the main entry first and the rest in chapter order`() = runTest {
        coEvery { getMergedGroup.await(any()) } returns group(main = 2, 1, 2, 3)

        entries.of(manga(1)).map { it.id } shouldContainExactly listOf(2L, 1L, 3L)
    }

    @Test
    fun `any member resolves the same entries, and the one in hand is not read again`() = runTest {
        coEvery { getMergedGroup.await(any()) } returns group(main = 1, 1, 2, 3)

        listOf(1L, 2L, 3L).forEach { opened ->
            entries.of(manga(opened)).map { it.id } shouldContainExactly listOf(1L, 2L, 3L)
        }
    }

    @Test
    fun `the entry the reader was opened on is the instance it passed in`() = runTest {
        coEvery { getMergedGroup.await(any()) } returns group(main = 1, 1, 2)
        val inHand = manga(2).copy(title = "Edited in hand")

        entries.of(inHand).single { it.id == 2L } shouldBe inHand
        coVerify(exactly = 0) { getManga.await(2) }
    }

    @Test
    fun `a member whose row cannot be read is left out`() = runTest {
        coEvery { getMergedGroup.await(any()) } returns group(main = 1, 1, 2, 3)
        coEvery { getManga.await(2) } returns null

        entries.of(manga(1)).map { it.id } shouldContainExactly listOf(1L, 3L)
    }

    @Test
    fun `the chapter pass of an entry in no group keeps every chapter`() = runTest {
        coEvery { getMergedGroup.await(1) } returns null
        val chapters = listOf(chapter(1, 1, 1.0), chapter(2, 1, 1.0))

        entries.chapterPass(listOf(manga(1)))(chapters) shouldBe chapters
    }

    @Test
    fun `the chapter pass of a merged title keeps one copy of each number, from the first source`() = runTest {
        coEvery { getMergedGroup.await(1) } returns group(main = 1, 1, 2)
        val chapters = listOf(chapter(1, 1, 1.0), chapter(2, 2, 1.0), chapter(3, 2, 2.0))

        entries.chapterPass(listOf(manga(1), manga(2)))(chapters).map { it.id } shouldContainExactly listOf(1L, 3L)
    }

    @Test
    fun `the chapter pass of a merged title with show every copy keeps every chapter`() = runTest {
        coEvery { getMergedGroup.await(1) } returns group(main = 1, 1, 2, showAll = true)
        val chapters = listOf(chapter(1, 1, 1.0), chapter(2, 2, 1.0))

        entries.chapterPass(listOf(manga(1), manga(2)))(chapters).map { it.id } shouldContainExactly listOf(1L, 2L)
    }
}
