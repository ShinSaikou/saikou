package com.shinsaikou.data.repository

import app.cash.turbine.test
import com.shinsaikou.data.download.DownloadQueueRepositoryImpl
import com.shinsaikou.domain.download.model.DownloadState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DownloadQueueRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { DownloadQueueRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        (10L..13L).forEach { insertChapter(id = it, mangaId = 1) }
    }

    @Test
    fun `enqueue appends at the tail of the queue order`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 11, mangaId = 1, now = 101)
        repository.enqueue(chapterId = 12, mangaId = 1, now = 102)

        repository.getByMangaId(1).map { it.chapterId to it.queueOrder } shouldContainExactly
            listOf(10L to 1L, 11L to 2L, 12L to 3L)
    }

    @Test
    fun `enqueueing a chapter that is not in the queue adds it queued at the tail`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)

        repository.enqueue(chapterId = 11, mangaId = 1, now = 200)

        val entry = repository.getByChapterId(11).shouldNotBeNull()
        entry.state shouldBe DownloadState.QUEUED
        entry.queueOrder shouldBe 2
        entry.dateAdded shouldBe 200
    }

    @Test
    fun `enqueueing a chapter already queued leaves it where it is`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 11, mangaId = 1, now = 100)

        repository.enqueue(chapterId = 10, mangaId = 1, now = 300)

        repository.getByMangaId(1).map { Triple(it.chapterId, it.state, it.queueOrder) } shouldContainExactly
            listOf(Triple(10L, DownloadState.QUEUED, 1L), Triple(11L, DownloadState.QUEUED, 2L))
        repository.getByChapterId(10).shouldNotBeNull().dateAdded shouldBe 100
    }

    /** The engine is still writing its pages, so its row must go on saying so. */
    @Test
    fun `enqueueing a chapter being downloaded leaves its state, progress and position alone`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 11, mangaId = 1, now = 100)
        repository.setState(chapterId = 10, state = DownloadState.DOWNLOADING, errorMessage = null, now = 200)
        repository.setProgress(chapterId = 10, downloadedPages = 4, totalPages = 20)

        repository.enqueue(chapterId = 10, mangaId = 1, now = 300)

        val entry = repository.getByChapterId(10).shouldNotBeNull()
        entry.state shouldBe DownloadState.DOWNLOADING
        entry.downloadedPages shouldBe 4
        entry.totalPages shouldBe 20
        entry.queueOrder shouldBe 1
        entry.dateStarted shouldBe 200
    }

    /** Download from the chapter list is a retry for a failed chapter, as Start on the queue is. */
    @Test
    fun `enqueueing a failed chapter queues it again in its place and clears the error`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 11, mangaId = 1, now = 100)
        repository.setState(chapterId = 10, state = DownloadState.ERROR, errorMessage = "boom", now = 200)

        repository.enqueue(chapterId = 10, mangaId = 1, now = 300)

        val entry = repository.getByChapterId(10).shouldNotBeNull()
        entry.state shouldBe DownloadState.QUEUED
        entry.errorMessage shouldBe null
        entry.queueOrder shouldBe 1
        repository.getByMangaId(1).size shouldBe 2
    }

    @Test
    fun `setState stamps date_started once and date_completed on done`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)

        repository.setState(chapterId = 10, state = DownloadState.DOWNLOADING, errorMessage = null, now = 200)
        repository.setState(chapterId = 10, state = DownloadState.PAUSED, errorMessage = null, now = 300)
        repository.setState(chapterId = 10, state = DownloadState.DOWNLOADING, errorMessage = null, now = 400)
        repository.setState(chapterId = 10, state = DownloadState.DONE, errorMessage = null, now = 500)

        val entry = repository.getByChapterId(10).shouldNotBeNull()
        entry.state shouldBe DownloadState.DONE
        entry.dateStarted shouldBe 200
        entry.dateCompleted shouldBe 500
    }

    @Test
    fun `getActive skips done and errored rows`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 11, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 12, mangaId = 1, now = 100)
        repository.setState(chapterId = 11, state = DownloadState.DONE, errorMessage = null, now = 200)
        repository.setState(chapterId = 12, state = DownloadState.ERROR, errorMessage = "boom", now = 200)

        repository.getActive().map { it.chapterId } shouldContainExactly listOf(10L)
    }

    @Test
    fun `setProgress writes both page counts`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)

        repository.setProgress(chapterId = 10, downloadedPages = 4, totalPages = 20)

        val entry = repository.getByChapterId(10).shouldNotBeNull()
        entry.downloadedPages shouldBe 4
        entry.totalPages shouldBe 20
    }

    @Test
    fun `reorder renumbers the queue from zero in list order`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 11, mangaId = 1, now = 100)
        repository.enqueue(chapterId = 12, mangaId = 1, now = 100)

        repository.reorder(listOf(12, 10, 11))

        repository.getByMangaId(1).map { it.chapterId } shouldContainExactly listOf(12L, 10L, 11L)
    }

    @Test
    fun `subscribeAll emits in queue order and re-emits on removal`() = runTest {
        repository.subscribeAll().test {
            awaitItem() shouldBe emptyList()

            repository.enqueue(chapterId = 10, mangaId = 1, now = 100)
            awaitItem().map { it.chapterId } shouldContainExactly listOf(10L)

            repository.remove(listOf(10))
            awaitItem() shouldBe emptyList()
        }
    }

    @Test
    fun `deleting the chapter cascades the queue row away`() = runTest {
        repository.enqueue(chapterId = 10, mangaId = 1, now = 100)

        exec("DELETE FROM chapters WHERE _id = 10")

        repository.getByChapterId(10) shouldBe null
    }
}
