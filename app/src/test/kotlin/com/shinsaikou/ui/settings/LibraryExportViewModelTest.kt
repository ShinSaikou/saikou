package com.shinsaikou.ui.settings

import android.net.Uri
import com.shinsaikou.ui.backup.BackupFileCopy
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/** Export library list writes the library's CSV into the picked document and says how it went. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryExportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val getFavorites = mockk<GetFavorites>()
    private val files = mockk<BackupFileCopy>()
    private val target = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = LibraryExportViewModel(
        getFavorites = getFavorites,
        files = files,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    @Test
    fun `the library's lines go into the picked document, and the result is handed back`() = runTest(testDispatcher) {
        coEvery { getFavorites.await() } returns
            listOf(Manga.create().copy(title = "Old Harbor", author = "Fixture Author"))
        every { files.write(target, any()) } returns true
        var result: Boolean? = null

        viewModel().export(target, LibraryCsvColumns(artist = false)) { result = it }
        advanceUntilIdle()

        verify(exactly = 1) { files.write(target, "Old Harbor,Fixture Author") }
        result shouldBe true
    }

    @Test
    fun `a library that cannot be read is a failed export, not a crash`() = runTest(testDispatcher) {
        coEvery { getFavorites.await() } throws IllegalStateException("fixture: the database is closed")
        var result: Boolean? = null

        viewModel().export(target, LibraryCsvColumns()) { result = it }
        advanceUntilIdle()

        verify(exactly = 0) { files.write(any(), any()) }
        result shouldBe false
    }
}
