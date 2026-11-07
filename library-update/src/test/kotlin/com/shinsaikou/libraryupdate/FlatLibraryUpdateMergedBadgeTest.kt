package com.shinsaikou.libraryupdate

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * What a run adds to the Updates badge (`newUpdatesCount`) for a merged title (R163): the rows the
 * new chapters add to the list. Entries 1 and 2 are the title's members and share one source, so
 * they run one after the other in queue order (source, then display title: member 2's title sorts
 * first, so it runs first); entry 3 is on its own. The sync is faked to store
 * what it reports, so the second member to run finds the first member's chapters in the database.
 */
class FlatLibraryUpdateMergedBadgeTest {

    private val getLibraryManga = mockk<GetLibraryManga>()
    private val getManga = mockk<GetManga>()
    private val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
    private val syncChapters = mockk<SyncChaptersWithSource>()
    private val fetchInterval = mockk<FetchInterval>()
    private val sourceManager = mockk<SourceManager>()
    private val reconcileMergedRead = mockk<ReconcileMergedRead>(relaxed = true)
    private val autoDownload = mockk<AutoDownload>(relaxed = true)
    private val preferences = LibraryPreferences(InMemoryPreferenceStore())

    private var library: List<LibraryManga> = emptyList()
    private var groups: List<MergedGroup> = emptyList()
    private val stored = mutableMapOf<Long, List<Chapter>>()
    private val reported = mutableMapOf<Long, List<Chapter>>()
    private var nextChapterId = 100L

    @BeforeEach
    fun setUp() {
        val source = Source100
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
        every { sourceManager.getOrStub(any()) } returns source
        every { sourceManager.get(any()) } returns source
        every { fetchInterval.getWindow(any(), any()) } returns (0L to 1_000L)
        coEvery { getManga.await(any()) } answers { library.firstOrNull { it.id == firstArg<Long>() }?.manga }
        coEvery { getLibraryManga.await() } answers { library }
        coEvery { getChaptersByMangaId.await(any(), any()) } answers { stored[firstArg<Long>()].orEmpty() }
        coEvery { syncChapters.await(any(), any(), any(), any()) } answers {
            val id = secondArg<Manga>().id
            val added = reported[id].orEmpty()
            stored[id] = stored[id].orEmpty() + added
            added
        }
    }

    private fun TestScope.pass() = StandardTestDispatcher(testScheduler).let { io ->
        FlatLibraryUpdate(
            getLibraryManga = getLibraryManga,
            getManga = getManga,
            getChaptersByMangaId = getChaptersByMangaId,
            sourceManager = sourceManager,
            syncChaptersWithSource = syncChapters,
            updateManga = mockk<UpdateManga>(relaxed = true),
            fetchInterval = fetchInterval,
            libraryPreferences = preferences,
            getMergedGroups = mockk { coEvery { await() } answers { groups } },
            reconcileMergedRead = reconcileMergedRead,
            autoDownload = autoDownload,
            dispatchers = Dispatchers(io = io, default = io, main = io),
            timeSource = testScheduler.timeSource,
        )
    }

    private suspend fun TestScope.run() = pass().run(categoryId = null, automatic = true) {}

    private fun title(showAll: Boolean = false) = MergedGroup(
        id = 1,
        mainMangaId = 1,
        showAll = showAll,
        members = listOf(1L, 2L).mapIndexed { index, id ->
            MergedMember(id, source = 100L, url = "/manga/$id", position = index.toLong(), checkUpdates = true)
        },
    )

    private fun entry(id: Long) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            source = 100L,
            url = "/manga/$id",
            title = if (id == 2L) "Aardvark 2" else "Fixture $id",
            favorite = true,
            status = MangaStatus.ONGOING.code.toLong(),
            initialized = true,
        ),
        categories = listOf(1L),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    /** Entries in the library, each reporting [numbers] as new chapters. */
    private fun arrive(vararg arrivals: Pair<Long, List<Double>>) {
        library = arrivals.map { entry(it.first) }
        arrivals.forEach { (id, numbers) ->
            reported[id] = numbers.map { Chapter.create().copy(id = nextChapterId++, mangaId = id, chapterNumber = it) }
        }
    }

    private fun badge() = preferences.newUpdatesCount.get()

    @Test
    fun `a single entry adds a row for each new chapter`() = runTest {
        arrive(3L to listOf(1.0, 2.0, 3.0))

        run()

        badge() shouldBe 3
    }

    @Test
    fun `a merged title adds one row for a number both members bring, member 1 running first`() = runTest {
        groups = listOf(title())
        arrive(1L to listOf(5.0), 2L to listOf(5.0))

        run()

        badge() shouldBe 1
    }

    @Test
    fun `a merged title adds one row for a number both members bring, member 2 running first`() = runTest {
        groups = listOf(title())
        arrive(2L to listOf(5.0), 1L to listOf(5.0))

        run()

        badge() shouldBe 1
    }

    @Test
    fun `a failed read of the other members chapters counts every new chapter and does not fail the entry`() = runTest {
        groups = listOf(title())
        arrive(1L to listOf(5.0, 6.0))
        coEvery { getChaptersByMangaId.await(2L, any()) } throws IllegalStateException("database read failed")

        val outcome = run()

        badge() shouldBe 2
        outcome.failures shouldBe emptyList()
        outcome.updated.keys shouldBe setOf(1L)
        coVerify(exactly = 1) { reconcileMergedRead.await(1L) }
        coVerify(exactly = 1) { autoDownload.queueNewChapters(any(), match { it.size == 2 }, any()) }
    }

    @Test
    fun `a number another member already holds adds nothing`() = runTest {
        groups = listOf(title())
        stored[2L] = listOf(Chapter.create().copy(id = 1, mangaId = 2L, chapterNumber = 7.0))
        arrive(1L to listOf(7.0))

        run()

        badge() shouldBe 0
    }

    @Test
    fun `a number no other member holds adds a row beside one it does`() = runTest {
        groups = listOf(title())
        stored[2L] = listOf(Chapter.create().copy(id = 1, mangaId = 2L, chapterNumber = 7.0))
        arrive(1L to listOf(7.0, 8.0))

        run()

        badge() shouldBe 1
    }

    @Test
    fun `chapters with no recognised number each add a row, whatever the other member holds`() = runTest {
        groups = listOf(title())
        stored[2L] = listOf(Chapter.create().copy(id = 1, mangaId = 2L, chapterNumber = -1.0))
        arrive(1L to listOf(-1.0, -1.0))

        run()

        badge() shouldBe 2
    }

    @Test
    fun `a title that shows every copy adds a row for each copy`() = runTest {
        groups = listOf(title(showAll = true))
        arrive(1L to listOf(5.0), 2L to listOf(5.0))

        run()

        badge() shouldBe 2
    }

    @Test
    fun `an entry outside every group is counted in full beside a merged title`() = runTest {
        groups = listOf(title())
        arrive(1L to listOf(5.0), 2L to listOf(5.0), 3L to listOf(5.0))

        run()

        badge() shouldBe 2
    }

    @Test
    fun `with no group both members add a row for the same number`() = runTest {
        groups = emptyList()
        arrive(1L to listOf(5.0), 2L to listOf(5.0))

        run()

        badge() shouldBe 2
    }

    private object Source100 : Source {
        override val id: Long = 100L
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = manga
        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = emptyList()
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = emptyList()
        override suspend fun getImage(page: PageInfo): ImageResult = error("Unused by the update pass")
        override suspend fun getMangaUpdate(
            manga: MangaInfo,
            chapters: List<ChapterInfo>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): MangaUpdate = MangaUpdate(null, emptyList())
    }
}
