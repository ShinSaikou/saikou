package com.shinsaikou.ui.download

import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.downloads.DownloadStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The queue screen reads the queue table and the worker's running flag, names each row from the
 * chapter and entry tables, and hands its four controls straight to [DownloadQueue]. Every name here
 * is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)

    private val rows = MutableStateFlow<List<DownloadQueueEntry>>(emptyList())
    private val status = MutableStateFlow(DownloadStatus.IDLE)
    private val downloadQueue = mockk<DownloadQueue>(relaxed = true) {
        every { subscribe() } returns rows
        every { status } returns this@DownloadQueueViewModelTest.status
    }
    private val chapterRepository = mockk<ChapterRepository>()
    private val getManga = mockk<GetManga>()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { chapterRepository.getChaptersByIds(any()) } answers {
            firstArg<List<Long>>().filter { it in CHAPTERS }.map { CHAPTERS.getValue(it) }
        }
        coEvery { getManga.await(MANGA_ID) } returns
            Manga.create().copy(id = MANGA_ID, title = "Fixture Manga")
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = DownloadQueueViewModel(downloadQueue, chapterRepository, getManga, appDispatchers)

    @Test
    fun `rows keep the queue's order and carry names, state and page progress`() = runTest(testDispatcher) {
        rows.value = listOf(
            entry(101L, DownloadState.DOWNLOADING, pages = 4, total = 20),
            entry(100L, DownloadState.QUEUED),
            entry(102L, DownloadState.ERROR, error = "timeout"),
        )
        status.value = DownloadStatus.RUNNING

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        state.loading shouldBe false
        state.running shouldBe true
        state.rows shouldContainExactly listOf(
            row(101L, "Chapter 2", DownloadState.DOWNLOADING, pages = 4, total = 20),
            row(100L, "Chapter 1", DownloadState.QUEUED),
            row(102L, "Chapter 3", DownloadState.ERROR, error = "timeout"),
        )
    }

    /** The engine rewrites a row per page; the names are looked up once, not once per page. */
    @Test
    fun `a page written updates the progress without another lookup`() = runTest(testDispatcher) {
        rows.value = listOf(entry(101L, DownloadState.DOWNLOADING, pages = 1, total = 20))
        val viewModel = viewModel()
        advanceUntilIdle()

        rows.value = listOf(entry(101L, DownloadState.DOWNLOADING, pages = 2, total = 20))
        advanceUntilIdle()

        viewModel.state.value.rows.single().downloadedPages shouldBe 2
        coVerify(exactly = 1) { chapterRepository.getChaptersByIds(any()) }
        coVerify(exactly = 1) { getManga.await(MANGA_ID) }
    }

    /**
     * A queue row goes with its chapter (ON DELETE CASCADE), but the lookup can still race a delete,
     * and a name that cannot be found must not drop a row the owner may want to cancel.
     */
    @Test
    fun `a row whose chapter is no longer stored is still listed, without names`() = runTest(testDispatcher) {
        rows.value = listOf(entry(999L, DownloadState.QUEUED))

        val viewModel = viewModel()
        advanceUntilIdle()

        val row = viewModel.state.value.rows.single()
        row.chapterId shouldBe 999L
        row.mangaTitle shouldBe null
        row.chapterName shouldBe null
    }

    @Test
    fun `the running flag follows the worker`() = runTest(testDispatcher) {
        rows.value = listOf(entry(100L, DownloadState.QUEUED))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.running shouldBe false

        status.value = DownloadStatus.RUNNING
        advanceUntilIdle()
        viewModel.state.value.running shouldBe true
    }

    /** A request waiting for Wi-Fi is not running, but it is started: the screen offers Pause, not Start. */
    @Test
    fun `a request waiting for its network is reported as waiting and started`() = runTest(testDispatcher) {
        rows.value = listOf(entry(100L, DownloadState.QUEUED))
        status.value = DownloadStatus.WAITING_FOR_WIFI

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        state.status shouldBe DownloadStatus.WAITING_FOR_WIFI
        state.running shouldBe false
        state.started shouldBe true
    }

    @Test
    fun `an empty queue settles as empty rather than loading`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.state.value.loading shouldBe true
        advanceUntilIdle()

        viewModel.state.value.loading shouldBe false
        viewModel.state.value.rows shouldBe emptyList()
    }

    @Test
    fun `start, pause, cancel one and clear each reach the queue`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.start()
        viewModel.pause()
        viewModel.cancel(101L)
        viewModel.clear()
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.start() }
        coVerify(exactly = 1) { downloadQueue.pause() }
        coVerify(exactly = 1) { downloadQueue.remove(listOf(101L)) }
        coVerify(exactly = 1) { downloadQueue.clear() }
        coVerify(exactly = 0) { downloadQueue.reorder(any()) }
    }

    private fun entry(
        chapterId: Long,
        state: DownloadState,
        pages: Long = 0,
        total: Long = 0,
        error: String? = null,
    ) = DownloadQueueEntry(
        id = chapterId,
        chapterId = chapterId,
        mangaId = MANGA_ID,
        state = state,
        queueOrder = 0,
        totalPages = total,
        downloadedPages = pages,
        errorMessage = error,
        dateAdded = 0,
        dateStarted = null,
        dateCompleted = null,
    )

    private fun row(
        chapterId: Long,
        name: String,
        state: DownloadState,
        pages: Long = 0,
        total: Long = 0,
        error: String? = null,
    ) = DownloadQueueViewModel.Row(
        chapterId = chapterId,
        mangaTitle = "Fixture Manga",
        chapterName = name,
        state = state,
        downloadedPages = pages,
        totalPages = total,
        errorMessage = error,
    )

    private companion object {
        const val MANGA_ID = 1L

        val CHAPTERS: Map<Long, Chapter> = listOf(100L to "Chapter 1", 101L to "Chapter 2", 102L to "Chapter 3")
            .associate { (id, name) -> id to Chapter.create().copy(id = id, mangaId = MANGA_ID, name = name) }
    }
}
