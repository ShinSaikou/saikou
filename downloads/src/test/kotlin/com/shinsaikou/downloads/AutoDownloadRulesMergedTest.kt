package com.shinsaikou.downloads

import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetManga

/**
 * Automatic downloads for a merged entry (R163): a new chapter is queued only while it is the copy
 * the group shows, and a read ahead follows the merged list, each chapter queued under its owner.
 *
 * Entry 1 is the main entry and ranks first, entry 2 second; both are favourites. Entry 1 holds
 * chapters 1 and 2 (numbers 1 and 2), entry 2 holds chapters 11 (number 1), 12 (number 2) and 13
 * and 14 (numbers 3 and 4). Chapters 11 and 12 are the copies the group hides.
 */
class AutoDownloadRulesMergedTest : DownloadTestBase() {

    private val scheduler = mockk<DownloadScheduler>(relaxed = true) {
        every { isRunning } returns flowOf(false)
    }
    private val readerPreferences = ReaderPreferences(preferenceStore)

    private val group = MergedGroup(
        id = 1,
        mainMangaId = 1,
        showAll = false,
        members = listOf(1L, 2L).map { MergedMember(it, source = SOURCE_ID, url = "/manga/$it", position = it, checkUpdates = true) },
    )

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertManga(id = 2)
        insertChapter(id = 1, mangaId = 1, number = 1.0)
        insertChapter(id = 2, mangaId = 1, number = 2.0)
        insertChapter(id = 11, mangaId = 2, number = 1.0)
        insertChapter(id = 12, mangaId = 2, number = 2.0)
        insertChapter(id = 13, mangaId = 2, number = 3.0)
        insertChapter(id = 14, mangaId = 2, number = 4.0)
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

    private fun TestScope.rules(merged: MergedGroup? = group): AutoDownloadRules {
        val getChaptersByMangaId = GetChaptersByMangaId(chapterRepository)
        val getManga = GetManga(mangaRepository)
        val getMergedGroup = mockk<GetMergedGroup> { coEvery { await(any()) } returns merged }
        val installed = mockk<Source>()
        val sources = mockk<SourceManager>()
        every { sources.get(any()) } returns installed
        val getMergedChapters = GetMergedChapters(chapterRepository, sources)
        return AutoDownloadRules(
            filterChaptersForDownload = FilterChaptersForDownload(
                getChaptersByMangaId,
                downloadPreferences,
                GetCategories(categoryRepository),
            ),
            getNextChapters = GetNextChapters(getChaptersByMangaId, getManga, mockk(), getMergedGroup, getMergedChapters),
            getScanlatorPriority = GetScanlatorPriority(ScanlatorPriorityRepositoryImpl(handler)),
            getManga = getManga,
            chapterRepository = chapterRepository,
            manifest = manifest(StandardTestDispatcher(testScheduler)),
            queue = queue(),
            downloadPreferences = downloadPreferences,
            readerPreferences = readerPreferences,
            getMergedGroup = getMergedGroup,
            getMergedChapters = getMergedChapters,
        )
    }

    private suspend fun queuedRows() = queueRepository.subscribeAll().first().map { it.chapterId to it.mangaId }

    @Test
    fun `a new chapter of a lower source that the group hides is not queued`() = runTest {
        val count = rules().queueNewChapters(manga(2), listOf(chapter(11), chapter(12)), autoStart = false)

        count shouldBe 0
        queuedRows().shouldBeEmpty()
    }

    @Test
    fun `of the new chapters of a lower source only the ones the group shows are queued`() = runTest {
        val count = rules().queueNewChapters(manga(2), listOf(chapter(11), chapter(13), chapter(14)), autoStart = false)

        count shouldBe 2
        queuedRows() shouldContainExactly listOf(13L to 2L, 14L to 2L)
    }

    @Test
    fun `a new chapter of the main entry is queued`() = runTest {
        rules().queueNewChapters(manga(1), listOf(chapter(1)), autoStart = false) shouldBe 1

        queuedRows() shouldContainExactly listOf(1L to 1L)
    }

    @Test
    fun `with show every copy on the hidden copy is queued too`() = runTest {
        rules(group.copy(showAll = true)).queueNewChapters(manga(2), listOf(chapter(11)), autoStart = false) shouldBe 1

        queuedRows() shouldContainExactly listOf(11L to 2L)
    }

    @Test
    fun `an entry in no group queues every new chapter as before`() = runTest {
        rules(merged = null).queueNewChapters(manga(2), listOf(chapter(11), chapter(12)), autoStart = false) shouldBe 2

        queuedRows().map { it.first } shouldContainExactly listOf(11L, 12L)
    }

    @Test
    fun `a download ahead crosses into the second source and queues each chapter under its owner`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)

        // Chapter 1 is read in entry 1; the next one is chapter 2, then the second source's 3 and 4.
        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 3

        queuedRows() shouldContainExactly listOf(2L to 1L, 13L to 2L, 14L to 2L)
    }

    @Test
    fun `a download ahead from a member reads the same merged list`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(2)

        rules().downloadAhead(2, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 2

        queuedRows() shouldContainExactly listOf(2L to 1L, 13L to 2L)
    }

    @Test
    fun `a download ahead skips the copies the group hides`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(4)

        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 1) shouldBe 4

        queuedRows().map { it.first } shouldContainExactly listOf(1L, 2L, 13L, 14L)
        coVerify(atLeast = 1) { scheduler.start() }
    }

    @Test
    fun `a download ahead owned by a Local source entry queues nothing for it`() = runTest {
        downloadPreferences.autoDownloadWhileReading.set(3)
        exec("UPDATE mangas SET source = ${SourceIds.LOCAL} WHERE _id = 2")

        rules().downloadAhead(1, pageIndex = 7, pageCount = 8, nextChapterId = 2) shouldBe 1

        queuedRows() shouldContainExactly listOf(2L to 1L)
    }
}
