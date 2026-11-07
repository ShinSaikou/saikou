package com.shinsaikou.reader.state

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.ChapterReplacements
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.track.TrackProgress
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Finishing a chapter writes `read` without [SetReadStatus], so in a merged entry the other copies
 * of the number are raised by [ReconcileMergedRead] right after (R163). A page turn raises nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressCommitterMergedTest {

    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val reconcile = mockk<ReconcileMergedRead>(relaxed = true)

    init {
        coEvery { chapterRepository.getChapterById(any()) } answers { chapter(firstArg(), mangaId = OWNER) }
    }

    private fun loaded(id: Long): ReaderChapter {
        val readerChapter = ReaderChapter(chapter(id, mangaId = OWNER))
        readerChapter.state = ReaderChapter.State.Loaded(
            List(3) { index -> ReaderPage(index).apply { this.chapter = readerChapter } },
        )
        return readerChapter
    }

    @Test
    fun `finishing a chapter reconciles the group of the entry that owns it, after the write`() = runTest {
        committer().onPageSelected(loaded(1), 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        coVerifyOrder {
            chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2))
            reconcile.await(OWNER)
        }
    }

    @Test
    fun `a page turn that finishes nothing reconciles nothing`() = runTest {
        committer().onPageSelected(loaded(1), 1, chapterFinished = false, immediate = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { reconcile.await(any<Long>()) }
    }

    @Test
    fun `a failing reconcile is swallowed and the chapter stays read`() = runTest {
        coEvery { reconcile.await(any<Long>()) } throws IllegalStateException("db busy")
        val committer = committer()

        committer.onPageSelected(loaded(1), 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2)) }
        // The committer still takes the next chapter's progress.
        committer.onPageSelected(loaded(2), 1, chapterFinished = false, immediate = true)
        advanceUntilIdle()
        coVerify(exactly = 1) { chapterRepository.update(ChapterUpdate(id = 2, lastPageRead = 1)) }
    }

    private fun TestScope.committer(): ProgressCommitter {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryPreferenceStore()
        val cleanup = mockk<ReadChapterCleanup>(relaxed = true)
        return ProgressCommitter(
            scope = this,
            updateChapter = UpdateChapter(chapterRepository),
            upsertHistory = UpsertHistory(mockk(relaxed = true)),
            setReadStatus = SetReadStatus(
                chapterRepository, mockk<MangaRepository>(relaxed = true), DownloadPreferences(store), cleanup,
                GetMergedGroup(mockk(), LibraryPreferences(store)),
                mockk(),
            ),
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            libraryPreferences = LibraryPreferences(store),
            trackPreferences = TrackPreferences(store),
            trackProgress = mockk<TrackProgress>(relaxed = true),
            autoDownload = mockk<AutoDownload>(relaxed = true),
            readChapterCleanup = cleanup,
            reconcileMergedRead = reconcile,
            chapterReplacements = ChapterReplacements(chapterRepository, ImmediateTransactionRunner),
            dispatchers = Dispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
            clock = { 1_000L },
        )
    }

    private companion object {
        const val OWNER = 8L
    }
}
