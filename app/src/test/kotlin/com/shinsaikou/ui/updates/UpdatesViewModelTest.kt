package com.shinsaikou.ui.updates

import app.cash.turbine.test
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.interactor.ToggleBookmark
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.preferences.UpdatesPreferences
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.LibraryUpdateError
import com.shinsaikou.libraryupdate.LibraryUpdateErrorStore
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.libraryupdate.LibraryUpdateWorker
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.chapter.ChapterDownload
import com.shinsaikou.ui.chapter.ChapterDownloadAction
import com.shinsaikou.ui.fixture.RecordingReadChapterCleanup
import com.shinsaikou.ui.fixture.deletingSetReadStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import java.util.TimeZone as JavaTimeZone
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * Covers the row projection reaching state, the stored filters reaching the query and the
 * downloaded filter applied after it (phase 4 unit UF), the refresh snackbar branches driven by
 * LibraryUpdateScheduler.startNow, and the markRead path that has to load a Chapter before
 * SetReadStatus can be called since SetReadStatus only accepts chapters, never a bare id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val getUpdates = mockk<GetUpdates>()
    private val getChapter = mockk<GetChapter>()
    private val setReadStatus = mockk<SetReadStatus>()
    private val toggleBookmark = mockk<ToggleBookmark>()
    private val libraryUpdateScheduler = mockk<LibraryUpdateScheduler>()
    private val errorStore = mockk<LibraryUpdateErrorStore>()
    private val sourceManager = mockk<SourceManager>()
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val updatesPreferences = UpdatesPreferences(InMemoryPreferenceStore())
    private val uiPreferences = UiPreferences(InMemoryPreferenceStore())
    private val downloadedChapters = mockk<DownloadedChapters>()
    private val downloadQueue = mockk<DownloadQueue>(relaxed = true)
    private val downloadManifest = mockk<DownloadManifest>(relaxed = true)
    private val getManga = mockk<GetManga>()
    private val getCategories = mockk<GetCategories>()

    /** The queue's rows, and the downloaded answer for the shown chapters, for the rows' controls. */
    private val queueFlow = MutableStateFlow<List<DownloadQueueEntry>>(emptyList())
    private val shownDownloadedFlow = MutableStateFlow<Set<Long>>(emptySet())

    /** Every id set the view model asked the downloaded answer about, in call order. */
    private val askedIds = mutableListOf<Set<Long>>()

    private val rowsFlow = MutableStateFlow<List<UpdatesWithRelations>>(emptyList())
    private val downloadedFlow = MutableStateFlow<Set<Long>>(emptySet())
    private val categoriesFlow = MutableStateFlow<List<Category>>(emptyList())

    /** The six query filters as `GetUpdates.subscribe` receives them, after the instant. */
    private data class Query(
        val unread: Boolean? = null,
        val started: Boolean? = null,
        val bookmarked: Boolean? = null,
        val hideExcludedScanlators: Boolean = false,
        val includedCategories: List<Long> = emptyList(),
        val excludedCategories: List<Long> = emptyList(),
    )

    /** What the query answers for a filtered call; the unfiltered one answers [rowsFlow]. */
    private val rowsByQuery = mutableMapOf<Query, List<UpdatesWithRelations>>()
    private val queries = mutableListOf<Query>()

    /** The instant each `GetUpdates.subscribe` call received, in call order. */
    private val instants = mutableListOf<Instant>()

    /** The wall clock the view model reads; a test moves it to show the window is read once. */
    private var now: Instant = Instant.parse("2026-09-30T12:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@UpdatesViewModelTest.now
    }
    private val isRunningFlow = MutableStateFlow(false)

    /**
     * The store is mocked rather than real because its own `load()` hardcodes
     * `kotlinx.coroutines.Dispatchers.IO`, which a test scheduler cannot drive.
     */
    private val errorsFlow = MutableStateFlow<List<LibraryUpdateError>>(emptyList())

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        // Runs wait only in a start without extensions, which one test sets up.
        every { libraryUpdateScheduler.isPaused } returns false
        every { getUpdates.subscribe(any(), any(), any(), any(), any(), any(), any()) } answers {
            val query = Query(
                unread = secondArg(),
                started = thirdArg(),
                bookmarked = arg(3),
                hideExcludedScanlators = arg(4),
                includedCategories = arg(5),
                excludedCategories = arg(6),
            )
            queries += query
            instants += firstArg<Instant>()
            if (query == Query()) rowsFlow else flowOf(rowsByQuery[query].orEmpty())
        }
        every { downloadedChapters.subscribeAll() } returns downloadedFlow
        every { downloadedChapters.subscribeByChapterIds(any()) } answers {
            askedIds += firstArg<Set<Long>>()
            shownDownloadedFlow
        }
        every { downloadQueue.subscribe() } returns queueFlow
        every { getCategories.subscribe() } returns categoriesFlow
        every { libraryUpdateScheduler.isRunning } returns isRunningFlow
        coEvery { errorStore.load() } returns Unit
        every { errorStore.errors } returns errorsFlow
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel(setReadStatus: SetReadStatus = this.setReadStatus) = UpdatesViewModel(
        getUpdates = getUpdates,
        getChapter = getChapter,
        setReadStatus = setReadStatus,
        toggleBookmark = toggleBookmark,
        libraryUpdateScheduler = libraryUpdateScheduler,
        errorStore = errorStore,
        sourceManager = sourceManager,
        libraryPreferences = libraryPreferences,
        updatesPreferences = updatesPreferences,
        uiPreferences = uiPreferences,
        downloadedChapters = downloadedChapters,
        downloadQueue = downloadQueue,
        downloadManifest = downloadManifest,
        getManga = getManga,
        getCategories = getCategories,
        dispatchers = AppDispatchers(
            io = testDispatcher,
            default = testDispatcher,
            main = testDispatcher,
        ),
        clock = clock,
    )

    private fun update(chapterId: Long, read: Boolean = false) = UpdatesWithRelations(
        mangaId = 1L,
        mangaTitle = "Fixture Manga",
        displayTitle = "Fixture Manga",
        chapterId = chapterId,
        chapterName = "Chapter 1",
        scanlator = null,
        chapterUrl = "https://example.invalid/1",
        read = read,
        bookmark = false,
        lastPageRead = 0L,
        sourceId = 1L,
        dateFetch = 0L,
        coverData = MangaCover(mangaId = 1L, sourceId = 1L, isMangaFavorite = false, url = null, lastModified = 0L),
    )

    private fun chapter(id: Long) = Chapter.create().copy(id = id)

    private fun updateError(
        mangaId: Long,
        title: String,
        sourceId: Long,
        key: ErrorKey = ErrorKey.NO_CHAPTERS,
    ) = LibraryUpdateError(
        mangaId = mangaId,
        title = title,
        sourceId = sourceId,
        key = key,
        detail = null,
        at = 1_000L,
    )

    private fun namedSource(name: String): Source = mockk<Source>().also { every { it.name } returns name }

    @Test
    fun `the updates flow reaches state`() = runTest(testDispatcher) {
        val unread = update(chapterId = 1L, read = false)
        val read = update(chapterId = 2L, read = true)
        rowsFlow.value = listOf(unread, read)

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.loading shouldBe false
        viewModel.state.value.items shouldContainExactly listOf(unread, read)
    }

    /**
     * The time a run last started was written and never shown, so a schedule that had stopped
     * running gave no sign of it. The line above the rows reads it, and follows a run that starts
     * while the tab is open.
     */
    @Test
    fun `the last library update time reaches state and follows a new run`() = runTest(testDispatcher) {
        libraryPreferences.lastUpdatedTimestamp.set(1_000L)

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.lastUpdated shouldBe 1_000L

        libraryPreferences.lastUpdatedTimestamp.set(2_000L)
        advanceUntilIdle()
        viewModel.state.value.lastUpdated shouldBe 2_000L
    }

    @Test
    fun `the last automatic run's skip counts reach state, a malformed one left out`() = runTest(testDispatcher) {
        libraryPreferences.lastAutoUpdateSkipped.set(setOf("manga_ongoing:5", "fetch_once:2", "garbled"))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.lastAutoSkipped shouldBe mapOf("manga_ongoing" to 5, "fetch_once" to 2)
    }

    @Test
    fun `Retry on the error dialog starts an update over the failed entries`() = runTest(testDispatcher) {
        errorsFlow.value = listOf(
            updateError(4L, "Fixture 4", sourceId = 1L),
            updateError(9L, "Fixture 9", sourceId = 2L),
        )
        coEvery { libraryUpdateScheduler.startNow(mangaIds = any()) } returns true
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.retryErrors()
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.library_update_entries_started)
        }
        coVerify(exactly = 1) { libraryUpdateScheduler.startNow(null, false, listOf(4L, 9L)) }
        viewModel.state.value.dialog shouldBe null
    }

    /** As the library's Retry: the first entries a run can name, and the next Retry takes the rest. */
    @Test
    fun `Retry over more failed entries than a run can name updates as many as it can`() = runTest(testDispatcher) {
        errorsFlow.value = (1L..LibraryUpdateWorker.MAX_MANGA_IDS + 1L)
            .map { updateError(it, "Fixture $it", sourceId = 1L) }
        coEvery { libraryUpdateScheduler.startNow(mangaIds = any()) } returns true
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.retryErrors()
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.library_update_entries_started)
        }
        coVerify(exactly = 1) {
            libraryUpdateScheduler.startNow(null, false, (1L..LibraryUpdateWorker.MAX_MANGA_IDS).toList())
        }
    }

    @Test
    fun `refresh starts the library update and reports it started`() = runTest(testDispatcher) {
        coEvery { libraryUpdateScheduler.startNow() } returns true
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.library_update_started)
        }
    }

    @Test
    fun `refresh reports that an update is already running`() = runTest(testDispatcher) {
        coEvery { libraryUpdateScheduler.startNow() } returns false
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.library_update_already_running)
        }
    }

    @Test
    fun `refresh in a start without extensions says updates wait for a restart`() = runTest(testDispatcher) {
        every { libraryUpdateScheduler.isPaused } returns true
        coEvery { libraryUpdateScheduler.startNow() } returns false
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.library_update_paused)
        }
    }

    @Test
    fun `markRead loads the chapter through GetChapter before calling SetReadStatus`() = runTest(testDispatcher) {
        val row = update(chapterId = 5L, read = false)
        val loadedChapter = chapter(id = 5L)
        coEvery { getChapter.await(5L) } returns loadedChapter
        coEvery { setReadStatus.await(true, loadedChapter) } returns SetReadStatus.Result.Success(listOf(loadedChapter))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markRead(row, true)
        advanceUntilIdle()

        coVerify(exactly = 1) { getChapter.await(5L) }
        coVerify(exactly = 1) { setReadStatus.await(true, loadedChapter) }
    }

    /** Phase 3 unit DR: the Updates row's mark read, through the real interactor, reaches the delete. */
    @Test
    fun `markRead with after manually marked as read on hands the chapter to the download cleanup`() = runTest(testDispatcher) {
        val row = update(chapterId = 5L, read = false)
        coEvery { getChapter.await(5L) } returns chapter(id = 5L)
        val cleanup = RecordingReadChapterCleanup()
        val viewModel = viewModel(setReadStatus = deletingSetReadStatus(mockk(relaxed = true), cleanup))
        advanceUntilIdle()

        viewModel.markRead(row, true)
        advanceUntilIdle()

        cleanup.marked shouldBe listOf(listOf(5L))
    }

    @Test
    fun `markRead does not call SetReadStatus when the chapter is missing`() = runTest(testDispatcher) {
        val row = update(chapterId = 9L, read = false)
        coEvery { getChapter.await(9L) } returns null
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.markRead(row, true)
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.error_generic)
        }
        coVerify(exactly = 0) { setReadStatus.await(any(), any<Chapter>()) }
    }

    @Test
    fun `markRead emits an error snackbar when SetReadStatus reports an internal error`() = runTest(testDispatcher) {
        val row = update(chapterId = 7L, read = false)
        val loadedChapter = chapter(id = 7L)
        coEvery { getChapter.await(7L) } returns loadedChapter
        coEvery {
            setReadStatus.await(true, loadedChapter)
        } returns SetReadStatus.Result.InternalError(RuntimeException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.markRead(row, true)
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.error_generic)
        }
    }

    @Test
    fun `markRead sends no event when SetReadStatus succeeds`() = runTest(testDispatcher) {
        val row = update(chapterId = 3L, read = false)
        val loadedChapter = chapter(id = 3L)
        coEvery { getChapter.await(3L) } returns loadedChapter
        coEvery { setReadStatus.await(true, loadedChapter) } returns SetReadStatus.Result.Success(listOf(loadedChapter))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.markRead(row, true)
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `markRead sends no event when SetReadStatus reports no chapters to update`() = runTest(testDispatcher) {
        val row = update(chapterId = 4L, read = false)
        val loadedChapter = chapter(id = 4L)
        coEvery { getChapter.await(4L) } returns loadedChapter
        coEvery { setReadStatus.await(true, loadedChapter) } returns SetReadStatus.Result.NoChapters
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.markRead(row, true)
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `reaching the tab clears the unseen updates count`() = runTest(testDispatcher) {
        libraryPreferences.newUpdatesCount.set(7)

        viewModel()

        libraryPreferences.newUpdatesCount.get() shouldBe 0
    }

    @Test
    fun `markRead with a selection marks exactly the selected rows and clears the selection`() = runTest(testDispatcher) {
        val first = update(chapterId = 1L, read = false)
        val second = update(chapterId = 2L, read = false)
        val untouched = update(chapterId = 3L, read = false)
        rowsFlow.value = listOf(first, second, untouched)
        val firstChapter = chapter(id = 1L)
        val secondChapter = chapter(id = 2L)
        coEvery { getChapter.await(1L) } returns firstChapter
        coEvery { getChapter.await(2L) } returns secondChapter
        coEvery { setReadStatus.await(true, firstChapter, secondChapter) } returns SetReadStatus.Result.Success(listOf(firstChapter, secondChapter))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.state.value.selectionMode shouldBe true

        viewModel.markRead(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { getChapter.await(3L) }
        coVerify(exactly = 1) { setReadStatus.await(true, firstChapter, secondChapter) }
        viewModel.state.value.selectionMode shouldBe false
    }

    @Test
    fun `markRead with nothing selected leaves the collaborators untouched`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markRead(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { getChapter.await(any()) }
    }

    @Test
    fun `markRead reports an error when SetReadStatus fails the batch`() = runTest(testDispatcher) {
        val first = update(chapterId = 1L, read = false)
        rowsFlow.value = listOf(first)
        val firstChapter = chapter(id = 1L)
        coEvery { getChapter.await(1L) } returns firstChapter
        coEvery {
            setReadStatus.await(true, firstChapter)
        } returns SetReadStatus.Result.InternalError(RuntimeException("boom"))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)

        viewModel.events.test {
            viewModel.markRead(true)
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.error_generic)
        }
    }

    @Test
    fun `toggleBookmark bookmarks every selected row and clears the selection`() = runTest(testDispatcher) {
        val first = update(chapterId = 1L, read = false)
        val second = update(chapterId = 2L, read = false)
        rowsFlow.value = listOf(first, second)
        coEvery { toggleBookmark.await(any(), any()) } returns Unit

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.state.value.selectionMode shouldBe true

        viewModel.toggleBookmark(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { toggleBookmark.await(1L, true) }
        coVerify(exactly = 1) { toggleBookmark.await(2L, true) }
        viewModel.state.value.selectionMode shouldBe false
    }

    @Test
    fun `toggleBookmark with nothing selected leaves the collaborator untouched`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleBookmark(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { toggleBookmark.await(any(), any()) }
    }

    @Test
    fun `the updating flag mirrors the scheduler's running state`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        isRunningFlow.value = true
        advanceUntilIdle()

        viewModel.state.value.updating shouldBe true
    }

    @Test
    fun `the update error count reaches state from the error store`() = runTest(testDispatcher) {
        errorsFlow.value = listOf(updateError(mangaId = 1L, title = "Anvil", sourceId = 10L))

        val viewModel = viewModel()
        advanceUntilIdle()

        // The store starts empty in a process that did not run the update, so the read is what
        // fills it: a badge that never calls load() is a badge that is always zero.
        coVerify(exactly = 1) { errorStore.load() }
        viewModel.state.value.updateErrorCount shouldBe 1

        errorsFlow.value = errorsFlow.value + updateError(mangaId = 2L, title = "Bellows", sourceId = 20L)
        advanceUntilIdle()

        viewModel.state.value.updateErrorCount shouldBe 2
    }

    @Test
    fun `showErrors opens the error list grouped by source`() = runTest(testDispatcher) {
        errorsFlow.value = listOf(
            updateError(mangaId = 1L, title = "Zither", sourceId = 20L),
            updateError(mangaId = 2L, title = "Anvil", sourceId = 10L),
            updateError(mangaId = 3L, title = "Bellows", sourceId = 10L),
        )
        every { sourceManager.getOrStub(10L) } returns namedSource("Almanac")
        every { sourceManager.getOrStub(20L) } returns namedSource("Beacon")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showErrors()
        advanceUntilIdle()

        val dialog = viewModel.state.value.dialog as UpdatesViewModel.Dialog.ErrorList
        dialog.groups.map { it.sourceName } shouldContainExactly listOf("Almanac", "Beacon")
        dialog.groups[0].errors.map { it.title } shouldContainExactly listOf("Anvil", "Bellows")
        dialog.groups[1].errors.map { it.title } shouldContainExactly listOf("Zither")
    }

    /**
     * The notice above the list counts the entries a stopped site left unasked, from the same read
     * the groups are built from, so the two never disagree about one run.
     */
    @Test
    fun `showErrors carries how many entries a stopped site left unchecked`() = runTest(testDispatcher) {
        errorsFlow.value = listOf(
            updateError(mangaId = 1L, title = "Anvil", sourceId = 10L, key = ErrorKey.SITE_STOPPED),
            updateError(mangaId = 2L, title = "Bellows", sourceId = 10L, key = ErrorKey.SITE_STOPPED),
            updateError(mangaId = 3L, title = "Zither", sourceId = 20L, key = ErrorKey.HTTP),
        )
        every { sourceManager.getOrStub(10L) } returns namedSource("Almanac")
        every { sourceManager.getOrStub(20L) } returns namedSource("Beacon")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showErrors()
        advanceUntilIdle()

        val dialog = viewModel.state.value.dialog as UpdatesViewModel.Dialog.ErrorList
        dialog.stoppedEntries shouldBe 2
        dialog.groups.sumOf { it.errors.size } shouldBe 3
    }

    @Test
    fun `showErrors carries no stopped entries when no site was stopped`() = runTest(testDispatcher) {
        errorsFlow.value = listOf(updateError(mangaId = 1L, title = "Anvil", sourceId = 10L))
        every { sourceManager.getOrStub(10L) } returns namedSource("Almanac")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showErrors()
        advanceUntilIdle()

        val dialog = viewModel.state.value.dialog as UpdatesViewModel.Dialog.ErrorList
        dialog.stoppedEntries shouldBe 0
    }

    /**
     * The cold start the error notification causes: the process that ran the update is gone, the
     * store is empty in memory, and the dialog is asked for on the first frame, before the `init`
     * load has finished reading the file. The load is slowed here so the race is the one a device
     * gets rather than one the test scheduler happens to order in the dialog's favour.
     */
    @Test
    fun `showErrors reads the stored file even when it is asked for before the first load lands`() = runTest(testDispatcher) {
        val stored = listOf(
            updateError(mangaId = 1L, title = "Anvil", sourceId = 10L),
            updateError(mangaId = 2L, title = "Bellows", sourceId = 10L),
        )
        coEvery { errorStore.load() } coAnswers {
            delay(100)
            errorsFlow.value = stored
        }
        every { sourceManager.getOrStub(10L) } returns namedSource("Almanac")

        val viewModel = viewModel()
        viewModel.showErrors()
        advanceUntilIdle()

        val dialog = viewModel.state.value.dialog as UpdatesViewModel.Dialog.ErrorList
        dialog.groups.map { it.sourceName } shouldContainExactly listOf("Almanac")
        dialog.groups.single().errors.map { it.title } shouldContainExactly listOf("Anvil", "Bellows")
    }

    @Test
    fun `dismissDialog closes the error list`() = runTest(testDispatcher) {
        errorsFlow.value = listOf(updateError(mangaId = 1L, title = "Anvil", sourceId = 10L))
        every { sourceManager.getOrStub(10L) } returns namedSource("Almanac")
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.showErrors()
        advanceUntilIdle()
        viewModel.state.value.dialog.shouldBeInstanceOf<UpdatesViewModel.Dialog.ErrorList>()

        viewModel.dismissDialog()
        advanceUntilIdle()

        viewModel.state.value.dialog shouldBe null
    }

    // ---- The stored filters (phase 4 unit UF) ----

    private fun category(id: Long) = Category(id = id, name = "Category $id", order = id, flags = 0L)

    @Test
    fun `the unread filter goes into the query and its rows reach state`() = runTest(testDispatcher) {
        val unreadRow = update(chapterId = 1L)
        val readRow = update(chapterId = 2L, read = true)
        rowsByQuery[Query(unread = true)] = listOf(unreadRow)
        rowsByQuery[Query(unread = false)] = listOf(readRow)
        val viewModel = viewModel()
        advanceUntilIdle()

        updatesPreferences.filterUnread.set(TriState.ENABLED_IS)
        advanceUntilIdle()
        viewModel.state.value.items shouldContainExactly listOf(unreadRow)

        updatesPreferences.filterUnread.set(TriState.ENABLED_NOT)
        advanceUntilIdle()
        viewModel.state.value.items shouldContainExactly listOf(readRow)
    }

    @Test
    fun `the started filter goes into the query and its rows reach state`() = runTest(testDispatcher) {
        rowsByQuery[Query(started = true)] = listOf(update(chapterId = 1L))
        rowsByQuery[Query(started = false)] = listOf(update(chapterId = 2L))
        val viewModel = viewModel()
        advanceUntilIdle()

        updatesPreferences.filterStarted.set(TriState.ENABLED_IS)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(1L)

        updatesPreferences.filterStarted.set(TriState.ENABLED_NOT)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(2L)
    }

    @Test
    fun `the bookmarked filter goes into the query and its rows reach state`() = runTest(testDispatcher) {
        rowsByQuery[Query(bookmarked = true)] = listOf(update(chapterId = 1L))
        rowsByQuery[Query(bookmarked = false)] = listOf(update(chapterId = 2L))
        val viewModel = viewModel()
        advanceUntilIdle()

        updatesPreferences.filterBookmarked.set(TriState.ENABLED_IS)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(1L)

        updatesPreferences.filterBookmarked.set(TriState.ENABLED_NOT)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(2L)
    }

    /** The chapter settings unit's carry-over 5: the Updates list now hides excluded scanlators when asked. */
    @Test
    fun `the excluded scanlators switch goes into the query and its rows reach state`() = runTest(testDispatcher) {
        rowsFlow.value = listOf(update(chapterId = 1L), update(chapterId = 2L))
        rowsByQuery[Query(hideExcludedScanlators = true)] = listOf(update(chapterId = 1L))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleExcludedScanlators()
        advanceUntilIdle()

        updatesPreferences.filterExcludedScanlators.get() shouldBe true
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(1L)
    }

    @Test
    fun `the category lists go into the query and their rows reach state`() = runTest(testDispatcher) {
        rowsByQuery[Query(includedCategories = listOf(3L))] = listOf(update(chapterId = 1L))
        rowsByQuery[Query(includedCategories = listOf(3L), excludedCategories = listOf(4L))] =
            listOf(update(chapterId = 2L))
        val viewModel = viewModel()
        advanceUntilIdle()

        updatesPreferences.filterIncludedCategories.set(listOf(3L))
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(1L)

        updatesPreferences.filterExcludedCategories.set(listOf(4L))
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(2L)
    }

    @Test
    fun `filters stored before the tab opens are read on the first query`() = runTest(testDispatcher) {
        updatesPreferences.filterUnread.set(TriState.ENABLED_IS)
        updatesPreferences.filterExcludedScanlators.set(true)
        rowsByQuery[Query(unread = true, hideExcludedScanlators = true)] = listOf(update(chapterId = 1L))

        val viewModel = viewModel()
        advanceUntilIdle()

        queries.first() shouldBe Query(unread = true, hideExcludedScanlators = true)
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(1L)
    }

    /**
     * Upstream's window (R107 carry-over 2, R112 carry-over 1): the query reads chapters uploaded after
     * now less three months in the device's zone, read once when the view model starts. A later
     * query, run for a filter change after the clock moved on, keeps the first window. Which rows
     * that lets in, one just inside and one just outside, is `RepositoryTest`'s, over the real query.
     */
    @Test
    fun `every query reads the three months before the view model started`() = runTest(testDispatcher) {
        val defaultZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("UTC"))
        try {
            val viewModel = viewModel()
            advanceUntilIdle()
            now += 40.days
            viewModel.setFilter(UpdatesViewModel.UpdatesFilter.UNREAD, TriState.ENABLED_IS)
            advanceUntilIdle()

            queries shouldContainExactly listOf(Query(), Query(unread = true))
            instants shouldContainExactly List(2) { Instant.parse("2026-06-30T12:00:00Z") }
        } finally {
            JavaTimeZone.setDefault(defaultZone)
        }
    }

    /**
     * Upstream applies the Downloaded filter after its query, from download state; here the state is
     * the downloaded chapter ids, and the query is never asked about it.
     */
    @Test
    fun `the downloaded filter narrows the rows from the downloaded ids, not through the query`() = runTest(testDispatcher) {
        rowsFlow.value = listOf(update(chapterId = 1L), update(chapterId = 2L), update(chapterId = 3L))
        downloadedFlow.value = setOf(2L)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFilter(UpdatesViewModel.UpdatesFilter.DOWNLOADED, TriState.ENABLED_IS)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(2L)

        downloadedFlow.value = setOf(1L, 2L)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(1L, 2L)

        viewModel.setFilter(UpdatesViewModel.UpdatesFilter.DOWNLOADED, TriState.ENABLED_NOT)
        advanceUntilIdle()
        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(3L)

        queries.distinct() shouldContainExactly listOf(Query())
    }

    @Test
    fun `the downloaded ids are not read while the downloaded filter is off`() = runTest(testDispatcher) {
        rowsFlow.value = listOf(update(chapterId = 1L))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.items.map { it.chapterId } shouldContainExactly listOf(1L)
        verify(exactly = 0) { downloadedChapters.subscribeAll() }
    }

    @Test
    fun `the filter icon is lit by the four tri-states and not by the switch or the categories`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.hasActiveFilters shouldBe false

        updatesPreferences.filterExcludedScanlators.set(true)
        updatesPreferences.filterIncludedCategories.set(listOf(3L))
        updatesPreferences.filterExcludedCategories.set(listOf(4L))
        advanceUntilIdle()
        viewModel.state.value.hasActiveFilters shouldBe false

        UpdatesViewModel.UpdatesFilter.entries.forEach { filter ->
            viewModel.setFilter(filter, TriState.ENABLED_NOT)
            advanceUntilIdle()
            viewModel.state.value.hasActiveFilters shouldBe true

            viewModel.setFilter(filter, TriState.DISABLED)
            advanceUntilIdle()
            viewModel.state.value.hasActiveFilters shouldBe false
        }
    }

    @Test
    fun `setFilter writes each tri-state row to its own key`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFilter(UpdatesViewModel.UpdatesFilter.DOWNLOADED, TriState.ENABLED_IS)
        viewModel.setFilter(UpdatesViewModel.UpdatesFilter.UNREAD, TriState.ENABLED_NOT)
        viewModel.setFilter(UpdatesViewModel.UpdatesFilter.STARTED, TriState.ENABLED_IS)
        viewModel.setFilter(UpdatesViewModel.UpdatesFilter.BOOKMARKED, TriState.ENABLED_NOT)
        advanceUntilIdle()

        updatesPreferences.filterDownloaded.get() shouldBe TriState.ENABLED_IS
        updatesPreferences.filterUnread.get() shouldBe TriState.ENABLED_NOT
        updatesPreferences.filterStarted.get() shouldBe TriState.ENABLED_IS
        updatesPreferences.filterBookmarked.get() shouldBe TriState.ENABLED_NOT
        viewModel.state.value.filters shouldBe UpdatesViewModel.Filters(
            downloaded = TriState.ENABLED_IS,
            unread = TriState.ENABLED_NOT,
            started = TriState.ENABLED_IS,
            bookmarked = TriState.ENABLED_NOT,
        )
    }

    /** Upstream's `UpdatesSettingsViewModel.cycleCategory`: neither, included, excluded, neither. */
    @Test
    fun `a category row moves its id from neither to included to excluded and back`() = runTest(testDispatcher) {
        categoriesFlow.value = listOf(category(0L), category(3L))
        updatesPreferences.filterIncludedCategories.set(listOf(5L))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.categories.map { it.id } shouldContainExactly listOf(0L, 3L)

        viewModel.cycleCategory(3L)
        advanceUntilIdle()
        updatesPreferences.filterIncludedCategories.get() shouldContainExactly listOf(5L, 3L)
        updatesPreferences.filterExcludedCategories.get() shouldBe emptyList()
        viewModel.state.value.filters.categoryState(3L) shouldBe TriState.ENABLED_IS

        viewModel.cycleCategory(3L)
        advanceUntilIdle()
        updatesPreferences.filterIncludedCategories.get() shouldContainExactly listOf(5L)
        updatesPreferences.filterExcludedCategories.get() shouldContainExactly listOf(3L)
        viewModel.state.value.filters.categoryState(3L) shouldBe TriState.ENABLED_NOT

        viewModel.cycleCategory(3L)
        advanceUntilIdle()
        updatesPreferences.filterIncludedCategories.get() shouldContainExactly listOf(5L)
        updatesPreferences.filterExcludedCategories.get() shouldBe emptyList()
        viewModel.state.value.filters.categoryState(3L) shouldBe TriState.DISABLED
    }

    @Test
    fun `an id a restore left in both lists clears in two taps`() = runTest(testDispatcher) {
        updatesPreferences.filterIncludedCategories.set(listOf(3L))
        updatesPreferences.filterExcludedCategories.set(listOf(3L))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.cycleCategory(3L)
        viewModel.cycleCategory(3L)
        advanceUntilIdle()

        updatesPreferences.filterIncludedCategories.get() shouldBe emptyList()
        updatesPreferences.filterExcludedCategories.get() shouldBe emptyList()
    }

    @Test
    fun `the filter icon opens the filter dialog and dismissing closes it`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showFilterDialog()
        viewModel.state.value.dialog shouldBe UpdatesViewModel.Dialog.Filter

        viewModel.dismissDialog()
        viewModel.state.value.dialog shouldBe null
    }

    /** Rows of one entry fetched at one instant, so they fold together (phase 4 unit UG). */
    private fun entryRows(mangaId: Long, vararg chapterIds: Long) =
        chapterIds.map { update(chapterId = it).copy(mangaId = mangaId, dateFetch = 1_000_000L) }

    private fun UpdatesViewModel.folds() =
        state.value.uiModels(kotlinx.datetime.TimeZone.UTC).filterIsInstance<UpdatesUiModel.Fold>()

    @Test
    fun `a long press on a collapsed fold selects every chapter and mark as read reaches the hidden ones`() =
        runTest(testDispatcher) {
            rowsFlow.value = entryRows(mangaId = 1L, 1L, 2L, 3L, 4L) + entryRows(mangaId = 2L, 5L)
            val chapters = (1L..4L).map { chapter(id = it) }
            chapters.forEach { coEvery { getChapter.await(it.id) } returns it }
            coEvery { setReadStatus.await(true, *chapters.toTypedArray()) } returns SetReadStatus.Result.Success(chapters)
            val viewModel = viewModel()
            advanceUntilIdle()
            val fold = viewModel.folds().single()
            fold.expanded shouldBe false

            viewModel.longPressFold(fold)

            viewModel.state.value.selection shouldBe setOf(1L, 2L, 3L, 4L)
            viewModel.markRead(true)
            advanceUntilIdle()
            coVerify(exactly = 1) { setReadStatus.await(true, *chapters.toTypedArray()) }
            coVerify(exactly = 0) { getChapter.await(5L) }
            viewModel.state.value.selectionMode shouldBe false
        }

    @Test
    fun `a long press on a fold whose chapters are all selected leaves them all`() = runTest(testDispatcher) {
        rowsFlow.value = entryRows(mangaId = 1L, 1L, 2L) + entryRows(mangaId = 2L, 3L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(3L)
        viewModel.toggleSelection(1L)
        val fold = viewModel.folds().single()

        // One of its two chapters is selected: the press selects the other.
        viewModel.longPressFold(fold)
        viewModel.state.value.selection shouldBe setOf(1L, 2L, 3L)

        viewModel.longPressFold(fold)
        viewModel.state.value.selection shouldBe setOf(3L)
    }

    private fun rowIds(vararg ids: Long) = ids.flatMap { entryRows(mangaId = it + 100L, it) }

    @Test
    fun `a long press on a row after a selected row selects every row between in list order`() = runTest(testDispatcher) {
        rowsFlow.value = rowIds(1L, 2L, 3L, 4L, 5L, 6L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(2L)

        viewModel.longPressChapter(5L)

        viewModel.state.value.selection shouldBe setOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun `a long press on a row before the selection fills upward to it`() = runTest(testDispatcher) {
        rowsFlow.value = rowIds(1L, 2L, 3L, 4L, 5L, 6L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(5L)

        viewModel.longPressChapter(2L)

        viewModel.state.value.selection shouldBe setOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun `a range follows the order the list shows, not id order`() = runTest(testDispatcher) {
        rowsFlow.value = rowIds(30L, 20L, 10L, 50L, 40L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(30L)

        viewModel.longPressChapter(50L)

        viewModel.state.value.selection shouldBe setOf(30L, 20L, 10L, 50L)
    }

    @Test
    fun `a long press on a selected row deselects only it`() = runTest(testDispatcher) {
        rowsFlow.value = rowIds(1L, 2L, 3L, 4L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.toggleSelection(3L)

        viewModel.longPressChapter(2L)

        viewModel.state.value.selection shouldBe setOf(1L, 3L)
    }

    @Test
    fun `a range from a row above a closed fold to a row below it takes the fold's hidden chapters`() =
        runTest(testDispatcher) {
            rowsFlow.value = entryRows(mangaId = 1L, 1L) + entryRows(mangaId = 2L, 2L, 3L, 4L, 5L) +
                entryRows(mangaId = 3L, 6L)
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.folds().single().expanded shouldBe false
            viewModel.toggleSelection(1L)

            viewModel.longPressChapter(6L)

            viewModel.state.value.selection shouldBe setOf(1L, 2L, 3L, 4L, 5L, 6L)
        }

    @Test
    fun `a long press on a closed fold below a selected row selects the gap and all the fold's chapters`() =
        runTest(testDispatcher) {
            rowsFlow.value = entryRows(mangaId = 1L, 1L) + entryRows(mangaId = 2L, 2L) +
                entryRows(mangaId = 3L, 3L, 4L, 5L, 6L)
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            val fold = viewModel.folds().single()
            fold.expanded shouldBe false

            viewModel.longPressFold(fold)

            viewModel.state.value.selection shouldBe setOf(1L, 2L, 3L, 4L, 5L, 6L)
        }

    @Test
    fun `a long press on a fold that overlaps the selection selects only the fold`() = runTest(testDispatcher) {
        rowsFlow.value = entryRows(mangaId = 1L, 1L) + entryRows(mangaId = 2L, 2L, 3L, 4L, 5L) +
            entryRows(mangaId = 3L, 6L) + entryRows(mangaId = 4L, 7L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(4L)
        viewModel.toggleSelection(7L)

        viewModel.longPressFold(viewModel.folds().single())

        viewModel.state.value.selection shouldBe setOf(2L, 3L, 4L, 5L, 7L)
    }

    @Test
    fun `mark as read after a range select reaches exactly the selected chapters`() = runTest(testDispatcher) {
        rowsFlow.value = rowIds(1L, 2L, 3L, 4L, 5L)
        val chapters = (2L..4L).map { chapter(id = it) }
        chapters.forEach { coEvery { getChapter.await(it.id) } returns it }
        val marked = mutableListOf<Long>()
        coEvery { setReadStatus.await(true, *anyVararg()) } answers {
            val reached = (args[1] as Array<*>).filterIsInstance<Chapter>()
            marked += reached.map { it.id }
            SetReadStatus.Result.Success(reached)
        }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(2L)
        viewModel.longPressChapter(4L)

        viewModel.markRead(true)
        advanceUntilIdle()

        marked.sorted() shouldBe listOf(2L, 3L, 4L)
        coVerify(exactly = 0) { getChapter.await(1L) }
        coVerify(exactly = 0) { getChapter.await(5L) }
        viewModel.state.value.selectionMode shouldBe false
    }

    @Test
    fun `one chapter inside an expanded fold is selected as a plain row is`() = runTest(testDispatcher) {
        rowsFlow.value = entryRows(mangaId = 1L, 1L, 2L, 3L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.folds().single().expanded shouldBe true

        viewModel.toggleSelection(2L)

        viewModel.state.value.selection shouldBe setOf(2L)
        viewModel.state.value.selection.containsAll(viewModel.folds().single().chapterIds) shouldBe false
    }

    @Test
    fun `a tap on a fold opens a collapsed one and closes it again`() = runTest(testDispatcher) {
        rowsFlow.value = entryRows(mangaId = 1L, 1L, 2L, 3L, 4L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.uiModels(kotlinx.datetime.TimeZone.UTC).size shouldBe 2

        viewModel.toggleFold(viewModel.folds().single())
        viewModel.folds().single().expanded shouldBe true
        viewModel.state.value.uiModels(kotlinx.datetime.TimeZone.UTC)
            .filterIsInstance<UpdatesUiModel.Item>().map { it.update.chapterId } shouldBe listOf(1L, 2L, 3L, 4L)

        viewModel.toggleFold(viewModel.folds().single())
        viewModel.folds().single().expanded shouldBe false
    }

    @Test
    fun `an opened fold stays open when the query emits again`() = runTest(testDispatcher) {
        rowsFlow.value = entryRows(mangaId = 1L, 1L, 2L, 3L, 4L, 5L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleFold(viewModel.folds().single())

        // Marking a chapter read with the unread filter on takes it off the list.
        rowsFlow.value = entryRows(mangaId = 1L, 1L, 2L, 3L, 4L)
        advanceUntilIdle()

        viewModel.folds().single().expanded shouldBe true
    }

    @Test
    fun `an opened fold stays open when its top row leaves the list`() = runTest(testDispatcher) {
        rowsFlow.value = entryRows(mangaId = 1L, 1L, 2L, 3L, 4L, 5L)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleFold(viewModel.folds().single())

        // The fold's first row is marked read under the Unread filter.
        rowsFlow.value = entryRows(mangaId = 1L, 2L, 3L, 4L, 5L)
        advanceUntilIdle()

        viewModel.folds().single().expanded shouldBe true
    }

    @Test
    fun `an opened fold keeps its chapters when a newer run of the entry lands above it`() = runTest(testDispatcher) {
        // Entry 10 fetched four chapters at 09:00 UTC; the owner opens them.
        val hour = 3_600_000L
        val morning = (4L downTo 1L).map { update(chapterId = it).copy(mangaId = 10L, dateFetch = 9 * hour) }
        rowsFlow.value = morning
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.folds().single().expanded shouldBe false
        viewModel.toggleFold(viewModel.folds().single())

        // At 12:00 entry 20 gets a chapter, at 18:00 entry 10 four more.
        rowsFlow.value = (8L downTo 5L).map { update(chapterId = it).copy(mangaId = 10L, dateFetch = 18 * hour) } +
            update(chapterId = 9L).copy(mangaId = 20L, dateFetch = 12 * hour) +
            morning
        advanceUntilIdle()

        viewModel.folds().map { it.chapterIds to it.expanded } shouldBe listOf(
            listOf(8L, 7L, 6L, 5L) to false,
            listOf(4L, 3L, 2L, 1L) to true,
        )
    }

    @Test
    fun `relative timestamps and the date format reach state as they change`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.relativeTime shouldBe true
        viewModel.state.value.dateFormat shouldBe ""

        uiPreferences.relativeTime.set(false)
        uiPreferences.dateFormat.set("yyyy-MM-dd")
        advanceUntilIdle()

        viewModel.state.value.relativeTime shouldBe false
        viewModel.state.value.dateFormat shouldBe "yyyy-MM-dd"
    }

    // ---- The rows' download control (plan 2.6, unit ID3) ----

    private fun queueRow(chapterId: Long, state: DownloadState, pages: Long = 0, total: Long = 0) = DownloadQueueEntry(
        id = chapterId,
        chapterId = chapterId,
        mangaId = 1L,
        state = state,
        queueOrder = chapterId,
        totalPages = total,
        downloadedPages = pages,
        errorMessage = null,
        dateAdded = 0L,
        dateStarted = null,
        dateCompleted = null,
    )

    /**
     * The controls read the shown chapters only: the downloaded answer is asked about those ids,
     * a Local row left out, and a queue row of a chapter not shown is not kept.
     */
    @Test
    fun `the rows' download states follow the shown chapters only`() = runTest(testDispatcher) {
        val local = update(chapterId = 1L).copy(sourceId = SourceIds.LOCAL)
        val onDisk = update(chapterId = 2L)
        val downloading = update(chapterId = 3L)
        val plain = update(chapterId = 4L)
        rowsFlow.value = listOf(local, onDisk, downloading, plain)
        shownDownloadedFlow.value = setOf(2L)
        queueFlow.value = listOf(
            queueRow(3L, DownloadState.DOWNLOADING, pages = 2, total = 9),
            queueRow(99L, DownloadState.QUEUED),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        askedIds.last() shouldBe setOf(2L, 3L, 4L)
        state.queued.keys shouldBe setOf(3L)
        listOf(local, onDisk, downloading, plain).map(state::downloadOf) shouldContainExactly listOf(
            ChapterDownload.None,
            ChapterDownload.Downloaded,
            ChapterDownload.Downloading(pages = 2, total = 9),
            ChapterDownload.None,
        )
        state.showsDownloadControl(local) shouldBe false
        state.showsDownloadControl(plain) shouldBe true

        // A new row asks again, for the new set.
        rowsFlow.value = listOf(update(chapterId = 5L)) + rowsFlow.value
        advanceUntilIdle()
        askedIds.last() shouldBe setOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun `the control's download loads the entry and the chapter and queues it with the queue started`() =
        runTest(testDispatcher) {
            val row = update(chapterId = 5L)
            rowsFlow.value = listOf(row)
            val manga = Manga.create().copy(id = 1L)
            coEvery { getManga.await(1L) } returns manga
            coEvery { getChapter.await(5L) } returns chapter(id = 5L)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.runChapterDownloadAction(row, ChapterDownloadAction.START)
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadQueue.enqueue(manga, listOf(chapter(id = 5L)), autoStart = true) }
            coVerify(exactly = 0) { downloadQueue.reorder(any()) }
        }

    @Test
    fun `the control's start now queues the chapter at the front and starts the queue`() = runTest(testDispatcher) {
        val row = update(chapterId = 5L)
        rowsFlow.value = listOf(row)
        val manga = Manga.create().copy(id = 1L)
        coEvery { getManga.await(1L) } returns manga
        coEvery { getChapter.await(5L) } returns chapter(id = 5L)
        queueFlow.value = listOf(queueRow(7L, DownloadState.QUEUED), queueRow(5L, DownloadState.ERROR))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.runChapterDownloadAction(row, ChapterDownloadAction.START_NOW)
        advanceUntilIdle()

        io.mockk.coVerifyOrder {
            downloadQueue.enqueue(manga, listOf(chapter(id = 5L)), autoStart = false)
            downloadQueue.reorder(listOf(5L, 7L))
            downloadQueue.start()
        }
    }

    @Test
    fun `the control's cancel removes the queue row and its delete removes the files`() = runTest(testDispatcher) {
        val queued = update(chapterId = 5L)
        val onDisk = update(chapterId = 6L)
        rowsFlow.value = listOf(queued, onDisk)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.runChapterDownloadAction(queued, ChapterDownloadAction.CANCEL)
        viewModel.runChapterDownloadAction(onDisk, ChapterDownloadAction.DELETE)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadQueue.remove(listOf(5L)) }
        coVerify(exactly = 1) { downloadManifest.delete(listOf(6L)) }
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `a download for a chapter the database no longer has says so and queues nothing`() = runTest(testDispatcher) {
        val row = update(chapterId = 5L)
        rowsFlow.value = listOf(row)
        coEvery { getManga.await(1L) } returns Manga.create().copy(id = 1L)
        coEvery { getChapter.await(5L) } returns null
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.runChapterDownloadAction(row, ChapterDownloadAction.START)
            advanceUntilIdle()

            awaitItem() shouldBe UpdatesViewModel.Event.Snackbar(R.string.error_generic)
        }
        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
    }

    /** The control is off while rows are selected and on a Local row; a call that arrives anyway does nothing. */
    @Test
    fun `the control does nothing while rows are selected or on a Local row`() = runTest(testDispatcher) {
        val local = update(chapterId = 1L).copy(sourceId = SourceIds.LOCAL)
        val row = update(chapterId = 2L)
        rowsFlow.value = listOf(local, row)
        val viewModel = viewModel()
        advanceUntilIdle()
        // Before state: the rows are there and the plain row draws its control.
        viewModel.state.value.showsDownloadControl(row) shouldBe true

        viewModel.toggleSelection(2L)
        viewModel.state.value.showsDownloadControl(row) shouldBe false
        for (action in ChapterDownloadAction.entries) {
            viewModel.runChapterDownloadAction(row, action)
            viewModel.runChapterDownloadAction(local, action)
        }
        viewModel.clearSelection()
        for (action in ChapterDownloadAction.entries) viewModel.runChapterDownloadAction(local, action)
        advanceUntilIdle()

        coVerify(exactly = 0) { downloadQueue.enqueue(any(), any(), any()) }
        coVerify(exactly = 0) { downloadQueue.remove(any()) }
        coVerify(exactly = 0) { downloadManifest.delete(any()) }
    }
}
