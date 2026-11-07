// Fresh: written for Saikou. GetNextChapters over a merged title (R163).
package tachiyomi.domain.history.interactor

import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/** Continue reading and the reader's next chapters read a merged entry's list, in its main entry's order. */
class GetNextChaptersMergedTest {

    private val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
    private val getManga = mockk<GetManga>()
    private val getMergedGroup = mockk<GetMergedGroup>()
    private val getMergedChapters = mockk<GetMergedChapters>()
    private val next = GetNextChapters(getChaptersByMangaId, getManga, mockk<HistoryRepository>(), getMergedGroup, getMergedChapters)

    private val group = MergedGroup(
        id = 1,
        mainMangaId = 10,
        showAll = false,
        members = listOf(10L, 20L).map { MergedMember(it, source = it, url = "/$it", position = it, checkUpdates = true) },
    )

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean = false, sourceOrder: Long = 0) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number, read = read, sourceOrder = sourceOrder)

    private val mainManga = Manga.create().copy(id = 10, chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
    private val memberManga = Manga.create().copy(id = 20, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)

    init {
        coEvery { getManga.await(10) } returns mainManga
        coEvery { getManga.await(20) } returns memberManga
        coEvery { getManga.await(30) } returns Manga.create().copy(id = 30, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        coEvery { getMergedGroup.await(any()) } returns null
    }

    private fun merged(vararg chapters: Chapter) {
        coEvery { getMergedGroup.await(10) } returns group
        coEvery { getMergedGroup.await(20) } returns group
        coEvery { getMergedChapters.await(group) } returns chapters.toList()
    }

    @Test
    fun `from any member the unread chapters of the union come in number order`() = runTest {
        merged(chapter(3, 20, 3.0), chapter(1, 10, 1.0, read = true), chapter(2, 10, 2.0), chapter(4, 20, 4.0))

        next.await(20, onlyUnread = true).map { it.id } shouldContainExactly listOf(2L, 3L, 4L)
        next.await(10, onlyUnread = true).map { it.id } shouldContainExactly listOf(2L, 3L, 4L)
    }

    @Test
    fun `the main entry's order is used, not the asked member's`() = runTest {
        // The main sorts by source order, which means number across sources; the member sorts by number.
        merged(chapter(1, 10, 2.0, sourceOrder = 0), chapter(2, 20, 1.0, sourceOrder = 7))

        next.await(20, onlyUnread = true).map { it.id } shouldContainExactly listOf(2L, 1L)
    }

    @Test
    fun `starting from a chapter of the second source skips what comes before it in the union`() = runTest {
        merged(chapter(1, 10, 1.0), chapter(2, 10, 2.0), chapter(3, 20, 3.0), chapter(4, 20, 4.0))

        next.await(10, fromChapterId = 3, onlyUnread = true).map { it.id } shouldContainExactly listOf(3L, 4L)
    }

    @Test
    fun `with all chapters, a read starting chapter is dropped and an unread one is kept`() = runTest {
        merged(chapter(1, 10, 1.0, read = true), chapter(2, 20, 2.0, read = true), chapter(3, 20, 3.0))

        next.await(10, fromChapterId = 2, onlyUnread = false).map { it.id } shouldContainExactly listOf(3L)
        next.await(10, fromChapterId = 3, onlyUnread = false).map { it.id } shouldContainExactly listOf(3L)
    }

    @Test
    fun `everything read leaves nothing to continue with`() = runTest {
        merged(chapter(1, 10, 1.0, read = true), chapter(2, 20, 2.0, read = true))

        next.await(10, onlyUnread = true).shouldBeEmpty()
    }

    @Test
    fun `an entry in no group reads only its own chapters`() = runTest {
        coEvery { getChaptersByMangaId.await(30, applyScanlatorFilter = true) } returns
            listOf(chapter(6, 30, 2.0), chapter(5, 30, 1.0))

        next.await(30, onlyUnread = true).map { it.id } shouldContainExactly listOf(5L, 6L)
        coVerify(exactly = 0) { getMergedChapters.await(any(), any()) }
    }

    @Test
    fun `a missing entry has no next chapters`() = runTest {
        coEvery { getManga.await(99) } returns null

        next.await(99, onlyUnread = true).shouldBeEmpty()
    }
}
