package com.shinsaikou.reader.state

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.track.TrackProgress
import com.shinsaikou.reader.pipeline.DownloadedPageLoader
import com.shinsaikou.reader.pipeline.LocalPageLoader
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressCommitterTest {

    private val chapterRepository = mockk<ChapterRepository>(relaxed = true)
    private val historyRepository = mockk<HistoryRepository>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val trackProgress = RecordingTrackProgress()
    private val autoDownload = RecordingAutoDownload()
    private val cleanup = RecordingReadChapterCleanup()
    private var now = 1_000L

    // ---- The debounce ----

    @Test
    fun `a burst of page turns costs one write`() = runTest {
        val chapter = loadedChapter(1)
        val committer = committer()

        repeat(10) { committer.onPageSelected(chapter, it, chapterFinished = false, immediate = false) }
        advanceUntilIdle()

        coVerify(exactly = 1) { chapterRepository.update(any()) }
        coVerify { chapterRepository.update(ChapterUpdate(id = 1, lastPageRead = 9)) }
    }

    @Test
    fun `nothing is written before the debounce elapses`() = runTest {
        val committer = committer()
        committer.onPageSelected(loadedChapter(1), 1, chapterFinished = false, immediate = false)

        advanceTimeBy(ProgressCommitter.DEBOUNCE_MS - 1)
        coVerify(exactly = 0) { chapterRepository.update(any()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { chapterRepository.update(any()) }
    }

    @Test
    fun `an immediate commit does not wait`() = runTest {
        val committer = committer()
        committer.onPageSelected(loadedChapter(1), 2, chapterFinished = false, immediate = true)
        advanceTimeBy(1)
        coVerify(exactly = 1) { chapterRepository.update(ChapterUpdate(id = 1, lastPageRead = 2)) }
    }

    @Test
    fun `the in-memory page index moves before any write`() = runTest {
        val chapter = loadedChapter(1)
        committer().onPageSelected(chapter, 5, chapterFinished = false, immediate = false)
        chapter.chapter.lastPageRead shouldBe 5L
        chapter.requestedPage shouldBe 5
    }

    @Test
    fun `progress may move backwards`() = runTest {
        val chapter = loadedChapter(1)
        val committer = committer()
        committer.onPageSelected(chapter, 5, chapterFinished = false, immediate = true)
        committer.onPageSelected(chapter, 2, chapterFinished = false, immediate = true)
        advanceUntilIdle()
        coVerify { chapterRepository.update(ChapterUpdate(id = 1, lastPageRead = 2)) }
    }

    // ---- Flush ----

    @Test
    fun `flush writes the pending index now`() = runTest {
        val chapter = loadedChapter(1)
        val committer = committer()
        committer.onPageSelected(chapter, 4, chapterFinished = false, immediate = false)

        committer.flush(chapter)

        coVerify(exactly = 1) { chapterRepository.update(ChapterUpdate(id = 1, lastPageRead = 4)) }
        // The scheduled write finds nothing left to do.
        advanceUntilIdle()
        coVerify(exactly = 1) { chapterRepository.update(any()) }
    }

    @Test
    fun `flush with nothing pending is safe`() = runTest {
        committer().flush(null)
        committer().flush(loadedChapter(1))
        coVerify(exactly = 0) { chapterRepository.update(any()) }
    }

    @Test
    fun `flushing another chapter leaves the pending write alone`() = runTest {
        val committer = committer()
        committer.onPageSelected(loadedChapter(1), 4, chapterFinished = false, immediate = false)

        committer.flush(loadedChapter(2))

        coVerify(exactly = 0) { chapterRepository.update(any()) }
    }

    // ---- The last page ----

    @Test
    fun `the last page marks the chapter read`() = runTest {
        val chapter = loadedChapter(1)
        committer().onPageSelected(chapter, 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        coVerify { chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2)) }
        chapter.chapter.read shouldBe true
    }

    @Test
    fun `duplicate unread chapters are marked read in one call when the preference asks`() = runTest {
        val chapter = loadedChapter(1)
        val duplicates = listOf(
            chapter(id = 20, number = 1.0),
            chapter(id = 21, number = 1.0, read = true),
            chapter(id = 22, number = 2.0),
        )
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns duplicates

        committer(markDuplicates = true).onPageSelected(chapter, 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        val updates = slot<List<ChapterUpdate>>()
        coVerify(exactly = 1) { chapterRepository.updateAll(capture(updates)) }
        updates.captured.map { it.id } shouldBe listOf(20L)
    }

    @Test
    fun `duplicates are left alone when the preference is off`() = runTest {
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns
            listOf(chapter(id = 20, number = 1.0))

        committer().onPageSelected(loadedChapter(1), 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
    }

    @Test
    fun `a chapter with no recognised number has no duplicates to mark`() = runTest {
        val unrecognised = eu.kanade.tachiyomi.ui.reader.model.ReaderChapter(Chapter.create().copy(id = 1, mangaId = MANGA_ID))
        committer(markDuplicates = true).onPageSelected(unrecognised, 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()
        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
    }

    // ---- The tracker push (phase 3 unit T1) ----

    @Test
    fun `a finished chapter records its tracker push after the read write`() = runTest {
        val chapter = eu.kanade.tachiyomi.ui.reader.model.ReaderChapter(chapter(id = 1, number = 12.5))
        committer().onPageSelected(chapter, 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        trackProgress.recorded shouldBe listOf(MANGA_ID to 12.5)
        coVerifyOrder {
            chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2))
            trackProgress.probe.record(MANGA_ID, 12.5)
        }
    }

    @Test
    fun `a page turn that does not finish the chapter records no push`() = runTest {
        committer().onPageSelected(loadedChapter(1), 1, chapterFinished = false, immediate = true)
        advanceUntilIdle()

        trackProgress.recorded shouldBe emptyList()
    }

    @Test
    fun `with autoUpdateTrack off a finished chapter records no push`() = runTest {
        val committer = committer(autoUpdateTrack = false)
        committer.onPageSelected(loadedChapter(1), 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        coVerify { chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2)) }
        trackProgress.recorded shouldBe emptyList()
    }

    @Test
    fun `a push that cannot be recorded fails neither the read write nor the duplicates`() = runTest {
        trackProgress.failure = java.io.IOException("fixture: the preference file refused the commit")
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns
            listOf(chapter(id = 20, number = 1.0))
        val chapter = loadedChapter(1)

        committer(markDuplicates = true).onPageSelected(chapter, 2, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        coVerify { chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2)) }
        chapter.chapter.read shouldBe true
        coVerify(exactly = 1) { chapterRepository.updateAll(any()) }
        trackProgress.recorded shouldBe listOf(MANGA_ID to 1.0)
    }

    // ---- Download ahead (phase 3 unit AD) ----

    @Test
    fun `every page of a downloaded chapter is reported, with its count and the next chapter`() = runTest {
        val chapter = loadedChapter(1, pageCount = 8).apply {
            pageLoader = DownloadedPageLoader(mockk<LocalPageLoader>(relaxed = true))
        }
        val committer = committer()

        repeat(3) { committer.onPageSelected(chapter, it, chapterFinished = false, immediate = false, nextChapterId = 2) }
        advanceUntilIdle()

        autoDownload.reported shouldBe List(3) { AheadReport(MANGA_ID, it, 8, 2L) }
        // The write is still debounced to one row; only the report runs per page.
        coVerify(exactly = 1) { chapterRepository.update(any()) }
    }

    @Test
    fun `a chapter read online is reported as a downloaded one is, and the last chapter with no next`() = runTest {
        val committer = committer()
        val online = loadedChapter(1, pageCount = 4)
        committer.onPageSelected(online, 2, chapterFinished = false, immediate = true, nextChapterId = 2)
        committer.onPageSelected(loadedChapter(2, pageCount = 4), 3, chapterFinished = true, immediate = true)
        advanceUntilIdle()

        // Download ahead runs for a chapter read online too, so the loader is not part of the report.
        autoDownload.reported shouldBe listOf(AheadReport(MANGA_ID, 2, 4, 2L), AheadReport(MANGA_ID, 3, 4, null))
    }

    @Test
    fun `a chapter with no pages loaded is not reported`() = runTest {
        committer().onPageSelected(waitingChapter(1), 0, chapterFinished = false, immediate = true)
        advanceUntilIdle()

        autoDownload.reported shouldBe emptyList()
    }

    @Test
    fun `a report that fails leaves the progress write standing`() = runTest {
        autoDownload.failure = java.io.IOException("fixture: the queue table refused the insert")
        val chapter = loadedChapter(1)

        committer().onPageSelected(chapter, 2, chapterFinished = true, immediate = true, nextChapterId = 2)
        advanceUntilIdle()

        autoDownload.reported.size shouldBe 1
        coVerify { chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2)) }
        chapter.chapter.read shouldBe true
    }

    // ---- Delete after reading (phase 3 unit DR) ----

    @Test
    fun `a finished chapter hands the session's chapters to the cleanup after the read write`() = runTest {
        val chapter = loadedChapter(1)
        committer().onPageSelected(chapter, 2, chapterFinished = true, immediate = true, readBack = listOf(1L, 0L))
        advanceUntilIdle()

        cleanup.events shouldBe listOf("finished [1, 0]")
        coVerifyOrder {
            chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2))
            cleanup.probe.chapterFinished(listOf(1L, 0L))
        }
    }

    @Test
    fun `a page that does not finish the chapter stages nothing`() = runTest {
        committer().onPageSelected(loadedChapter(1), 1, chapterFinished = false, immediate = true, readBack = listOf(1L))
        advanceUntilIdle()

        cleanup.events shouldBe emptyList()
    }

    @Test
    fun `a stage that fails leaves the read write and the duplicates standing`() = runTest {
        cleanup.failure = java.io.IOException("fixture: the preference file refused the commit")
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns listOf(chapter(id = 20, number = 1.0))
        val chapter = loadedChapter(1)

        committer(markDuplicates = true).onPageSelected(chapter, 2, chapterFinished = true, immediate = true, readBack = listOf(1L))
        advanceUntilIdle()

        coVerify { chapterRepository.update(ChapterUpdate(id = 1, read = true, lastPageRead = 2)) }
        chapter.chapter.read shouldBe true
        coVerify(exactly = 1) { chapterRepository.updateAll(any()) }
    }

    /**
     * Upstream marks a finished chapter's duplicates with `updateChapter.awaitAll` and never deletes
     * them; one may be the chapter the reader shows next. So with every key set to delete, the
     * duplicate is marked read and never reaches the cleanup.
     */
    @Test
    fun `the duplicates the reader marks read keep their files with the marked read switch on`() = runTest {
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns listOf(chapter(id = 20, number = 1.0))

        committer(markDuplicates = true, removeAfterMarkedAsRead = true)
            .onPageSelected(loadedChapter(1), 2, chapterFinished = true, immediate = true, readBack = listOf(1L))
        advanceUntilIdle()

        val updates = slot<List<ChapterUpdate>>()
        coVerify(exactly = 1) { chapterRepository.updateAll(capture(updates)) }
        updates.captured.map { it.id } shouldBe listOf(20L)
        cleanup.events shouldBe listOf("finished [1]")
    }

    /**
     * The finish's flush is what stages the chapter just finished, so the delete that follows it has
     * to wait for it. The stage here takes a while, and the delete, asked for right after the flush,
     * still runs second.
     */
    @Test
    fun `deleting the staged chapters waits for the flush the finish asked for first`() = runTest {
        cleanup.stageDelayMillis = 500
        val chapter = loadedChapter(1)
        val committer = committer()
        committer.onPageSelected(chapter, 2, chapterFinished = true, immediate = false, readBack = listOf(1L))

        launch { committer.flush(chapter) }
        launch { committer.deletePendingChapters() }
        advanceUntilIdle()

        cleanup.events shouldBe listOf("finished [1]", "closed")
    }

    @Test
    fun `deleting the staged chapters waits for a last page write already under way`() = runTest {
        cleanup.stageDelayMillis = 500
        val committer = committer()
        committer.onPageSelected(loadedChapter(1), 2, chapterFinished = true, immediate = true, readBack = listOf(1L))
        advanceTimeBy(1)

        launch { committer.deletePendingChapters() }
        advanceUntilIdle()

        cleanup.events shouldBe listOf("finished [1]", "closed")
    }

    /** The view model's scope is cancelled as the reader closes; a delete it started still finishes. */
    @Test
    fun `deleting the staged chapters outlives the scope that started it`() = runTest {
        cleanup.closeDelayMillis = 500
        val committer = committer()
        val viewModelScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))

        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { committer.deletePendingChapters() }
        viewModelScope.cancel()
        advanceUntilIdle()

        cleanup.events shouldBe listOf("closed")
    }

    @Test
    fun `a delete that fails is logged and does not throw`() = runTest {
        cleanup.failure = java.io.IOException("fixture: the manifest refused the delete")

        committer().deletePendingChapters()

        cleanup.events shouldBe listOf("closed")
    }

    // ---- History ----

    @Test
    fun `the history row carries the time since the timer restarted`() = runTest {
        val chapter = loadedChapter(1)
        val committer = committer()
        committer.restartReadTimer(chapter)
        now += 5_000

        committer.commitHistory(chapter)

        val update = slot<HistoryUpdate>()
        coVerify { historyRepository.upsertHistory(capture(update)) }
        update.captured.chapterId shouldBe 1L
        update.captured.sessionReadDuration shouldBe 5_000L
        update.captured.readAt.time shouldBe now
    }

    @Test
    fun `a history row with no timer still writes, with a zero duration`() = runTest {
        committer().commitHistory(loadedChapter(1))
        val update = slot<HistoryUpdate>()
        coVerify { historyRepository.upsertHistory(capture(update)) }
        update.captured.sessionReadDuration shouldBe 0L
    }

    @Test
    fun `the timer does not carry over between two history rows`() = runTest {
        val chapter = loadedChapter(1)
        val committer = committer()
        committer.restartReadTimer(chapter)
        now += 3_000
        committer.commitHistory(chapter)
        now += 4_000
        committer.commitHistory(chapter)

        val updates = mutableListOf<HistoryUpdate>()
        coVerify { historyRepository.upsertHistory(capture(updates)) }
        updates.map { it.sessionReadDuration } shouldBe listOf(3_000L, 0L)
    }

    @Test
    fun `a history row whose chapter left with its entry is logged and does not throw`() = runTest {
        // What the JVM driver throws for the insert once Clear database has removed the chapter.
        coEvery { historyRepository.upsertHistory(any()) } throws java.sql.SQLException(
            "[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (FOREIGN KEY constraint failed)",
        )
        val chapter = loadedChapter(1)
        val committer = committer()
        committer.restartReadTimer(chapter)

        committer.commitHistory(chapter)

        coVerify(exactly = 1) { historyRepository.upsertHistory(any()) }
        // The timer was reset all the same, so the next chapter's row does not carry this one's time.
        now += 2_000
        committer.commitHistory(chapter)
        val updates = mutableListOf<HistoryUpdate>()
        coVerify { historyRepository.upsertHistory(capture(updates)) }
        updates.last().sessionReadDuration shouldBe 0L
    }

    private fun TestScope.committer(
        markDuplicates: Boolean = false,
        autoUpdateTrack: Boolean = true,
        removeAfterMarkedAsRead: Boolean = false,
    ): ProgressCommitter {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryPreferenceStore(
            if (markDuplicates) {
                sequenceOf(
                    InMemoryPreferenceStore.InMemoryPreference(
                        MARK_DUPLICATE_KEY,
                        setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING),
                        emptySet(),
                    ),
                )
            } else {
                sequenceOf()
            },
        )
        val trackStore = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference(AUTO_UPDATE_TRACK_KEY, autoUpdateTrack, true)),
        )
        val downloadStore = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference(REMOVE_AFTER_MARKED_KEY, removeAfterMarkedAsRead, false)),
        )
        return ProgressCommitter(
            scope = this,
            updateChapter = UpdateChapter(chapterRepository),
            upsertHistory = UpsertHistory(historyRepository),
            setReadStatus = SetReadStatus(chapterRepository, mangaRepository, DownloadPreferences(downloadStore), cleanup),
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            libraryPreferences = LibraryPreferences(store),
            trackPreferences = TrackPreferences(trackStore),
            trackProgress = trackProgress,
            autoDownload = autoDownload,
            readChapterCleanup = cleanup,
            dispatchers = Dispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
            clock = { now },
        )
    }

    /**
     * Records each push it is asked for, and throws [failure] after recording when one is set. The
     * relaxed [probe] sees the same calls, so an order check can place them among the repository's.
     */
    private class RecordingTrackProgress : TrackProgress {
        val recorded = mutableListOf<Pair<Long, Double>>()
        var failure: Exception? = null
        val probe = mockk<TrackProgress>(relaxed = true)

        override suspend fun wouldRecord(mangaId: Long, chapterNumber: Double): Boolean = true

        override suspend fun record(mangaId: Long, chapterNumber: Double): Int {
            recorded += mangaId to chapterNumber
            probe.record(mangaId, chapterNumber)
            failure?.let { throw it }
            return 1
        }
    }

    private data class AheadReport(
        val mangaId: Long,
        val pageIndex: Int,
        val pageCount: Int,
        val nextChapterId: Long?,
    )

    /** Records each download-ahead report, and throws [failure] after recording when one is set. */
    private class RecordingAutoDownload : AutoDownload {
        val reported = mutableListOf<AheadReport>()
        var failure: Exception? = null

        override suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Int = 0

        override suspend fun queuePulledChapters(chapterIds: List<Long>): Int = 0

        override suspend fun startQueue() = Unit

        override suspend fun downloadAhead(
            mangaId: Long,
            pageIndex: Int,
            pageCount: Int,
            nextChapterId: Long?,
        ): Int {
            reported += AheadReport(mangaId, pageIndex, pageCount, nextChapterId)
            failure?.let { throw it }
            return 0
        }
    }

    /**
     * Records each call in order as a line of [events], after [stageDelayMillis] or
     * [closeDelayMillis], and throws [failure] after recording when one is set. The relaxed [probe]
     * sees the stage calls, so an order check can place them among the repository's.
     */
    private class RecordingReadChapterCleanup : ReadChapterCleanup {
        val events = mutableListOf<String>()
        var failure: Exception? = null
        var stageDelayMillis = 0L
        var closeDelayMillis = 0L
        val probe = mockk<ReadChapterCleanup>(relaxed = true)

        override suspend fun chapterFinished(sessionChapterIds: List<Long>) {
            delay(stageDelayMillis)
            events += "finished $sessionChapterIds"
            probe.chapterFinished(sessionChapterIds)
            failure?.let { throw it }
        }

        override suspend fun readerClosed() {
            delay(closeDelayMillis)
            events += "closed"
            failure?.let { throw it }
        }

        override fun markedRead(chapterIds: List<Long>) {
            events += "marked $chapterIds"
        }
    }

    private companion object {
        const val REMOVE_AFTER_MARKED_KEY = "pref_remove_after_marked_as_read_key"
        const val MARK_DUPLICATE_KEY = "mark_duplicate_read_chapter_read"
        const val AUTO_UPDATE_TRACK_KEY = "pref_auto_update_manga_sync_key"
    }
}
