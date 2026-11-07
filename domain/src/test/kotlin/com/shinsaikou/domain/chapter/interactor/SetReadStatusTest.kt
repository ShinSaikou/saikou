package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.preferences.DownloadPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * What [SetReadStatus] reports it wrote (phase 3 unit T1): the chapter list's tracker push is the
 * highest number among those rows, so the answer has to be the rows written and never the argument.
 *
 * And what it hands the download cleanup (phase 3 unit DR): with "after manually marked as read" on,
 * the ids of the rows a mark read wrote, after the write, never the reader's duplicates, and never in
 * a way that can change the answer.
 */
class SetReadStatusTest {

    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>()
    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
    private val cleanup = RecordingCleanup()
    private val getMergedGroup = mockk<GetMergedGroup> { coEvery { await(any()) } returns null }
    private val setReadStatus = SetReadStatus(chapterRepository, mangaRepository, downloadPreferences, cleanup, getMergedGroup, mockk())

    private fun chapter(number: Long, read: Boolean = false) = Chapter.create().copy(
        id = number,
        mangaId = MANGA_ID,
        chapterNumber = number.toDouble(),
        sourceOrder = number,
        read = read,
    )

    private val group = MergedGroup(
        id = 1,
        mainMangaId = MANGA_ID,
        showAll = false,
        members = listOf(MANGA_ID, OTHER_MEMBER).map {
            MergedMember(mangaId = it, source = it, url = "/$it", position = it, checkUpdates = true)
        },
    )

    private fun copy(id: Long, number: Double, read: Boolean = false) =
        Chapter.create().copy(id = id, mangaId = OTHER_MEMBER, chapterNumber = number, read = read)

    private fun mergedWith(vararg copies: Chapter) {
        coEvery { getMergedGroup.await(any()) } returns group
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID) } returns emptyList()
        coEvery { chapterRepository.getChapterByMangaId(OTHER_MEMBER) } returns copies.toList()
    }

    @Test
    fun `in a merged entry a mark reaches the other copies of the number, and no others`() = runTest {
        mergedWith(copy(101, 1.0), copy(102, 2.0), copy(103, -1.0))
        val marked = chapter(1)

        val result = setReadStatus.await(read = true, marked)

        result shouldBe SetReadStatus.Result.Success(listOf(marked, copy(101, 1.0)))
    }

    @Test
    fun `marking unread reaches the copies too`() = runTest {
        mergedWith(copy(101, 1.0, read = true))
        val marked = chapter(1, read = true)

        val result = setReadStatus.await(read = false, marked)

        result shouldBe SetReadStatus.Result.Success(listOf(marked, copy(101, 1.0, read = true)))
    }

    @Test
    fun `a chapter with no recognised number has no copies`() = runTest {
        mergedWith(copy(101, -1.0))
        val marked = chapter(1).copy(chapterNumber = -1.0)

        setReadStatus.await(read = true, marked) shouldBe SetReadStatus.Result.Success(listOf(marked))
    }

    @Test
    fun `marking read reports only the chapters it wrote`() = runTest {
        val chapters = listOf(chapter(1), chapter(2, read = true), chapter(3))

        val result = setReadStatus.await(true, *chapters.toTypedArray())

        result shouldBe SetReadStatus.Result.Success(listOf(chapters[0], chapters[2]))
    }

    @Test
    fun `marking chapters already read reports that nothing was written`() = runTest {
        setReadStatus.await(true, chapter(1, read = true)) shouldBe SetReadStatus.Result.NoChapters
    }

    /**
     * Challenge 5 T1 item 1's scenario: read to 30, "mark previous as read" on 41, the last. The
     * answer is 31 to 40, whose highest is 40, and never 41.
     */
    @Test
    fun `mark previous as read on the last chapter reports the chapters before it that it wrote`() = runTest {
        val chapters = (1L..41L).map { chapter(it, read = it <= 30) }
        coEvery { mangaRepository.getMangaById(MANGA_ID) } returns
            Manga.create().copy(id = MANGA_ID, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns chapters.shuffled()

        val result = setReadStatus.awaitAllBefore(chapters.last(), read = true)

        val written = (result as SetReadStatus.Result.Success).chapters
        written.map { it.chapterNumber } shouldBe (31..40).map { it.toDouble() }
        written.maxOf { it.chapterNumber } shouldBe 40.0
    }

    // ---- After manually marked as read (phase 3 unit DR) ----

    @Test
    fun `with the switch on, a mark read hands the rows it wrote to the cleanup after the write`() = runTest {
        downloadPreferences.removeAfterMarkedAsRead.set(true)
        val chapters = listOf(chapter(1), chapter(2, read = true), chapter(3))

        setReadStatus.await(true, *chapters.toTypedArray())

        cleanup.marked shouldBe listOf(listOf(1L, 3L))
        coVerifyOrder {
            chapterRepository.updateAll(any())
            cleanup.probe.markedRead(listOf(1L, 3L))
        }
    }

    @Test
    fun `with the switch off, a mark read deletes nothing`() = runTest {
        setReadStatus.await(true, chapter(1))

        cleanup.marked shouldBe emptyList()
    }

    @Test
    fun `marking unread deletes nothing`() = runTest {
        downloadPreferences.removeAfterMarkedAsRead.set(true)

        setReadStatus.await(false, chapter(1, read = true))

        cleanup.marked shouldBe emptyList()
    }

    @Test
    fun `a caller that asks to keep the files keeps them with the switch on`() = runTest {
        downloadPreferences.removeAfterMarkedAsRead.set(true)

        val result = setReadStatus.await(true, chapter(1), removeDownloads = false)

        result shouldBe SetReadStatus.Result.Success(listOf(chapter(1)))
        cleanup.marked shouldBe emptyList()
    }

    @Test
    fun `a write that fails hands nothing to the cleanup`() = runTest {
        downloadPreferences.removeAfterMarkedAsRead.set(true)
        coEvery { chapterRepository.updateAll(any()) } throws IllegalStateException("fixture: the database refused")

        val result = setReadStatus.await(true, chapter(1))

        (result is SetReadStatus.Result.InternalError) shouldBe true
        cleanup.marked shouldBe emptyList()
    }

    /** Challenge S4: the tracker push reads the rows written, so a failing cleanup must not lose them. */
    @Test
    fun `a cleanup that throws changes neither the result nor the rows it carries`() = runTest {
        downloadPreferences.removeAfterMarkedAsRead.set(true)
        cleanup.failure = IllegalStateException("fixture: the cleanup failed")
        val chapters = listOf(chapter(1), chapter(2))

        val result = setReadStatus.await(true, *chapters.toTypedArray())

        result shouldBe SetReadStatus.Result.Success(chapters)
        verify(exactly = 1) { cleanup.probe.markedRead(listOf(1L, 2L)) }
    }

    @Test
    fun `mark previous as read and a whole entry marked read hand their rows over too`() = runTest {
        downloadPreferences.removeAfterMarkedAsRead.set(true)
        val chapters = (1L..4L).map { chapter(it, read = it == 2L) }
        coEvery { mangaRepository.getMangaById(MANGA_ID) } returns
            Manga.create().copy(id = MANGA_ID, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns chapters

        setReadStatus.awaitAllBefore(chapters[3], read = true)
        setReadStatus.await(MANGA_ID, read = true)

        cleanup.marked shouldBe listOf(listOf(1L, 3L), listOf(1L, 3L, 4L))
    }

    /**
     * Records each hand-over, then throws [failure] when one is set; the relaxed [probe] sees the
     * same calls, so an order check can place them after the repository's write.
     */
    private class RecordingCleanup : ReadChapterCleanup {
        val marked = mutableListOf<List<Long>>()
        var failure: Exception? = null
        val probe = mockk<ReadChapterCleanup>(relaxed = true)

        override suspend fun chapterFinished(sessionChapterIds: List<Long>) = Unit

        override suspend fun readerClosed() = Unit

        override fun markedRead(chapterIds: List<Long>) {
            probe.markedRead(chapterIds)
            failure?.let { throw it }
            marked += chapterIds
        }
    }

    private companion object {
        const val MANGA_ID = 5L
        const val OTHER_MEMBER = 6L
    }
}
