package com.shinsaikou.ui.history

import app.cash.turbine.test
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.i18n.R
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.MangaCover
import java.io.File
import java.util.Date
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val historyRepository = mockk<HistoryRepository>(relaxed = true)
    private val getNextChapters = mockk<GetNextChapters>()
    private val getScanlatorPriority = mockk<GetScanlatorPriority> {
        coEvery { await(any()) } returns emptyMap()
    }
    private val uiPreferences = UiPreferences(InMemoryPreferenceStore())
    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())

    /** Loaded already unless a test says otherwise, as it is once the app has started. */
    private val initializedFlow = MutableStateFlow(true)
    private val extensionManager = mockk<ExtensionManager> {
        every { installedExtensions } returns installedFlow
        every { isInitialized } returns initializedFlow
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun historyRow(
        id: Long = 1L,
        title: String = "Fixture Manga",
        mangaId: Long = 100L,
        chapterId: Long = 10L,
        sourceId: Long = 1L,
    ) = HistoryWithRelations(
        id = id,
        chapterId = chapterId,
        mangaId = mangaId,
        title = title,
        displayTitle = title,
        chapterNumber = 1.0,
        readAt = null,
        readDuration = 0L,
        coverData = MangaCover(
            mangaId = 100L,
            sourceId = sourceId,
            isMangaFavorite = false,
            url = null,
            lastModified = 0L,
        ),
    )

    private fun viewModel() = HistoryViewModel(
        getHistory = GetHistory(historyRepository),
        removeHistory = RemoveHistory(historyRepository),
        getNextChapters = getNextChapters,
        getScanlatorPriority = getScanlatorPriority,
        uiPreferences = uiPreferences,
        extensionManager = extensionManager,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    @Test
    fun `the history flow reaches state`() = runTest(testDispatcher) {
        val row = historyRow()
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(row))

        val vm = viewModel()
        advanceUntilIdle()

        vm.state.value.loading shouldBe false
        vm.state.value.items shouldContainExactly listOf(row)
    }

    @Test
    fun `the day headers follow the date preferences`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())

        val vm = viewModel()
        advanceUntilIdle()
        vm.state.value.relativeTime shouldBe true
        vm.state.value.dateFormat shouldBe ""

        uiPreferences.relativeTime.set(false)
        uiPreferences.dateFormat.set("yyyy-MM-dd")
        advanceUntilIdle()

        vm.state.value.relativeTime shouldBe false
        vm.state.value.dateFormat shouldBe "yyyy-MM-dd"
    }

    @Test
    fun `search feeds the query through in lower case`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())

        val vm = viewModel()
        advanceUntilIdle()

        vm.search("Some Title")
        advanceUntilIdle()

        // The field keeps what was typed, the query that reaches the database is lower cased.
        vm.state.value.query shouldBe "Some Title"
        verify { historyRepository.getHistory("some title") }
        verify(exactly = 0) { historyRepository.getHistory("Some Title") }
    }

    @Test
    fun `a row's delete asks first and removes nothing yet`() = runTest(testDispatcher) {
        val row = historyRow(id = 42L)
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(row))

        val vm = viewModel()
        advanceUntilIdle()

        vm.askRemove(row)
        advanceUntilIdle()

        vm.state.value.dialog shouldBe HistoryViewModel.Dialog.Remove(row)
        coVerify(exactly = 0) { historyRepository.resetHistory(any()) }
        coVerify(exactly = 0) { historyRepository.resetHistoryByMangaId(any()) }
    }

    @Test
    fun `remove takes the whole entry's history, not the one chapter the row shows`() = runTest(testDispatcher) {
        // The list shows one row per entry; resetting only its chapter would bring the entry back
        // at once with the chapter read before it.
        val row = historyRow(id = 42L, mangaId = 7L)
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(row))

        val vm = viewModel()
        advanceUntilIdle()
        vm.askRemove(row)

        vm.remove(row)
        advanceUntilIdle()

        coVerify(exactly = 1) { historyRepository.resetHistoryByMangaId(7L) }
        coVerify(exactly = 0) { historyRepository.resetHistory(any()) }
        vm.state.value.dialog shouldBe null
    }

    @Test
    fun `the remove confirm closes when its entry leaves the list`() = runTest(testDispatcher) {
        val row = historyRow(id = 42L, mangaId = 7L)
        val historyFlow = MutableStateFlow(listOf(row))
        every { historyRepository.getHistory(any()) } returns historyFlow

        val vm = viewModel()
        advanceUntilIdle()
        vm.askRemove(row)

        historyFlow.value = emptyList()
        advanceUntilIdle()

        vm.state.value.dialog shouldBe null
    }

    private fun chapter(id: Long, number: Double, read: Boolean, scanlator: String? = null) =
        Chapter.create().copy(id = id, mangaId = 100L, chapterNumber = number, read = read, scanlator = scanlator)

    @Test
    fun `resume opens an unfinished chapter itself`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())
        coEvery { getNextChapters.await(100L, false) } returns listOf(
            chapter(10L, 1.0, read = false),
            chapter(11L, 2.0, read = false),
        )

        val vm = viewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.resume(historyRow(chapterId = 10L))
            awaitItem() shouldBe HistoryViewModel.Event.OpenReader(100L, 10L)
        }
    }

    @Test
    fun `resume opens the chapter after a finished one`() = runTest(testDispatcher) {
        // A read chapter opens at its first page, so resuming it would replay what was finished.
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())
        coEvery { getNextChapters.await(100L, false) } returns listOf(
            chapter(9L, 0.0, read = true),
            chapter(10L, 1.0, read = true),
            chapter(11L, 2.0, read = true),
            chapter(12L, 3.0, read = false),
        )

        val vm = viewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.resume(historyRow(chapterId = 10L))
            awaitItem() shouldBe HistoryViewModel.Event.OpenReader(100L, 11L)
        }
    }

    @Test
    fun `resume skips another group's copy of the finished chapter`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())
        coEvery { getNextChapters.await(100L, false) } returns listOf(
            chapter(10L, 1.0, read = true, scanlator = "Group A"),
            chapter(20L, 1.0, read = false, scanlator = "Group B"),
            chapter(11L, 2.0, read = false, scanlator = "Group A"),
        )

        val vm = viewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.resume(historyRow(chapterId = 10L))
            awaitItem() shouldBe HistoryViewModel.Event.OpenReader(100L, 11L)
        }
    }

    @Test
    fun `resume after the last chapter says there is no next one`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())
        coEvery { getNextChapters.await(100L, false) } returns listOf(
            chapter(9L, 0.0, read = true),
            chapter(10L, 1.0, read = true),
        )

        val vm = viewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.resume(historyRow(chapterId = 10L))
            awaitItem() shouldBe HistoryViewModel.Event.Snackbar(R.string.history_no_next_chapter)
        }
    }

    @Test
    fun `resume opens a chapter the list no longer holds as before`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())
        coEvery { getNextChapters.await(100L, false) } returns listOf(chapter(11L, 2.0, read = false))

        val vm = viewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.resume(historyRow(chapterId = 10L))
            awaitItem() shouldBe HistoryViewModel.Event.OpenReader(100L, 10L)
        }
    }

    @Test
    fun `askClearAll opens the clear all dialog`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())

        val vm = viewModel()
        advanceUntilIdle()

        vm.askClearAll()

        vm.state.value.dialog shouldBe HistoryViewModel.Dialog.ClearAll
    }

    @Test
    fun `dismissDialog closes the clear all dialog`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())

        val vm = viewModel()
        advanceUntilIdle()

        vm.askClearAll()
        vm.dismissDialog()

        vm.state.value.dialog shouldBe null
    }

    @Test
    fun `clearAll removes everything and dismisses the dialog`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())

        val vm = viewModel()
        advanceUntilIdle()

        vm.askClearAll()
        vm.clearAll()
        advanceUntilIdle()

        coVerify { historyRepository.deleteAllHistory() }
        vm.state.value.dialog shouldBe null
    }

    @Test
    fun `clearAll emits its snackbar`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())

        val vm = viewModel()
        advanceUntilIdle()

        vm.events.test {
            vm.clearAll()
            awaitItem() shouldBe HistoryViewModel.Event.Snackbar(R.string.history_cleared)
        }
    }

    @Test
    fun `toggleSelection turns selection mode on and back off`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(historyRow(id = 1L)))

        val vm = viewModel()
        advanceUntilIdle()

        vm.state.value.selectionMode shouldBe false

        vm.toggleSelection(1L)
        vm.state.value.selectionMode shouldBe true
        vm.state.value.selection shouldBe setOf(1L)

        // A second toggle of the same id deselects it, leaving selection mode again.
        vm.toggleSelection(1L)
        vm.state.value.selectionMode shouldBe false
        vm.state.value.selection shouldBe emptySet()
    }

    @Test
    fun `selectAll selects every visible row and clearSelection empties it`() = runTest(testDispatcher) {
        val rows = listOf(historyRow(id = 1L), historyRow(id = 2L), historyRow(id = 3L))
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()

        vm.selectAll()
        vm.state.value.selection shouldBe setOf(1L, 2L, 3L)
        vm.state.value.allSelected shouldBe true

        vm.clearSelection()
        vm.state.value.selection shouldBe emptySet()
        vm.state.value.allSelected shouldBe false
    }

    @Test
    fun `allSelected is false while the selection is a strict subset of the visible rows`() = runTest(testDispatcher) {
        val rows = listOf(historyRow(id = 1L), historyRow(id = 2L))
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()

        vm.toggleSelection(1L)

        vm.state.value.allSelected shouldBe false
    }

    @Test
    fun `a row leaving the visible list prunes it out of the selection`() = runTest(testDispatcher) {
        val historyFlow = MutableStateFlow(listOf(historyRow(id = 1L), historyRow(id = 2L)))
        every { historyRepository.getHistory(any()) } returns historyFlow

        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleSelection(1L)
        vm.toggleSelection(2L)
        vm.state.value.selection shouldBe setOf(1L, 2L)

        // Row 2 disappears from the feed, as it would after a removal from elsewhere or a search
        // narrowing the results; its id must not linger in the selection the app bar counts.
        historyFlow.value = listOf(historyRow(id = 1L))
        advanceUntilIdle()

        vm.state.value.selection shouldBe setOf(1L)
    }

    @Test
    fun `pruning the last selected row closes an open remove confirmation`() = runTest(testDispatcher) {
        val historyFlow = MutableStateFlow(listOf(historyRow(id = 1L)))
        every { historyRepository.getHistory(any()) } returns historyFlow

        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleSelection(1L)
        vm.askRemoveSelected()
        vm.state.value.dialog shouldBe HistoryViewModel.Dialog.RemoveSelected(1)

        historyFlow.value = emptyList()
        advanceUntilIdle()

        vm.state.value.selection shouldBe emptySet()
        vm.state.value.dialog shouldBe null
    }

    @Test
    fun `askRemoveSelected does nothing with an empty selection`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(historyRow(id = 1L)))

        val vm = viewModel()
        advanceUntilIdle()

        vm.askRemoveSelected()

        vm.state.value.dialog shouldBe null
    }

    @Test
    fun `removeSelected removes exactly the selected entries and clears the selection`() = runTest(testDispatcher) {
        val rows = listOf(
            historyRow(id = 1L, mangaId = 101L),
            historyRow(id = 2L, mangaId = 102L),
            historyRow(id = 3L, mangaId = 103L),
        )
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleSelection(1L)
        vm.toggleSelection(3L)
        vm.askRemoveSelected()

        vm.removeSelected()
        advanceUntilIdle()

        coVerify(exactly = 1) { historyRepository.resetHistoryByMangaId(101L) }
        coVerify(exactly = 1) { historyRepository.resetHistoryByMangaId(103L) }
        coVerify(exactly = 0) { historyRepository.resetHistoryByMangaId(102L) }
        coVerify(exactly = 0) { historyRepository.resetHistory(any()) }
        vm.state.value.selection shouldBe emptySet()
        vm.state.value.dialog shouldBe null
    }

    @Test
    fun `removeSelected emits its snackbar`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(historyRow(id = 1L)))

        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleSelection(1L)

        vm.events.test {
            vm.removeSelected()
            awaitItem() shouldBe HistoryViewModel.Event.Snackbar(R.string.history_removed)
        }
    }

    @Test
    fun `removeSelected with nothing selected just dismisses the dialog`() = runTest(testDispatcher) {
        every { historyRepository.getHistory(any()) } returns flowOf(emptyList())

        val vm = viewModel()
        advanceUntilIdle()
        vm.askClearAll() // any dialog will do, to prove removeSelected dismisses it

        vm.removeSelected()
        advanceUntilIdle()

        coVerify(exactly = 0) { historyRepository.resetHistory(any()) }
        coVerify(exactly = 0) { historyRepository.resetHistoryByMangaId(any()) }
        vm.state.value.dialog shouldBe null
    }

    private fun source(sourceId: Long): CatalogueSource = mockk { every { id } returns sourceId }

    private fun installedExtension(isNsfw: Boolean, vararg sourceIds: Long) = Extension.Installed(
        packageName = "dev.example.fixture${sourceIds.first()}",
        name = "Fixture Extension",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = isNsfw,
        sources = sourceIds.map(::source),
        extFile = File("fixture.ext"),
    )

    @Test
    fun `hide NSFW is off by default and shows every row`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installedExtension(isNsfw = true, MATURE_SOURCE))
        val rows = listOf(historyRow(id = 1L, sourceId = MATURE_SOURCE), historyRow(id = 2L, sourceId = PLAIN_SOURCE))
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()

        uiPreferences.historyHideNsfw.get() shouldBe false
        vm.state.value.hideNsfw shouldBe false
        vm.state.value.items shouldContainExactly rows
        vm.state.value.hiddenCount shouldBe 0
    }

    @Test
    fun `hide NSFW leaves out the rows of an NSFW extension's sources and keeps the rest`() = runTest(testDispatcher) {
        installedFlow.value = listOf(
            installedExtension(isNsfw = true, MATURE_SOURCE, OTHER_MATURE_SOURCE),
            installedExtension(isNsfw = false, PLAIN_SOURCE),
        )
        val mature = historyRow(id = 1L, sourceId = MATURE_SOURCE)
        val plain = historyRow(id = 2L, sourceId = PLAIN_SOURCE)
        val otherMature = historyRow(id = 3L, sourceId = OTHER_MATURE_SOURCE)
        // The Local source and a source no installed extension provides: nothing says what they hold.
        val local = historyRow(id = 4L, sourceId = SourceIds.LOCAL)
        val uninstalled = historyRow(id = 5L, sourceId = UNINSTALLED_SOURCE)
        val rows = listOf(mature, plain, otherMature, local, uninstalled)
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()
        vm.setHideNsfw(true)
        advanceUntilIdle()

        uiPreferences.historyHideNsfw.get() shouldBe true
        vm.state.value.hideNsfw shouldBe true
        vm.state.value.items shouldContainExactly listOf(plain, local, uninstalled)
        vm.state.value.hiddenCount shouldBe 2

        vm.setHideNsfw(false)
        advanceUntilIdle()

        vm.state.value.items shouldContainExactly rows
        vm.state.value.hiddenCount shouldBe 0
    }

    @Test
    fun `hide NSFW applies to search results too`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installedExtension(isNsfw = true, MATURE_SOURCE))
        uiPreferences.historyHideNsfw.set(true)
        val mature = historyRow(id = 1L, title = "Fixture Match", sourceId = MATURE_SOURCE)
        val plain = historyRow(id = 2L, title = "Fixture Match Too", sourceId = PLAIN_SOURCE)
        every { historyRepository.getHistory("") } returns flowOf(emptyList())
        every { historyRepository.getHistory("fixture match") } returns flowOf(listOf(mature, plain))

        val vm = viewModel()
        advanceUntilIdle()
        vm.search("Fixture Match")
        advanceUntilIdle()

        vm.state.value.items shouldContainExactly listOf(plain)
        vm.state.value.hiddenCount shouldBe 1
    }

    @Test
    fun `an NSFW extension installed later hides its rows then`() = runTest(testDispatcher) {
        uiPreferences.historyHideNsfw.set(true)
        val row = historyRow(id = 1L, sourceId = MATURE_SOURCE)
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(row))

        val vm = viewModel()
        advanceUntilIdle()
        vm.state.value.items shouldContainExactly listOf(row)

        installedFlow.value = listOf(installedExtension(isNsfw = true, MATURE_SOURCE))
        advanceUntilIdle()

        vm.state.value.items shouldBe emptyList()
        vm.state.value.hiddenCount shouldBe 1
    }

    @Test
    fun `hide NSFW shows no rows until the extensions have loaded`() = runTest(testDispatcher) {
        // Before the first load the installed list is empty, so filtering against it would show
        // every NSFW row for a moment.
        initializedFlow.value = false
        uiPreferences.historyHideNsfw.set(true)
        val rows = listOf(historyRow(id = 1L, sourceId = MATURE_SOURCE), historyRow(id = 2L, sourceId = PLAIN_SOURCE))
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()

        vm.state.value.loading shouldBe true
        vm.state.value.items shouldBe emptyList()
        vm.state.value.hideNsfw shouldBe true
    }

    @Test
    fun `hide NSFW filters the rows once the extensions have loaded`() = runTest(testDispatcher) {
        initializedFlow.value = false
        uiPreferences.historyHideNsfw.set(true)
        val plain = historyRow(id = 2L, sourceId = PLAIN_SOURCE)
        val rows = listOf(historyRow(id = 1L, sourceId = MATURE_SOURCE), plain)
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()
        vm.state.value.loading shouldBe true

        // The manager publishes the list before it turns the flag.
        installedFlow.value = listOf(installedExtension(isNsfw = true, MATURE_SOURCE))
        initializedFlow.value = true
        advanceUntilIdle()

        vm.state.value.loading shouldBe false
        vm.state.value.items shouldContainExactly listOf(plain)
        vm.state.value.hiddenCount shouldBe 1
    }

    @Test
    fun `with hide NSFW off the rows do not wait for the extensions`() = runTest(testDispatcher) {
        initializedFlow.value = false
        val rows = listOf(historyRow(id = 1L, sourceId = MATURE_SOURCE), historyRow(id = 2L, sourceId = PLAIN_SOURCE))
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()

        vm.state.value.loading shouldBe false
        vm.state.value.items shouldContainExactly rows
        vm.state.value.hiddenCount shouldBe 0
    }

    @Test
    fun `hiding a selected row drops it from the selection`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installedExtension(isNsfw = true, MATURE_SOURCE))
        val rows = listOf(historyRow(id = 1L, sourceId = MATURE_SOURCE), historyRow(id = 2L, sourceId = PLAIN_SOURCE))
        every { historyRepository.getHistory(any()) } returns flowOf(rows)

        val vm = viewModel()
        advanceUntilIdle()
        vm.selectAll()
        vm.setHideNsfw(true)
        advanceUntilIdle()

        vm.state.value.selection shouldBe setOf(2L)
        vm.state.value.allSelected shouldBe true
    }

    @Test
    fun `clear all still clears the hidden rows`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installedExtension(isNsfw = true, MATURE_SOURCE))
        uiPreferences.historyHideNsfw.set(true)
        val row = historyRow(id = 1L, sourceId = MATURE_SOURCE)
        every { historyRepository.getHistory(any()) } returns flowOf(listOf(row))

        val vm = viewModel()
        advanceUntilIdle()
        vm.clearAll()
        advanceUntilIdle()

        coVerify(exactly = 1) { historyRepository.deleteAllHistory() }
        coVerify(exactly = 0) { historyRepository.resetHistoryByMangaId(any()) }
    }

    @Test
    fun `the day headers come from the shown rows, so a hidden day has none`() {
        val day = Date(1_800_000_000_000L)
        val earlier = Date(1_800_000_000_000L - 3 * 24 * 60 * 60 * 1000L)
        val rows = listOf(
            historyRow(id = 1L, sourceId = PLAIN_SOURCE).copy(readAt = day),
            historyRow(id = 2L, sourceId = MATURE_SOURCE).copy(readAt = earlier),
        )

        val models = historyUiModels(withoutMatureSources(rows, setOf(MATURE_SOURCE)))

        models.filterIsInstance<HistoryUiModel.Header>().size shouldBe 1
        models.filterIsInstance<HistoryUiModel.Item>().map { it.history.id } shouldContainExactly listOf(1L)
    }

    private companion object {
        const val PLAIN_SOURCE = 11L
        const val MATURE_SOURCE = 21L
        const val OTHER_MATURE_SOURCE = 22L
        const val UNINSTALLED_SOURCE = 31L
    }
}
