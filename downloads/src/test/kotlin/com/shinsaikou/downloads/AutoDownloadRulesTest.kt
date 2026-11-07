package com.shinsaikou.downloads

import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.source.SourceIds
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetManga

/**
 * Upstream's two automatic downloads (phase 3 unit AD) over the engine's fixture: the real
 * repositories on the JVM driver, the real queue over its table, and a scheduler that only counts
 * its starts. What a rule queued is read back from the queue table.
 *
 * Entry 1 is a favourite in no category with chapters 1 to 6, numbered as their ids, source order
 * falling as the number rises (the newest first, as a source lists them).
 */
class AutoDownloadRulesTest : DownloadTestBase() {

    private val scheduler = mockk<DownloadScheduler>(relaxed = true) {
        every { isRunning } returns flowOf(false)
    }
    private val readerPreferences = ReaderPreferences(preferenceStore)

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        (1L..6L).forEach { insertChapter(id = it, mangaId = 1) }
        exec("UPDATE chapters SET source_order = 100 - _id")
        downloadPreferences.downloadNewChapters.set(true)
    }

    private fun TestScope.queue(): WorkManagerDownloadQueue {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return WorkManagerDownloadQueue(
            queueRepository = queueRepository,
            manifest = manifest(dispatcher),
            chapterRepository = chapterRepository,
            getManga = GetManga(mangaRepository),
            locator = locator(dispatcher),
            downloadRoot = downloadRoot,
            engine = engine(dispatcher),
            scheduler = scheduler,
            dispatchers = dispatchers(dispatcher),
            appScope = backgroundScope,
        )
    }

    private fun TestScope.rules(): AutoDownloadRules {
        val getChaptersByMangaId = GetChaptersByMangaId(chapterRepository)
        val getManga = GetManga(mangaRepository)
        return AutoDownloadRules(
            filterChaptersForDownload = FilterChaptersForDownload(
                getChaptersByMangaId,
                downloadPreferences,
                GetCategories(categoryRepository),
            ),
            getNextChapters = GetNextChapters(getChaptersByMangaId, getManga, mockk(), mockk { coEvery { await(any()) } returns null }, mockk()),
            getScanlatorPriority = GetScanlatorPriority(ScanlatorPriorityRepositoryImpl(handler)),
            getManga = getManga,
            chapterRepository = chapterRepository,
            manifest = manifest(StandardTestDispatcher(testScheduler)),
            queue = queue(),
            downloadPreferences = downloadPreferences,
            readerPreferences = readerPreferences,
            getMergedGroup = mockk { coEvery { await(any()) } returns null },
            getMergedChapters = mockk(),
        )
    }

    /** Every queue row in queue order, failed ones included. */
    private suspend fun queued(): List<Long> = queueRepository.subscribeAll().first().map { it.chapterId }

    private suspend fun markDownloaded(chapterId: Long) {
        val chapter = chapter(chapterId)
        realManifestRepository.recordDownload(manifestEntry(chapter, manga(chapter.mangaId), chapterDirName(chapterId)))
        finalDir(chapterId).mkdirs()
    }

    private fun category(id: Long) = exec("INSERT INTO categories(_id, name, sort, flags) VALUES ($id, 'Category $id', $id, 0)")

    private fun inCategory(mangaId: Long, categoryId: Long) =
        exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES ($mangaId, $categoryId)")

    // region new chapters: FilterChaptersForDownload

    @Test
    fun `new chapters of a favourite are queued oldest first and the queue is not started`() = runTest {
        val queuedCount = rules().queueNewChapters(manga(1), listOf(chapter(5), chapter(6), chapter(4)), autoStart = false)

        queuedCount shouldBe 3
        queued() shouldContainExactly listOf(4L, 5L, 6L)
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `the manga screen's manual refresh starts the queue`() = runTest {
        rules().queueNewChapters(manga(1), listOf(chapter(6)), autoStart = true)

        queued() shouldContainExactly listOf(6L)
        coVerify(exactly = 1) { scheduler.start() }
    }

    @Test
    fun `nothing is queued while download new chapters is off`() = runTest {
        downloadPreferences.downloadNewChapters.set(false)

        rules().queueNewChapters(manga(1), listOf(chapter(6)), autoStart = true) shouldBe 0

        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `an entry that is not a favourite is never queued`() = runTest {
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")

        rules().queueNewChapters(manga(1), listOf(chapter(6)), autoStart = false) shouldBe 0

        queued().shouldBeEmpty()
    }

    @Test
    fun `an entry in no category counts as the default category`() = runTest {
        category(5)
        downloadPreferences.downloadNewChapterCategories.set(setOf("5"))
        rules().queueNewChapters(manga(1), listOf(chapter(6)), autoStart = false) shouldBe 0

        downloadPreferences.downloadNewChapterCategories.set(setOf("0"))
        rules().queueNewChapters(manga(1), listOf(chapter(6)), autoStart = false) shouldBe 1

        queued() shouldContainExactly listOf(6L)
    }

    @Test
    fun `an excluded category wins over an included one`() = runTest {
        category(5)
        category(6)
        inCategory(1, 5)
        inCategory(1, 6)
        downloadPreferences.downloadNewChapterCategories.set(setOf("5"))
        downloadPreferences.downloadNewChapterCategoriesExclude.set(setOf("6"))
        rules().queueNewChapters(manga(1), listOf(chapter(6)), autoStart = false) shouldBe 0

        // With only an exclusion, every category that is not excluded downloads.
        downloadPreferences.downloadNewChapterCategories.set(emptySet())
        downloadPreferences.downloadNewChapterCategoriesExclude.set(setOf("7"))
        rules().queueNewChapters(manga(1), listOf(chapter(6)), autoStart = false) shouldBe 1
    }

    @Test
    fun `skipping duplicate read chapters drops a new chapter whose number a read one has`() = runTest {
        insertChapter(id = 7, mangaId = 1, url = "/ch/7-other", number = 2.0)
        exec("UPDATE chapters SET read = 1 WHERE _id = 2")
        downloadPreferences.downloadNewUnreadChaptersOnly.set(true)

        rules().queueNewChapters(manga(1), listOf(chapter(7), chapter(6)), autoStart = false) shouldBe 1

        queued() shouldContainExactly listOf(6L)
    }

    @Test
    fun `a Local-source entry is never queued`() = runTest {
        insertManga(id = 2, sourceId = SourceIds.LOCAL)
        insertChapter(id = 20, mangaId = 2)

        rules().queueNewChapters(manga(2), listOf(chapter(20)), autoStart = true) shouldBe 0

        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `pulled rows go through the same rule and the queue starts once`() = runTest {
        insertManga(id = 2)
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 2")
        insertChapter(id = 20, mangaId = 2)
        insertManga(id = 3)
        insertChapter(id = 30, mangaId = 3)

        rules().queuePulledChapters(listOf(6, 20, 30, 5)) shouldBe 3

        queued().toSet() shouldBe setOf(5L, 6L, 30L)
        coVerify(exactly = 1) { scheduler.start() }
    }

    @Test
    fun `a pulled row that arrived read is not queued, with skip duplicate read chapters off`() = runTest {
        exec("UPDATE chapters SET read = 1 WHERE _id = 6")
        downloadPreferences.downloadNewUnreadChaptersOnly.get() shouldBe false

        rules().queuePulledChapters(listOf(6)) shouldBe 0
        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }

        rules().queuePulledChapters(listOf(5, 6)) shouldBe 1
        queued() shouldContainExactly listOf(5L)
        coVerify(exactly = 1) { scheduler.start() }
    }

    @Test
    fun `pulled rows the rule declines leave the queue unstarted`() = runTest {
        downloadPreferences.downloadNewChapters.set(false)

        rules().queuePulledChapters(listOf(5, 6)) shouldBe 0
        rules().queuePulledChapters(emptyList()) shouldBe 0

        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }
    }

    // endregion

    // region download ahead: ReaderViewModel.downloadNextChapters, without its downloaded-only guard

    @Test
    fun `a favourite read online queues the next unread chapters from the next one and starts the queue`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)

        // Nothing is downloaded: chapter 1 is read online, and 2, 3 and 4 are queued.
        rules().downloadAhead(1, pageIndex = 2, pageCount = 8, nextChapterId = 2) shouldBe 3

        queued() shouldContainExactly listOf(2L, 3L, 4L)
        coVerify(exactly = 1) { scheduler.start() }
    }

    @Test
    fun `an entry that is not in the library downloads nothing ahead`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")

        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 0

        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `a Local-source entry downloads nothing ahead`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        insertManga(id = 2, sourceId = SourceIds.LOCAL)
        (20L..22L).forEach { insertChapter(id = it, mangaId = 2) }

        rules().downloadAhead(2, pageIndex = 7, pageCount = 8, nextChapterId = 21) shouldBe 0

        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `a next chapter that is not downloaded is queued with the ones after it`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(2)
        markDownloaded(1)

        // Upstream stops here, the next chapter not being on disk; Saikou queues it and the one after.
        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 2

        queued() shouldContainExactly listOf(2L, 3L)
    }

    @Test
    fun `a page at a quarter of the chapter or before it queues nothing`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        val rules = rules()

        // Pages are counted from 1 in upstream's rule: the second of eight is exactly a quarter.
        rules.downloadAhead(1, pageIndex = 0, pageCount = 8, nextChapterId = 2) shouldBe 0
        rules.downloadAhead(1, pageIndex = 1, pageCount = 8, nextChapterId = 2) shouldBe 0
        queued().shouldBeEmpty()

        rules.downloadAhead(1, pageIndex = 2, pageCount = 8, nextChapterId = 2) shouldBe 3
    }

    @Test
    fun `download ahead switched off queues nothing`() = runTest {
        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 0

        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `the last chapter, with no next one, queues nothing`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)

        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = null) shouldBe 0

        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `downloaded chapters count towards the amount and are left out`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        markDownloaded(2)
        markDownloaded(4)

        // 2, 3 and 4 are the three; 2 and 4 are on disk, so only 3 is queued.
        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 1

        queued() shouldContainExactly listOf(3L)
        coVerify(exactly = 1) { scheduler.start() }
    }

    @Test
    fun `with the next chapters all downloaded nothing is queued or started`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(2)
        markDownloaded(2)
        markDownloaded(3)

        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 0

        queued().shouldBeEmpty()
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `read chapters are passed over and duplicates are one per number under skip duplicates`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        readerPreferences.skipDupe.set(true)
        markDownloaded(2)
        exec("UPDATE chapters SET read = 1 WHERE _id = 3")
        exec("UPDATE chapters SET scanlator = 'Group A' WHERE _id IN (2, 4)")
        insertChapter(id = 8, mangaId = 1, url = "/ch/8-other", scanlator = "Group B", number = 4.0)

        // Unread from 2 on: 2, then 4 in the next chapter's group (8 is its duplicate), then 5.
        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 2

        queued() shouldContainExactly listOf(4L, 5L)
    }

    @Test
    fun `under skip duplicates a ranked title's download ahead takes the ranked group's copy`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        readerPreferences.skipDupe.set(true)
        markDownloaded(2)
        exec("UPDATE chapters SET read = 1 WHERE _id = 3")
        exec("UPDATE chapters SET scanlator = 'Group A' WHERE _id IN (2, 4)")
        insertChapter(id = 8, mangaId = 1, url = "/ch/8-other", scanlator = "Group B", number = 4.0)
        ScanlatorPriorityRepositoryImpl(handler).setPriorities(mangaId = 1, orderedScanlators = listOf("Group B"))

        // Unread from 2 on: 2, then 8, Group B's copy of 4, over the next chapter's group, then 5.
        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 2

        queued() shouldContainExactly listOf(5L, 8L)
    }

    @Test
    fun `a queued chapter is left as it is and a queue that holds rows is not started`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        markDownloaded(2)
        queueRepository.enqueue(chapterId = 3, mangaId = 1, now = 0)
        queueRepository.setState(3, DownloadState.ERROR, "fixture", 0)
        val rules = rules()

        rules.downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 1

        queueRepository.getByChapterId(3)?.state shouldBe DownloadState.ERROR
        queued().toSet() shouldBe setOf(3L, 4L)
        coVerify(exactly = 0) { scheduler.start() }

        // Every page past the quarter asks again; with nothing new it enqueues and starts nothing.
        rules.downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 0
        coVerify(exactly = 0) { scheduler.start() }
    }

    @Test
    fun `reading online, the pages after the first report queue nothing more and start the queue no more`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(2)
        val rules = rules()

        rules.downloadAhead(1, pageIndex = 2, pageCount = 8, nextChapterId = 2) shouldBe 2
        rules.downloadAhead(1, pageIndex = 3, pageCount = 8, nextChapterId = 2) shouldBe 0
        // A larger amount set mid-chapter adds the one more, to a queue that holds rows: no start.
        downloadPreferences.autoDownloadWhileReading.set(3)
        rules.downloadAhead(1, pageIndex = 4, pageCount = 8, nextChapterId = 2) shouldBe 1

        queued() shouldContainExactly listOf(2L, 3L, 4L)
        coVerify(exactly = 1) { scheduler.start() }
    }

    // endregion
}
