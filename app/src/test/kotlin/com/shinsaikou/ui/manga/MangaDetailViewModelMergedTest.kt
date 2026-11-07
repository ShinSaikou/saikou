package com.shinsaikou.ui.manga

import app.cash.turbine.test
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.chapter.interactor.GetAvailableScanlators
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.interactor.SetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.manga.interactor.SetMangaViewerFlags
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.merged.interactor.AddMergedEntry
import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.interactor.MergeEntries
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.merged.interactor.RemoveMergedMember
import com.shinsaikou.domain.merged.interactor.SetMergedCheckUpdates
import com.shinsaikou.domain.merged.interactor.SetMergedMain
import com.shinsaikou.domain.merged.interactor.SetMergedOrder
import com.shinsaikou.domain.merged.interactor.SetMergedShowAll
import com.shinsaikou.domain.merged.interactor.UnmergeEntries
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.AutoTrackState
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences.ChapterSwipeAction
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.track.TrackProgress
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.i18n.R
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.chapter.ChapterDownloadAction
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The entry screen over a merged title (R163), with the view model on mocks as [MangaDetailViewModelTest]
 * has it, and the real [GetMergedChapters] for the union.
 *
 * Entry [A] is the main entry on source 10, entry [B] is on source 20 and entry [C] on source 30, in
 * that chapter order. A holds chapters 100 and 101 (numbers 1 and 2), B holds 200, 201 and 202
 * (numbers 1, 2 and 3), C holds 300 and 301 (numbers 3 and 4). The copies the group shows are the
 * first source's, so with all three: 100, 101, 202 and 301.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaDetailViewModelMergedTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)

    private val getMangaWithChapters = mockk<GetMangaWithChapters>()
    private val getManga = mockk<GetManga> { coEvery { await(any()) } answers { null } }
    private val updateManga = mockk<UpdateManga>(relaxed = true)
    private val sourceManager = mockk<SourceManager>()
    private val setReadStatus = mockk<SetReadStatus>(relaxed = true)
    private val toggleBookmark = mockk<ToggleBookmark>(relaxed = true)
    private val setMangaViewerFlags = mockk<SetMangaViewerFlags>(relaxed = true)
    private val getExcludedScanlators = mockk<GetExcludedScanlators>()
    private val setExcludedScanlators = mockk<SetExcludedScanlators>(relaxed = true)
    private val getAvailableScanlators = mockk<GetAvailableScanlators>()
    private val getScanlatorPriority = mockk<GetScanlatorPriority>()
    private val setScanlatorPriority = mockk<SetScanlatorPriority>(relaxed = true)
    private val getNextChapters = mockk<GetNextChapters>()
    private val downloadQueue = mockk<DownloadQueue>(relaxed = true)
    private val downloadManifest = mockk<DownloadManifest>(relaxed = true)
    private val downloadedChapters = mockk<DownloadedChapters>()
    private val refreshMangaFromSource = mockk<RefreshMangaFromSource>()
    private val reconcileMergedRead = mockk<ReconcileMergedRead>(relaxed = true)
    private val mergeEntries = mockk<MergeEntries>(relaxed = true)
    private val addMergedEntry = mockk<AddMergedEntry>(relaxed = true)
    private val setMergedOrder = mockk<SetMergedOrder>(relaxed = true)
    private val setMergedMain = mockk<SetMergedMain>(relaxed = true)
    private val setMergedCheckUpdates = mockk<SetMergedCheckUpdates>(relaxed = true)
    private val setMergedShowAll = mockk<SetMergedShowAll>(relaxed = true)
    private val removeMergedMember = mockk<RemoveMergedMember>(relaxed = true)
    private val unmergeEntries = mockk<UnmergeEntries>(relaxed = true)
    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val trackProgress = mockk<TrackProgress>(relaxed = true)
    private val autoDownload = mockk<AutoDownload>(relaxed = true)
    private val getCategories = mockk<GetCategories> { coEvery { await(B) } returns emptyList() }
    private val trackRepository = mockk<TrackRepository> {
        every { getTracksByMangaIdAsFlow(any()) } returns flowOf(emptyList<Track>())
    }

    /** Each entry's row and chapters as the database holds them. */
    private val rows = mutableMapOf<Long, MutableStateFlow<Pair<Manga, List<Chapter>>?>>()
    private val available = mutableMapOf<Long, Set<String>>()
    private val excluded = mutableMapOf<Long, Set<String>>()
    private val downloaded = MutableStateFlow(emptySet<Long>())

    /** The group of whichever member the screen was opened on; null for none. */
    private val group = MutableStateFlow<MergedGroup?>(null)

    /** Source ids [sourceManager] has loaded. */
    private val installed = mutableSetOf(10L, 20L, 30L)
    private val sources = mutableMapOf<Long, Source>()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { getMangaWithChapters.subscribe(any(), applyScanlatorFilter = true) } answers { rows.getValue(firstArg()) }
        every { getExcludedScanlators.subscribe(any()) } answers { flowOf(excluded[firstArg()].orEmpty()) }
        every { getAvailableScanlators.subscribe(any()) } answers { flowOf(available[firstArg()].orEmpty()) }
        every { getScanlatorPriority.subscribe(any()) } returns flowOf(emptyList())
        every { downloadedChapters.subscribeByMangaId(any()) } returns downloaded
        every { downloadQueue.subscribe() } returns flowOf(emptyList())
        every { sourceManager.isInitialized } returns MutableStateFlow(true)
        every { sourceManager.get(any()) } answers {
            val id = firstArg<Long>()
            if (id in installed) sources.getOrPut(id) { source(id) } else null
        }
        every { sourceManager.getOrStub(any()) } answers {
            val id = firstArg<Long>()
            StubSource(id = id, name = "Source $id", lang = "")
        }
        listOf(A, B, C).forEach { coEvery { getNextChapters.await(it) } returns emptyList() }
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun source(id: Long): Source = mockk { every { name } returns "Source $id"; every { homeUrl } returns null }

    private fun entry(id: Long, initialized: Boolean = true, favorite: Boolean = true, source: Long = id * 10) =
        Manga.create().copy(id = id, source = source, favorite = favorite, initialized = initialized, title = "Title $id", url = "/m$id")

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Boolean = false) =
        Chapter.create().copy(id = id, mangaId = mangaId, chapterNumber = number, read = read, name = "Chapter $id")

    private fun seed(
        a: List<Chapter> = listOf(chapter(100, A, 1.0), chapter(101, A, 2.0)),
        b: List<Chapter> = listOf(chapter(200, B, 1.0), chapter(201, B, 2.0), chapter(202, B, 3.0)),
        c: List<Chapter> = listOf(chapter(300, C, 3.0), chapter(301, C, 4.0)),
        entryOf: (Long) -> Manga = ::entry,
    ) {
        rows[A] = MutableStateFlow(entryOf(A) to a)
        rows[B] = MutableStateFlow(entryOf(B) to b)
        rows[C] = MutableStateFlow(entryOf(C) to c)
    }

    private fun groupOf(main: Long, vararg ids: Long, showAll: Boolean = false, id: Long = 1L) = MergedGroup(
        id = id,
        mainMangaId = main,
        showAll = showAll,
        members = ids.mapIndexed { index, mangaId ->
            MergedMember(mangaId, source = mangaId * 10, url = "/m$mangaId", position = index.toLong(), checkUpdates = true)
        },
    )

    private fun getMergedGroups(): GetMergedGroups = mockk { every { subscribe(any()) } returns group }

    private fun viewModel(
        mangaId: Long,
        getMergedGroups: GetMergedGroups = getMergedGroups(),
        fromSource: Boolean = false,
    ) = MangaDetailViewModel(
        mangaId = mangaId,
        fromSource = fromSource,
        getMangaWithChapters = getMangaWithChapters,
        getManga = getManga,
        updateManga = updateManga,
        setMangaDetailsOverride = mockk<SetMangaDetailsOverride>(),
        updateMangaNotes = mockk<UpdateMangaNotes>(),
        coverActions = mockk<MangaCoverActions>(),
        refreshMangaFromSource = refreshMangaFromSource,
        sourceManager = sourceManager,
        setReadStatus = setReadStatus,
        toggleBookmark = toggleBookmark,
        setMangaChapterFlags = SetMangaChapterFlags(mangaRepository),
        setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>(relaxed = true),
        setMangaViewerFlags = setMangaViewerFlags,
        getExcludedScanlators = getExcludedScanlators,
        setExcludedScanlators = setExcludedScanlators,
        getAvailableScanlators = getAvailableScanlators,
        getScanlatorPriority = getScanlatorPriority,
        setScanlatorPriority = setScanlatorPriority,
        getNextChapters = getNextChapters,
        getCategories = getCategories,
        setMangaCategories = mockk<SetMangaCategories>(relaxed = true),
        getDuplicateLibraryManga = mockk<GetDuplicateLibraryManga>(),
        libraryPreferences = libraryPreferences,
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        uiPreferences = UiPreferences(InMemoryPreferenceStore()),
        downloadQueue = downloadQueue,
        downloadManifest = downloadManifest,
        downloadedChapters = downloadedChapters,
        basePreferences = BasePreferences(InMemoryPreferenceStore()),
        trackPreferences = TrackPreferences(MapPreferenceStore()),
        trackProgress = trackProgress,
        autoDownload = autoDownload,
        trackerManager = TrackerManager(emptySet()),
        trackRepository = trackRepository,
        getMergedGroups = getMergedGroups,
        getMergedChapters = GetMergedChapters(mockk(), sourceManager),
        reconcileMergedRead = reconcileMergedRead,
        mergeEntries = mergeEntries,
        addMergedEntry = addMergedEntry,
        setMergedOrder = setMergedOrder,
        setMergedMain = setMergedMain,
        setMergedCheckUpdates = setMergedCheckUpdates,
        setMergedShowAll = setMergedShowAll,
        removeMergedMember = removeMergedMember,
        unmergeEntries = unmergeEntries,
        dispatchers = appDispatchers,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.opened(
        mangaId: Long = A,
        merged: MergedGroup? = groupOf(A, A, B, C),
        getMergedGroups: GetMergedGroups = getMergedGroups(),
    ): MangaDetailViewModel {
        group.value = merged
        return viewModel(mangaId, getMergedGroups).also { advanceUntilIdle() }
    }

    private fun MangaDetailViewModel.success() = state.value as MangaDetailViewModel.State.Success

    private fun MangaDetailViewModel.State.Success.ids() = chapters.map { it.id }

    // ---- Opening on a member ----

    @Test
    fun `any member's id opens the group, with the main entry as the manga`() = runTest(testDispatcher) {
        seed()
        listOf(A, B, C).forEach { id ->
            val viewModel = opened(mangaId = id)

            viewModel.success().manga.id shouldBe A
            viewModel.success().merged.shouldNotBeNull().sources.map { it.mangaId } shouldContainExactly listOf(A, B, C)
        }
    }

    @Test
    fun `the list is one copy of each number through the merged rule, highest first for a descending main entry`() =
        runTest(testDispatcher) {
            seed()

            opened(mangaId = B).success().ids() shouldContainExactly listOf(301L, 202L, 101L, 100L)
        }

    @Test
    fun `show every copy lists every chapter of every member`() = runTest(testDispatcher) {
        seed()

        val viewModel = opened(merged = groupOf(A, A, B, C, showAll = true))

        viewModel.success().ids() shouldContainExactlyInAnyOrder listOf(100L, 101L, 200L, 201L, 202L, 300L, 301L)
    }

    @Test
    fun `the counts are of the union, one per number`() = runTest(testDispatcher) {
        seed(a = listOf(chapter(100, A, 1.0, read = true), chapter(101, A, 2.0)))

        val success = opened().success()

        success.chapterTotal shouldBe 4
        success.chapterRead shouldBe 1
    }

    @Test
    fun `a row names the entry that owns it and the source line lists every source`() = runTest(testDispatcher) {
        seed()
        val success = opened().success()

        success.ownerOf(chapter(202, B, 3.0)).id shouldBe B
        success.ownerOf(chapter(301, C, 4.0)).id shouldBe C
        success.merged.shouldNotBeNull().sources.map { it.sourceName } shouldContainExactly listOf("Source 10", "Source 20", "Source 30")
        success.merged.sources.first { it.mangaId == A }.isMain shouldBe true
        success.merged.sources.first { it.mangaId == B }.chapterCount shouldBe 3
        success.sourceNameOf(chapter(202, B, 3.0)) shouldBe "Source 20"
    }

    @Test
    fun `the group's read marks are reconciled once when the screen first sees it`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()

        // The group changes under the open screen, keeping its id.
        group.value = groupOf(A, A, B)
        advanceUntilIdle()

        viewModel.success().merged.shouldNotBeNull().sources.map { it.mangaId } shouldContainExactly listOf(A, B)
        coVerify(exactly = 1) { reconcileMergedRead.await(A) }
    }

    // ---- The group changing while the screen is open ----

    @Test
    fun `an unmerge leaves the entry the screen was opened on, with its own chapters`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened(mangaId = B)
        viewModel.success().manga.id shouldBe A

        group.value = null
        advanceUntilIdle()

        val success = viewModel.success()
        success.merged.shouldBeNull()
        success.manga.id shouldBe B
        success.ids() shouldContainExactlyInAnyOrder listOf(200L, 201L, 202L)
    }

    @Test
    fun `a member removed takes its chapters off the list`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        viewModel.success().ids() shouldContainExactly listOf(301L, 202L, 101L, 100L)

        group.value = groupOf(A, A, B)
        advanceUntilIdle()

        viewModel.success().ids() shouldContainExactly listOf(202L, 101L, 100L)
        viewModel.success().merged.shouldNotBeNull().owners.keys shouldBe setOf(A, B)
    }

    @Test
    fun `a new main entry becomes the manga`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()

        group.value = groupOf(B, B, A, C)
        advanceUntilIdle()

        viewModel.success().manga.id shouldBe B
        viewModel.success().merged.shouldNotBeNull().main.shouldNotBeNull().mangaId shouldBe B
    }

    @Test
    fun `a new order moves the winner of a number`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()

        group.value = groupOf(A, B, A, C)
        advanceUntilIdle()

        viewModel.success().ids() shouldContainExactly listOf(301L, 202L, 201L, 200L)
    }

    @Test
    fun `the Merged sources sheet closes with the group it shows`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        viewModel.showMergedSources()
        viewModel.success().dialog shouldBe MangaDetailViewModel.Dialog.MergedSources

        group.value = null
        advanceUntilIdle()

        viewModel.success().dialog.shouldBeNull()
    }

    @Test
    fun `a selection survives a group change for the rows still there`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        viewModel.toggleSelection(301)
        viewModel.toggleSelection(101)

        group.value = groupOf(A, A, B)
        advanceUntilIdle()

        viewModel.success().selection shouldBe setOf(101L)
    }

    // ---- Download, delete, mark and bookmark act on each chapter's owner ----

    @Test
    fun `a row's download is queued under the entry that owns the chapter`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        val fromC = viewModel.success().chapters.first { it.id == 301L }

        viewModel.runChapterDownloadAction(fromC, ChapterDownloadAction.START)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == C }, listOf(fromC), true) }
    }

    @Test
    fun `a swipe that starts a download starts it under the owner`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        val fromB = viewModel.success().chapters.first { it.id == 202L }

        viewModel.swipeChapter(fromB, ChapterSwipeAction.Download)
        advanceUntilIdle()

        // Start now queues the one chapter without starting, moves it to the front, then starts.
        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == B }, listOf(fromB), false) }
    }

    @Test
    fun `downloading a selection queues each chapter under its owner, in reading order`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        listOf(301L, 202L, 101L).forEach(viewModel::toggleSelection)

        viewModel.downloadSelected()
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == A }, match { list -> list.map { it.id } == listOf(101L) }, true) }
        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == B }, match { list -> list.map { it.id } == listOf(202L) }, true) }
        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == C }, match { list -> list.map { it.id } == listOf(301L) }, true) }
    }

    @Test
    fun `a Local member's chapters are not queued, though the others are`() = runTest(testDispatcher) {
        seed(entryOf = { id -> if (id == C) entry(C, source = SourceIds.LOCAL) else entry(id) })
        val viewModel = opened()
        listOf(301L, 202L).forEach(viewModel::toggleSelection)

        viewModel.downloadSelected()
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.enqueue(match { it.id == B }, any(), true) }
        coVerify(exactly = 0) { downloadQueue.enqueue(match { it.id == C }, any(), any()) }
        viewModel.success().downloadOf(301) shouldBe com.shinsaikou.ui.chapter.ChapterDownload.None
    }

    @Test
    fun `deleting a selection from two members asks about the downloaded ones and deletes by chapter id`() =
        runTest(testDispatcher) {
            seed()
            downloaded.value = setOf(202L, 101L)
            val viewModel = opened()
            listOf(301L, 202L, 101L).forEach(viewModel::toggleSelection)

            viewModel.deleteSelected()
            advanceUntilIdle()
            val asking = viewModel.success().dialog as MangaDetailViewModel.Dialog.DeleteChapters
            asking.chapterIds shouldContainExactlyInAnyOrder listOf(202L, 101L)

            viewModel.confirmDeleteChapters()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadManifest.delete(asking.chapterIds) }
        }

    @Test
    fun `the queue of every member shows on the rows`() = runTest(testDispatcher) {
        seed()
        every { downloadQueue.subscribe() } returns flowOf(
            listOf(
                queueRow(chapterId = 202, mangaId = B),
                queueRow(chapterId = 301, mangaId = C),
                queueRow(chapterId = 900, mangaId = 99),
            ),
        )

        opened().success().queued.keys shouldBe setOf(202L, 301L)
    }

    @Test
    fun `a hidden copy on disk badges the row that shows its number`() = runTest(testDispatcher) {
        seed()
        downloaded.value = setOf(200L)

        opened().success().hiddenDownloads shouldBe setOf(100L)
    }

    @Test
    fun `marking a chapter of another member reads that chapter`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        val fromB = viewModel.success().chapters.first { it.id == 202L }

        viewModel.markRead(fromB, read = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { setReadStatus.await(true, fromB) }
    }

    @Test
    fun `bookmarking a selection from two members toggles each chapter by id`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        listOf(301L, 101L).forEach(viewModel::toggleSelection)

        viewModel.bookmarkSelected(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { toggleBookmark.await(301L, true) }
        coVerify(exactly = 1) { toggleBookmark.await(101L, true) }
    }

    @Test
    fun `mark previous as read walks the merged list by number across members`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()
        // 202 is B's and 301 is C's: the latest by number is 301, whatever the order the rows were picked in.
        listOf(202L, 301L, 101L).forEach(viewModel::toggleSelection)

        viewModel.markSelectedPreviousRead()
        advanceUntilIdle()

        coVerify(exactly = 1) { setReadStatus.awaitAllBefore(match { it.id == 301L }, true) }
    }

    // ---- Refresh ----

    private fun refreshOf(entry: Manga, added: List<Chapter> = emptyList()) =
        RefreshMangaFromSource.Result(entry, added)

    @Test
    fun `a refresh runs every member`() = runTest(testDispatcher) {
        seed()
        coEvery { refreshMangaFromSource(any(), any(), any(), any(), any()) } answers { refreshOf(secondArg()) }
        val viewModel = opened()

        viewModel.refresh(manual = true)
        advanceUntilIdle()

        listOf(A, B, C).forEach { id ->
            coVerify(exactly = 1) { refreshMangaFromSource(any(), match { it.id == id }, true, true, true) }
        }
    }

    @Test
    fun `a failing member is named, the others finish, and their new chapters are counted`() = runTest(testDispatcher) {
        seed()
        val boom = java.io.IOException("offline")
        coEvery { refreshMangaFromSource(any(), any(), any(), any(), any()) } answers {
            val manga = secondArg<Manga>()
            when (manga.id) {
                B -> throw boom
                C -> refreshOf(manga, listOf(chapter(302, C, 5.0), chapter(303, C, 6.0)))
                else -> refreshOf(manga)
            }
        }
        val viewModel = opened()

        viewModel.events.test {
            viewModel.refresh(manual = true)
            advanceUntilIdle()

            awaitItem() shouldBe MangaDetailViewModel.Event.Snackbar(R.string.merged_refresh_failed_format, listOf("Source 20", boom))
            awaitItem() shouldBe MangaDetailViewModel.Event.Snackbar(R.string.manga_new_chapters, listOf(2))
            expectNoEvents()
        }
        coVerify(exactly = 1) { refreshMangaFromSource(any(), match { it.id == C }, true, true, true) }
        viewModel.success().refreshing shouldBe false
    }

    @Test
    fun `a member whose source is not installed is a failure of its own and the others still refresh`() =
        runTest(testDispatcher) {
            seed()
            installed -= 20L
            coEvery { refreshMangaFromSource(any(), any(), any(), any(), any()) } answers { refreshOf(secondArg()) }
            val viewModel = opened()

            viewModel.events.test {
                viewModel.refresh(manual = true)
                advanceUntilIdle()

                val failure = awaitItem() as MangaDetailViewModel.Event.Snackbar
                failure.messageRes shouldBe R.string.merged_refresh_failed_format
                failure.args.first() shouldBe "Source 20"
                expectNoEvents()
            }
            coVerify(exactly = 1) { refreshMangaFromSource(any(), match { it.id == A }, any(), any(), any()) }
            coVerify(exactly = 1) { refreshMangaFromSource(any(), match { it.id == C }, any(), any(), any()) }
            coVerify(exactly = 0) { refreshMangaFromSource(any(), match { it.id == B }, any(), any(), any()) }
        }

    @Test
    fun `a manual refresh that fails nowhere and finds nothing says the title is up to date`() = runTest(testDispatcher) {
        seed()
        coEvery { refreshMangaFromSource(any(), any(), any(), any(), any()) } answers { refreshOf(secondArg()) }
        val viewModel = opened()

        viewModel.events.test {
            viewModel.refresh(manual = true)
            advanceUntilIdle()

            awaitItem() shouldBe MangaDetailViewModel.Event.Snackbar(R.string.manga_up_to_date)
            expectNoEvents()
        }
    }

    @Test
    fun `the new chapters of a refresh the owner asked for are queued under the member that fetched them`() =
        runTest(testDispatcher) {
            seed()
            val added = listOf(chapter(302, C, 5.0))
            coEvery { refreshMangaFromSource(any(), any(), any(), any(), any()) } answers {
                val manga = secondArg<Manga>()
                if (manga.id == C) refreshOf(manga, added) else refreshOf(manga)
            }
            val viewModel = opened()

            viewModel.refresh(manual = true)
            advanceUntilIdle()

            viewModel.success().refreshing shouldBe false
            coVerify(exactly = 1) { autoDownload.queueNewChapters(match { it.id == C }, added, true) }
            coVerify(exactly = 0) { autoDownload.queueNewChapters(match { it.id != C }, any(), any()) }
        }

    @Test
    fun `a member that was never fetched is fetched once, alone`() = runTest(testDispatcher) {
        seed(entryOf = { id -> entry(id, initialized = id != C) }, c = emptyList())
        coEvery { refreshMangaFromSource(any(), any(), any(), any(), any()) } answers { refreshOf(secondArg()) }
        val viewModel = opened()

        coVerify(exactly = 1) { refreshMangaFromSource(any(), match { it.id == C }, true, true, false) }
        coVerify(exactly = 0) { refreshMangaFromSource(any(), match { it.id != C }, any(), any(), any()) }

        // The member's row ticks again with nothing new: it is not asked for a second time.
        rows.getValue(C).value = entry(C, initialized = false) to emptyList()
        advanceUntilIdle()

        coVerify(exactly = 1) { refreshMangaFromSource(any(), match { it.id == C }, any(), any(), any()) }
        viewModel.success().refreshing shouldBe false
    }

    @Test
    fun `fully fetched members are not fetched on open`() = runTest(testDispatcher) {
        seed()
        opened()

        coVerify(exactly = 0) { refreshMangaFromSource(any(), any(), any(), any(), any()) }
    }

    // ---- Scanlators ----

    @Test
    fun `the scanlator sheet shows the union of every member's groups`() = runTest(testDispatcher) {
        seed()
        available[A] = setOf("Alpha", "Shared")
        available[B] = setOf("Shared", "Beta")
        excluded[C] = setOf("Gamma")

        val success = opened().success()

        success.availableScanlators shouldBe setOf("Alpha", "Shared", "Beta")
        success.excludedScanlators shouldBe setOf("Gamma")
    }

    @Test
    fun `confirming the scanlators writes the answer to every member`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened(mangaId = B)

        viewModel.confirmScanlators(setOf("Beta"), MangaDetailViewModel.RankingChange.Write(listOf("Alpha", "Beta")))
        advanceUntilIdle()

        listOf(A, B, C).forEach { id ->
            coVerify(exactly = 1) { setExcludedScanlators.await(id, setOf("Beta")) }
            coVerify(exactly = 1) { setScanlatorPriority.await(id, listOf("Alpha", "Beta")) }
        }
        viewModel.success().dialog.shouldBeNull()
    }

    @Test
    fun `resetting the ranking clears it on every member, and keeping it writes none`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened()

        viewModel.confirmScanlators(emptySet(), MangaDetailViewModel.RankingChange.Clear)
        advanceUntilIdle()
        viewModel.confirmScanlators(emptySet(), MangaDetailViewModel.RankingChange.Keep)
        advanceUntilIdle()

        listOf(A, B, C).forEach { id -> coVerify(exactly = 1) { setScanlatorPriority.clear(id) } }
        coVerify(exactly = 0) { setScanlatorPriority.await(any(), any()) }
    }

    @Test
    fun `the scanlator write of an entry on its own goes to that entry alone`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened(mangaId = B, merged = null)

        viewModel.confirmScanlators(setOf("Beta"), MangaDetailViewModel.RankingChange.Keep)
        advanceUntilIdle()

        coVerify(exactly = 1) { setExcludedScanlators.await(B, setOf("Beta")) }
        coVerify(exactly = 0) { setExcludedScanlators.await(A, any()) }
        coVerify(exactly = 0) { setExcludedScanlators.await(C, any()) }
    }

    // ---- sourceInstalled ----

    @Test
    fun `the source is installed while any member's is`() = runTest(testDispatcher) {
        seed()
        installed.clear()
        installed += 30L

        opened().success().sourceInstalled shouldBe true
    }

    @Test
    fun `the source is not installed when no member's is`() = runTest(testDispatcher) {
        seed()
        installed.clear()

        opened().success().sourceInstalled shouldBe false
    }

    @Test
    fun `a main entry with an uninstalled source still reads from the others`() = runTest(testDispatcher) {
        seed()
        installed -= 10L

        val success = opened().success()

        success.sourceInstalled shouldBe true
        success.manga.id shouldBe A
        success.ids() shouldContainExactly listOf(301L, 202L, 201L, 200L)
    }

    // ---- Title-level writes go to the main entry ----

    @Test
    fun `the reading mode of a title opened on a member is the main entry's`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened(mangaId = C)

        viewModel.setReadingMode(ReadingMode.WEBTOON)
        advanceUntilIdle()

        coVerify(exactly = 1) { setMangaViewerFlags.awaitSetReadingMode(A, ReadingMode.WEBTOON) }
    }

    @Test
    fun `removing a merged title asks first and then takes every source out of the library`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened(mangaId = B)

        viewModel.toggleFavorite()
        advanceUntilIdle()
        viewModel.success().dialog shouldBe MangaDetailViewModel.Dialog.RemoveMerged(3)
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }

        viewModel.confirmRemoveMerged()
        advanceUntilIdle()

        listOf(A, B, C).forEach { id -> coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(id, false) } }
        viewModel.success().dialog.shouldBeNull()
    }

    @Test
    fun `merging with a duplicate adds this entry and keeps the duplicate as main`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened(mangaId = B, merged = null)

        viewModel.mergeWithDuplicate(duplicateId = C)
        advanceUntilIdle()

        coVerify(exactly = 1) { addMergedEntry.await(match { it.id == B }) }
        coVerify(exactly = 1) { mergeEntries.await(C, listOf(C, B)) }
    }

    @Test
    fun `merging with a duplicate whose add fails says so, and does not merge or report done`() =
        runTest(testDispatcher) {
            seed()
            coEvery { addMergedEntry.await(any()) } throws IllegalStateException("db busy")
            val viewModel = opened(mangaId = B, merged = null)

            viewModel.events.test {
                viewModel.mergeWithDuplicate(duplicateId = C)
                advanceUntilIdle()

                awaitItem() shouldBe MangaDetailViewModel.Event.Snackbar(R.string.merged_failed)
                expectNoEvents()
            }
            coVerify(exactly = 0) { mergeEntries.await(any(), any()) }
        }

    @Test
    fun `merging with a duplicate whose merge fails says so and does not report done`() = runTest(testDispatcher) {
        seed()
        coEvery { mergeEntries.await(any(), any()) } throws IllegalStateException("db busy")
        val viewModel = opened(mangaId = B, merged = null)

        viewModel.events.test {
            viewModel.mergeWithDuplicate(duplicateId = C)
            advanceUntilIdle()

            awaitItem() shouldBe MangaDetailViewModel.Event.Snackbar(R.string.merged_failed)
            expectNoEvents()
        }
    }

    @Test
    fun `the sheet's actions reach their interactors`() = runTest(testDispatcher) {
        seed()
        val viewModel = opened(mangaId = B)

        viewModel.setMergedSourceOrder(listOf(C, B, A))
        viewModel.useAsMain(B)
        viewModel.setSourceCheckUpdates(C, false)
        viewModel.removeMergedSource(C)
        viewModel.setShowEveryCopy(true)
        viewModel.unmerge()
        advanceUntilIdle()

        coVerify(exactly = 1) { setMergedOrder.await(listOf(C, B, A)) }
        coVerify(exactly = 1) { setMergedMain.await(B) }
        coVerify(exactly = 1) { setMergedCheckUpdates.await(C, false) }
        coVerify(exactly = 1) { removeMergedMember.await(C) }
        coVerify(exactly = 1) { setMergedShowAll.await(B, true) }
        coVerify(exactly = 1) { unmergeEntries.await(B) }
    }

    // ---- An entry on its own, with the switch on and off ----

    @Test
    fun `an entry in no group is the manga it was opened on, with no merged title, whatever the switch says`() =
        runTest(testDispatcher) {
            seed()
            for (on in listOf(false, true)) {
                libraryPreferences.mergedEntries.set(on)

                val viewModel = opened(mangaId = B, merged = null)

                val success = viewModel.success()
                success.merged.shouldBeNull()
                success.manga.id shouldBe B
                success.ids() shouldContainExactlyInAnyOrder listOf(200L, 201L, 202L)
                success.hiddenDownloads shouldBe emptySet()
                success.mergedEntries shouldBe on
            }
        }

    @Test
    fun `an entry on its own is removed as before, with no question`() = runTest(testDispatcher) {
        seed()
        libraryPreferences.mergedEntries.set(true)
        val viewModel = opened(mangaId = B, merged = null)

        viewModel.toggleFavorite()
        advanceUntilIdle()

        viewModel.success().dialog.shouldBeNull()
        coVerify(exactly = 1) { updateManga.awaitUpdateFavorite(B, false) }
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(A, any()) }
    }

    @Test
    fun `with the switch off a stored group is not read, and turning it on brings the title in while the screen is open`() =
        runTest(testDispatcher) {
            seed()
            val repository = mockk<MergedGroupRepository> {
                every { subscribeLiveGroups() } returns flowOf(listOf(groupOf(A, A, B, C)))
            }
            val viewModel = viewModel(B, GetMergedGroups(repository, libraryPreferences))
            advanceUntilIdle()

            viewModel.success().merged.shouldBeNull()
            viewModel.success().manga.id shouldBe B
            viewModel.success().ids() shouldContainExactlyInAnyOrder listOf(200L, 201L, 202L)
            verify(exactly = 0) { repository.subscribeLiveGroups() }

            libraryPreferences.mergedEntries.set(true)
            advanceUntilIdle()

            viewModel.success().merged.shouldNotBeNull()
            viewModel.success().manga.id shouldBe A
            viewModel.success().ids() shouldContainExactly listOf(301L, 202L, 101L, 100L)
        }

    // ---- The sort sheet over a stored sort by source ----

    private fun seedSorted(flags: Long) = seed(entryOf = { id -> entry(id).copy(chapterFlags = flags) })

    private val bySourceAsc = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_ASC
    private val bySourceDesc = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_DESC

    @Test
    fun `a stored sort by source lists the union by number, ascending`() = runTest(testDispatcher) {
        seedSorted(bySourceAsc)

        opened().success().ids() shouldContainExactly listOf(100L, 101L, 202L, 301L)
    }

    @Test
    fun `a stored sort by source lists the union by number, descending`() = runTest(testDispatcher) {
        seedSorted(bySourceDesc)

        opened().success().ids() shouldContainExactly listOf(301L, 202L, 101L, 100L)
    }

    @Test
    fun `the sheet shows a stored sort by source as the number sort in the same direction`() = runTest(testDispatcher) {
        seedSorted(bySourceAsc)

        val success = opened().success()

        success.sortedManga.sorting shouldBe Manga.CHAPTER_SORTING_NUMBER
        success.sortedManga.sortDescending() shouldBe false
        success.manga.sorting shouldBe Manga.CHAPTER_SORTING_SOURCE
    }

    @Test
    fun `the sheet leaves a stored sort by upload date as it is`() = runTest(testDispatcher) {
        seedSorted(Manga.CHAPTER_SORTING_UPLOAD_DATE or Manga.CHAPTER_SORT_ASC)

        val success = opened().success()

        success.sortedManga shouldBe success.manga
    }

    @Test
    fun `the first tap on the active number row flips the direction, from a stored sort by source ascending`() =
        runTest(testDispatcher) {
            seedSorted(bySourceAsc)
            val viewModel = opened()

            viewModel.setChapterSort(Manga.CHAPTER_SORTING_NUMBER)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                mangaRepository.update(MangaUpdate(id = A, chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_DESC))
            }
        }

    @Test
    fun `the first tap on the active number row flips the direction, from a stored sort by source descending`() =
        runTest(testDispatcher) {
            seedSorted(bySourceDesc)
            val viewModel = opened()

            viewModel.setChapterSort(Manga.CHAPTER_SORTING_NUMBER)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                mangaRepository.update(MangaUpdate(id = A, chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC))
            }
        }

    @Test
    fun `a tap on another row sets that field ascending`() = runTest(testDispatcher) {
        seedSorted(bySourceDesc)
        val viewModel = opened()

        viewModel.setChapterSort(Manga.CHAPTER_SORTING_ALPHABET)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            mangaRepository.update(MangaUpdate(id = A, chapterFlags = Manga.CHAPTER_SORTING_ALPHABET or Manga.CHAPTER_SORT_ASC))
        }
    }

    @Test
    fun `a single entry keeps its sort by source in the sheet and a tap on it flips the direction`() = runTest(testDispatcher) {
        seedSorted(bySourceAsc)
        val viewModel = opened(merged = null)

        viewModel.success().sortedManga shouldBe viewModel.success().manga

        viewModel.setChapterSort(Manga.CHAPTER_SORTING_SOURCE)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            mangaRepository.update(MangaUpdate(id = A, chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_DESC))
        }
    }

    private fun queueRow(chapterId: Long, mangaId: Long) = com.shinsaikou.domain.download.model.DownloadQueueEntry(
        id = chapterId,
        chapterId = chapterId,
        mangaId = mangaId,
        state = com.shinsaikou.domain.download.model.DownloadState.QUEUED,
        queueOrder = chapterId,
        totalPages = 0,
        downloadedPages = 0,
        errorMessage = null,
        dateAdded = 0,
        dateStarted = null,
        dateCompleted = null,
    )

    private companion object {
        const val A = 1L
        const val B = 2L
        const val C = 3L
    }
}
