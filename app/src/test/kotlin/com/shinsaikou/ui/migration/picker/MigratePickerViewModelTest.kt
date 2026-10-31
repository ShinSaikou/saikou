package com.shinsaikou.ui.migration.picker

import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * The Migrate tab's per-source picker's model (phase 4 unit MB, design g7 final section 19.5): one
 * source's library entries sorted ignoring case, the selection in tap order and restored after a
 * process death, the list following the library live, and a failed feed reported once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigratePickerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val favourites = MutableStateFlow<List<Manga>>(emptyList())
    private val mangaRepository = mockk<MangaRepository> {
        every { getFavoritesBySourceId(SOURCE_ID) } returns favourites
    }
    private val sourceManager = mockk<SourceManager> {
        every { getOrStub(SOURCE_ID) } returns StubSource(SOURCE_ID, "Old Harbor", "en")
    }

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    private fun TestScope.viewModel(selection: List<Long> = emptyList()): MigratePickerViewModel {
        val viewModel = MigratePickerViewModel(
            sourceId = SOURCE_ID,
            selection = selection,
            sourceManager = sourceManager,
            getFavorites = GetFavorites(mangaRepository),
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
        )
        advanceUntilIdle()
        return viewModel
    }

    private fun entry(id: Long, title: String) =
        Manga.create().copy(id = id, source = SOURCE_ID, title = title, favorite = true)

    @Test
    fun `the source's entries are listed by title ignoring case under the source's remembered name`() = runTest(dispatcher) {
        favourites.value = listOf(entry(1L, "delta"), entry(2L, "Bravo"), entry(3L, "alpha"), entry(4L, "Charlie"))

        val viewModel = viewModel()

        viewModel.state.value.isLoading shouldBe false
        viewModel.state.value.sourceName shouldBe "Old Harbor"
        viewModel.state.value.titles.map { it.id } shouldContainExactly listOf(3L, 2L, 4L, 1L)
        verify(exactly = 1) { mangaRepository.getFavoritesBySourceId(SOURCE_ID) }
    }

    @Test
    fun `the selection keeps tap order, a second tap drops one and clear empties it`() = runTest(dispatcher) {
        val entries = listOf(entry(1L, "Alpha"), entry(2L, "Bravo"), entry(3L, "Charlie"))
        favourites.value = entries
        val viewModel = viewModel()

        viewModel.toggleSelection(entries[2])
        viewModel.toggleSelection(entries[0])
        viewModel.toggleSelection(entries[1])

        viewModel.state.value.selection shouldContainExactly listOf(3L, 1L, 2L)
        viewModel.state.value.selectionMode shouldBe true

        viewModel.toggleSelection(entries[0])

        viewModel.state.value.selection shouldContainExactly listOf(3L, 2L)

        viewModel.clearSelection()

        viewModel.state.value.selection.shouldBeEmpty()
        viewModel.state.value.selectionMode shouldBe false
    }

    @Test
    fun `a selection handed back after a process death is the starting selection, in its order`() = runTest(dispatcher) {
        favourites.value = listOf(entry(1L, "Alpha"), entry(2L, "Bravo"))

        val viewModel = viewModel(selection = listOf(2L, 1L))

        viewModel.state.value.selection shouldContainExactly listOf(2L, 1L)
        viewModel.state.value.selectionMode shouldBe true
    }

    @Test
    fun `an entry that leaves the library leaves the list at once`() = runTest(dispatcher) {
        favourites.value = listOf(entry(1L, "Alpha"), entry(2L, "Bravo"))
        val viewModel = viewModel()

        favourites.value = listOf(entry(2L, "Bravo"))
        advanceUntilIdle()

        viewModel.state.value.titles.map { it.id } shouldContainExactly listOf(2L)
    }

    @Test
    fun `a failed feed is reported once and shows an empty list rather than Loading`() = runTest(dispatcher) {
        every { mangaRepository.getFavoritesBySourceId(SOURCE_ID) } returns flow { throw IOException("database gone") }
        val events = mutableListOf<MigratePickerViewModel.Event>()

        val viewModel = MigratePickerViewModel(
            sourceId = SOURCE_ID,
            selection = emptyList(),
            sourceManager = sourceManager,
            getFavorites = GetFavorites(mangaRepository),
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
        )
        backgroundScope.launch { viewModel.events.collect { events += it } }
        advanceUntilIdle()

        events shouldContainExactly listOf(MigratePickerViewModel.Event.FailedFetchingFavorites)
        viewModel.state.value.isLoading shouldBe false
        viewModel.state.value.isEmpty shouldBe true
    }

    private companion object {
        const val SOURCE_ID = 77L
    }
}
