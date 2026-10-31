package com.shinsaikou.ui.storage

import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.repository.MangaRepository
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Upstream's clear database over Saikou's repository (phase 4 unit ST): the sources named as upstream's
 * `visualName` names them and sorted by name, and the delete handed the rows the confirmation froze
 * and the keep read switch.
 */
class ClearDatabaseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val counts = MutableStateFlow(mapOf(7L to 2L, 8L to 1L, 9L to 4L))
    private val mangaRepository = mockk<MangaRepository> {
        every { getSourceIdsWithNonLibraryMangaAsFlow() } returns counts
        coEvery { getLiveNonLibraryMangaIds(any()) } returns listOf(71L, 72L, 91L)
        coEvery { deleteNonLibraryMangaByIds(any(), any()) } just Runs
    }
    private val sourceManager = mockk<SourceManager> {
        every { getOrStub(7L) } returns StubSource(id = 7L, name = "Zeta Source", lang = "en")
        every { getOrStub(8L) } returns StubSource(id = 8L, name = "Alpha Source", lang = "")
        every { getOrStub(9L) } returns StubSource.unknown(9L)
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = ClearDatabaseViewModel(
        mangaRepository = mangaRepository,
        sourceManager = sourceManager,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    @Test
    fun `every source with entries outside the library is listed by name with its count`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val ready = viewModel.state.value.shouldBeInstanceOf<ClearDatabaseViewModel.State.Ready>()
        ready.items shouldBe listOf(
            ClearDatabaseViewModel.SourceWithCount(id = 9L, name = "9", count = 4L),
            ClearDatabaseViewModel.SourceWithCount(id = 8L, name = "Alpha Source", count = 1L),
            ClearDatabaseViewModel.SourceWithCount(id = 7L, name = "Zeta Source (EN)", count = 2L),
        )
    }

    @Test
    fun `the delete gets the rows the confirmation froze and the keep read switch`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectAll()
        viewModel.toggleSelection(8L)
        val sources = slot<List<Long>>()

        viewModel.showConfirmation()
        advanceUntilIdle()
        coVerify(exactly = 1) { mangaRepository.getLiveNonLibraryMangaIds(capture(sources)) }
        sources.captured shouldContainExactlyInAnyOrder listOf(7L, 9L)
        viewModel.state.value.shouldBeInstanceOf<ClearDatabaseViewModel.State.Ready>().confirming shouldBe
            listOf(71L, 72L, 91L)

        // While the dialog is open, a sync moves another row of source 7 out of the library and the
        // ticks change: neither reaches the delete.
        coEvery { mangaRepository.getLiveNonLibraryMangaIds(any()) } returns listOf(71L, 72L, 73L, 91L)
        counts.value = mapOf(7L to 3L, 8L to 1L, 9L to 4L)
        viewModel.toggleSelection(8L)
        advanceUntilIdle()
        viewModel.removeConfirmedManga(keepReadManga = true)

        coVerify(exactly = 1) { mangaRepository.deleteNonLibraryMangaByIds(listOf(71L, 72L, 91L), true) }
        coVerify(exactly = 0) { mangaRepository.deleteNonLibraryManga(any(), any()) }
    }

    @Test
    fun `nothing ticked opens no confirmation and dismissing it deletes nothing`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showConfirmation()
        advanceUntilIdle()
        viewModel.state.value.shouldBeInstanceOf<ClearDatabaseViewModel.State.Ready>().confirming shouldBe null

        viewModel.toggleSelection(7L)
        viewModel.showConfirmation()
        advanceUntilIdle()
        viewModel.hideConfirmation()
        viewModel.removeConfirmedManga(keepReadManga = false)

        viewModel.state.value.shouldBeInstanceOf<ClearDatabaseViewModel.State.Ready>().confirming shouldBe null
        coVerify(exactly = 0) { mangaRepository.deleteNonLibraryMangaByIds(any(), any()) }
    }

    @Test
    fun `a source with nothing left to clear leaves the list and its tick`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.toggleSelection(7L)
        viewModel.toggleSelection(8L)

        counts.value = mapOf(7L to 1L)
        advanceUntilIdle()

        val ready = viewModel.state.value.shouldBeInstanceOf<ClearDatabaseViewModel.State.Ready>()
        ready.items.map { it.id } shouldBe listOf(7L)
        ready.selection shouldBe setOf(7L)
    }
}
