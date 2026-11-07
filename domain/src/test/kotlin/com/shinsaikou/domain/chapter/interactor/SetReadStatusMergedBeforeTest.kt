package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.preferences.DownloadPreferences
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/** "Mark previous as read" in a merged entry walks the merged list in the main entry's order. */
class SetReadStatusMergedBeforeTest {

    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>()
    private val getMergedGroup = mockk<GetMergedGroup>()
    private val getMergedChapters = mockk<GetMergedChapters>()
    private val setReadStatus = SetReadStatus(
        chapterRepository,
        mangaRepository,
        DownloadPreferences(InMemoryPreferenceStore()),
        mockk<ReadChapterCleanup>(relaxed = true),
        getMergedGroup,
        getMergedChapters,
    )

    private val group = MergedGroup(
        id = 1,
        mainMangaId = 10,
        showAll = false,
        members = listOf(10L, 20L).map { MergedMember(it, source = it, url = "/$it", position = it, checkUpdates = true) },
    )

    private fun chapter(id: Long, mangaId: Long, number: Double, sourceOrder: Long = 0) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number, sourceOrder = sourceOrder)

    init {
        coEvery { mangaRepository.getMangaById(10) } returns
            Manga.create().copy(id = 10, chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
        coEvery { getMergedGroup.await(any()) } returns null
    }

    private fun merged(shown: List<Chapter>, hiddenCopies: List<Chapter> = emptyList()) {
        coEvery { getMergedGroup.await(10) } returns group
        coEvery { getMergedGroup.await(20) } returns group
        coEvery { getMergedChapters.await(group) } returns shown
        coEvery { chapterRepository.getChapterByMangaId(10) } returns shown.filter { it.mangaId == 10L }
        coEvery { chapterRepository.getChapterByMangaId(20) } returns
            shown.filter { it.mangaId == 20L } + hiddenCopies
    }

    @Test
    fun `a chapter of the second source marks the earlier numbers of both sources`() = runTest {
        val chapters = listOf(
            chapter(1, 10, 1.0, sourceOrder = 5),
            chapter(2, 10, 2.0, sourceOrder = 4),
            chapter(3, 20, 3.0, sourceOrder = 0),
            chapter(4, 20, 4.0, sourceOrder = 1),
        )
        merged(chapters)

        val result = setReadStatus.awaitAllBefore(chapters[2], read = true)

        (result as SetReadStatus.Result.Success).chapters.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `the list is walked by number even though source order means nothing across sources`() = runTest {
        // Source order would put the second source's chapter 3 before the first source's chapter 2.
        val first = chapter(1, 10, 1.0, sourceOrder = 9)
        val second = chapter(2, 10, 2.0, sourceOrder = 9)
        val third = chapter(3, 20, 3.0, sourceOrder = 0)
        merged(listOf(second, third, first))

        val result = setReadStatus.awaitAllBefore(third, read = true)

        (result as SetReadStatus.Result.Success).chapters.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `a hidden copy of a number marked before is marked too`() = runTest {
        val one = chapter(1, 10, 1.0)
        val two = chapter(2, 10, 2.0)
        val hiddenOne = chapter(31, 20, 1.0)
        merged(listOf(one, two), hiddenCopies = listOf(hiddenOne))

        val result = setReadStatus.awaitAllBefore(two, read = true)

        (result as SetReadStatus.Result.Success).chapters.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 31L)
    }

    @Test
    fun `the first chapter of the merged list has nothing before it`() = runTest {
        val chapters = listOf(chapter(1, 20, 1.0), chapter(2, 10, 2.0))
        merged(chapters)

        setReadStatus.awaitAllBefore(chapters[0], read = true) shouldBe SetReadStatus.Result.NoChapters
    }

    @Test
    fun `a chapter the merged list hides has nothing before it`() = runTest {
        merged(listOf(chapter(1, 10, 1.0), chapter(2, 10, 2.0)))

        setReadStatus.awaitAllBefore(chapter(99, 20, 2.0), read = true) shouldBe SetReadStatus.Result.NoChapters
    }

    @Test
    fun `an entry in no group reads only its own chapters`() = runTest {
        val chapters = listOf(chapter(1, 30, 1.0, sourceOrder = 1), chapter(2, 30, 2.0, sourceOrder = 0))
        coEvery { mangaRepository.getMangaById(30) } returns Manga.create().copy(id = 30, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        coEvery { chapterRepository.getChapterByMangaId(30, any()) } returns chapters

        val result = setReadStatus.awaitAllBefore(chapters[1], read = true)

        (result as SetReadStatus.Result.Success).chapters.map { it.id } shouldBe listOf(1L)
        coVerify(exactly = 0) { getMergedChapters.await(any(), any()) }
    }
}
