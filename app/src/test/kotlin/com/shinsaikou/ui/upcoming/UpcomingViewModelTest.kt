package com.shinsaikou.ui.upcoming

import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

@OptIn(ExperimentalCoroutinesApi::class)
class UpcomingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)
    private val library = MutableStateFlow<List<LibraryManga>>(emptyList())
    private var subscriptions = 0
    private val getLibraryManga = mockk<GetLibraryManga> {
        every { subscribe() } answers { library.onStart { subscriptions++ } }
    }
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val uiPreferences = UiPreferences(InMemoryPreferenceStore())

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = UpcomingViewModel(
        getLibraryManga,
        mockk { every { subscribe() } returns flowOf(emptyList()) },
        libraryPreferences,
        uiPreferences,
        appDispatchers,
    )

    private fun due(id: Long) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            status = MangaStatus.ONGOING.code.toLong(),
            nextUpdate = (Clock.System.now() + 3.days).toEpochMilliseconds(),
        ),
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    @Test
    fun `the library is not read until the state has a subscriber`() = runTest(testDispatcher) {
        library.value = listOf(due(1L))

        val viewModel = viewModel()
        advanceUntilIdle()

        subscriptions shouldBe 0
        viewModel.state.value.loading shouldBe true
        viewModel.state.value.items.shouldBeEmpty()
    }

    @Test
    fun `the state fills while it has a subscriber`() = runTest(testDispatcher) {
        library.value = listOf(due(1L), due(2L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.state.value.loading shouldBe false
        viewModel.state.value.items.filterIsInstance<UpcomingUiModel.Item>().map { it.manga.id } shouldBe listOf(1L, 2L)
        subscriptions shouldBe 1
        collector.cancel()
    }

    @Test
    fun `the library is no longer collected once the last subscriber leaves`() = runTest(testDispatcher) {
        library.value = listOf(due(1L))
        val viewModel = viewModel()
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        collector.cancel()
        advanceUntilIdle()
        library.value = listOf(due(1L), due(2L))
        advanceUntilIdle()

        viewModel.state.value.items.filterIsInstance<UpcomingUiModel.Item>().map { it.manga.id } shouldBe listOf(1L)
    }

    @Test
    fun `the date settings reach the state`() = runTest(testDispatcher) {
        uiPreferences.relativeTime.set(false)
        uiPreferences.dateFormat.set("yyyy-MM-dd")

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.relativeTime shouldBe false
        viewModel.state.value.dateFormat shouldBe "yyyy-MM-dd"
    }
}
