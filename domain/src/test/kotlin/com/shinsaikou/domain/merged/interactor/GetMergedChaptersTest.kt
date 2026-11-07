package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

/** A group's chapters as one list, with the sources installed at the moment of asking. */
class GetMergedChaptersTest {

    private val chapterRepository = mockk<ChapterRepository>()
    private val sourceManager = mockk<SourceManager>()
    private val getMergedChapters = GetMergedChapters(chapterRepository, sourceManager)

    private val group = MergedGroup(
        id = 1,
        mainMangaId = 2,
        showAll = false,
        members = listOf(1L, 2L).map {
            MergedMember(mangaId = it, source = it * 10, url = "/$it", position = it, checkUpdates = true)
        },
    )

    private fun chapter(id: Long, mangaId: Long, number: Double) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number)

    private fun install(vararg sources: Long) {
        every { sourceManager.get(any()) } answers { null }
        sources.forEach { every { sourceManager.get(it) } returns mockk<Source>() }
    }

    init {
        coEvery { chapterRepository.getChapterByMangaId(1, any()) } returns listOf(chapter(11, 1, 1.0), chapter(12, 1, 2.0))
        coEvery { chapterRepository.getChapterByMangaId(2, any()) } returns listOf(chapter(21, 2, 2.0), chapter(22, 2, 3.0))
    }

    @Test
    fun `the union keeps one copy of each number, from the first member`() = runTest {
        install(10, 20)

        getMergedChapters.await(group).map { it.id } shouldContainExactlyInAnyOrder listOf(11L, 12L, 22L)
    }

    @Test
    fun `a source that is not installed ranks last`() = runTest {
        install(20)

        getMergedChapters.await(group).map { it.id } shouldContainExactlyInAnyOrder listOf(11L, 21L, 22L)
    }

    @Test
    fun `a stub source counts as not installed`() = runTest {
        every { sourceManager.get(10) } returns StubSource.unknown(10)
        every { sourceManager.get(20) } returns mockk<Source>()

        getMergedChapters.isInstalled(10) shouldBe false
        getMergedChapters.isInstalled(20) shouldBe true
        getMergedChapters.await(group).map { it.id } shouldContainExactlyInAnyOrder listOf(11L, 21L, 22L)
    }

    @Test
    fun `a source nobody serves is not installed`() {
        install()

        getMergedChapters.isInstalled(10) shouldBe false
    }

    @Test
    fun `the scanlator filter choice reaches every member's read`() = runTest {
        install(10, 20)

        getMergedChapters.await(group, applyScanlatorFilter = false)

        coVerify(exactly = 1) { chapterRepository.getChapterByMangaId(1, false) }
        coVerify(exactly = 1) { chapterRepository.getChapterByMangaId(2, false) }
    }

    @Test
    fun `show every copy returns every chapter of every member`() = runTest {
        install(10, 20)

        getMergedChapters.await(group.copy(showAll = true)).map { it.id } shouldContainExactlyInAnyOrder
            listOf(11L, 12L, 21L, 22L)
    }
}
