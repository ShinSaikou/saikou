package com.shinsaikou.ui.storage

import com.shinsaikou.downloads.OrphanDownloads
import com.shinsaikou.downloads.OrphanTitle
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The storage screen's list of downloads not in the library (phase 4 unit ST): the ticks, and a
 * delete that acts on exactly the titles its confirmation named.
 */
class StorageDownloadsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val first = OrphanTitle(mangaId = 1L, title = "First Title", chapterIds = listOf(10L, 11L), sizeBytes = 300L)
    private val second = OrphanTitle(mangaId = 2L, title = "Second Title", chapterIds = listOf(20L), sizeBytes = 100L)
    private val third = OrphanTitle(mangaId = 3L, title = "Third Title", chapterIds = listOf(30L), sizeBytes = 50L)
    private val orphanDownloads = mockk<OrphanDownloads> {
        coEvery { list() } returns listOf(first, second, third)
        coEvery { delete(any()) } returns emptyList()
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `the list is read on open, and select all and select inverse tick as upstream's do`() = runTest(testDispatcher) {
        val viewModel = StorageDownloadsViewModel(orphanDownloads)
        advanceUntilIdle()
        viewModel.state.value.titles shouldBe listOf(first, second, third)

        viewModel.toggleSelection(2L)
        viewModel.invertSelection()
        viewModel.state.value.selection shouldBe setOf(1L, 3L)
        viewModel.selectAll()
        viewModel.state.value.selection shouldBe setOf(1L, 2L, 3L)
        viewModel.toggleSelection(1L)
        viewModel.state.value.selection shouldBe setOf(2L, 3L)
    }

    @Test
    fun `the delete removes exactly the titles the confirmation named, whatever is ticked afterwards`() =
        runTest(testDispatcher) {
            val viewModel = StorageDownloadsViewModel(orphanDownloads)
            advanceUntilIdle()
            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(3L)

            viewModel.showConfirmation()
            viewModel.state.value.confirming shouldBe listOf(first, third)
            // A tick that changes while the dialog is open changes nothing the dialog named.
            viewModel.toggleSelection(2L)
            viewModel.delete()
            advanceUntilIdle()

            coVerify(exactly = 1) { orphanDownloads.delete(listOf(first, third)) }
            viewModel.state.value.confirming shouldBe null
            viewModel.state.value.selection shouldBe emptySet()
            // The list is read again after the delete: once on open, once after.
            coVerify(exactly = 2) { orphanDownloads.list() }
        }

    @Test
    fun `dismissing the confirmation deletes nothing and keeps the ticks`() = runTest(testDispatcher) {
        val viewModel = StorageDownloadsViewModel(orphanDownloads)
        advanceUntilIdle()
        viewModel.toggleSelection(2L)

        viewModel.showConfirmation()
        viewModel.hideConfirmation()
        viewModel.delete()
        advanceUntilIdle()

        coVerify(exactly = 0) { orphanDownloads.delete(any()) }
        viewModel.state.value.confirming shouldBe null
        viewModel.state.value.selection shouldBe setOf(2L)
    }

    @Test
    fun `nothing ticked opens no confirmation, and a title gone from a new read loses its tick`() =
        runTest(testDispatcher) {
            val viewModel = StorageDownloadsViewModel(orphanDownloads)
            advanceUntilIdle()

            viewModel.showConfirmation()
            viewModel.state.value.confirming shouldBe null

            viewModel.toggleSelection(1L)
            viewModel.toggleSelection(2L)
            coEvery { orphanDownloads.list() } returns listOf(second)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.state.value.selection shouldBe setOf(2L)
        }
}
