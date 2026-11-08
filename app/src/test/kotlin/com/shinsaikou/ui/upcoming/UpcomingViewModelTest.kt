package com.shinsaikou.ui.upcoming

import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.preferences.UpcomingPreferences
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import kotlinx.datetime.Month
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
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
    private val upcomingPreferences = UpcomingPreferences(InMemoryPreferenceStore())
    private val uiPreferences = UiPreferences(InMemoryPreferenceStore())
    private val categories = MutableStateFlow<List<Category>>(emptyList())
    private val getCategories = mockk<GetCategories> {
        every { subscribe() } answers { categories }
    }

    private fun category(id: Long) = Category(id = id, name = "Category $id", order = id, flags = 0L)

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
        upcomingPreferences,
        getCategories,
        uiPreferences,
        appDispatchers,
    )

    private fun due(id: Long, categories: List<Long> = emptyList()) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            status = MangaStatus.ONGOING.code.toLong(),
            nextUpdate = (Clock.System.now() + 3.days).toEpochMilliseconds(),
        ),
        categories = categories,
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

    @Test
    fun `the days in the state agree with the headers of its items`() = runTest(testDispatcher) {
        library.value = listOf(due(1L), due(2L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        val state = viewModel.state.value
        state.days shouldBe upcomingDays(state.items)
        state.days.size shouldBe 1
        val day = state.days.values.single()
        day.count shouldBe 2
        (state.items[day.headerIndex] as UpcomingUiModel.Header).date shouldBe state.days.keys.single()
        collector.cancel()
    }

    @Test
    fun `no entries leave the days empty`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.state.value.days shouldBe emptyMap()
        collector.cancel()
    }

    @Test
    fun `showNextMonth moves the month on by one`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        val start = viewModel.state.value.month

        viewModel.showNextMonth()

        viewModel.state.value.month shouldBe start.plusMonth()
    }

    @Test
    fun `showPreviousMonth moves the month back by one`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        val start = viewModel.state.value.month

        viewModel.showPreviousMonth()

        viewModel.state.value.month shouldBe start.minusMonth()
    }

    @Test
    fun `turning the month across a year boundary changes the year`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        val start = viewModel.state.value.month
        val monthsToNextYear = 12 - start.month.ordinal

        repeat(monthsToNextYear) { viewModel.showNextMonth() }
        viewModel.state.value.month.year shouldBe start.year + 1
        viewModel.state.value.month.month shouldBe Month.JANUARY

        viewModel.showPreviousMonth()
        viewModel.state.value.month.year shouldBe start.year
        viewModel.state.value.month.month shouldBe Month.DECEMBER
    }

    @Test
    fun `turning the month keeps the items and days`() = runTest(testDispatcher) {
        library.value = listOf(due(1L))
        val viewModel = viewModel()
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        val before = viewModel.state.value

        viewModel.showNextMonth()
        advanceUntilIdle()

        viewModel.state.value.items shouldBe before.items
        viewModel.state.value.days shouldBe before.days
        collector.cancel()
    }

    private fun UpcomingViewModel.ids() =
        state.value.items.filterIsInstance<UpcomingUiModel.Item>().map { it.manga.id }

    @Test
    fun `the filter lists reach the state`() = runTest(testDispatcher) {
        categories.value = listOf(category(1L), category(2L), category(3L))
        upcomingPreferences.filterIncludedCategories.set(listOf(1L, 2L))
        upcomingPreferences.filterExcludedCategories.set(listOf(3L))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.filter shouldBe UpcomingViewModel.Filter(setOf(1L, 2L), setOf(3L))
    }

    @Test
    fun `the filter is not active with both lists empty`() {
        UpcomingViewModel.Filter().active shouldBe false
    }

    @Test
    fun `the filter is active with an included category alone`() {
        UpcomingViewModel.Filter(included = setOf(1L)).active shouldBe true
    }

    @Test
    fun `the filter is active with an excluded category alone`() {
        UpcomingViewModel.Filter(excluded = setOf(1L)).active shouldBe true
    }

    @Test
    fun `categoryState reads the list a category is in`() {
        val filter = UpcomingViewModel.Filter(included = setOf(1L), excluded = setOf(2L))

        filter.categoryState(1L) shouldBe TriState.ENABLED_IS
        filter.categoryState(2L) shouldBe TriState.ENABLED_NOT
        filter.categoryState(3L) shouldBe TriState.DISABLED
    }

    @Test
    fun `cycleCategory goes neither, included, excluded, neither`() = runTest(testDispatcher) {
        categories.value = listOf(category(4L))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.cycleCategory(4L)
        advanceUntilIdle()
        viewModel.state.value.filter shouldBe UpcomingViewModel.Filter(included = setOf(4L))

        viewModel.cycleCategory(4L)
        advanceUntilIdle()
        viewModel.state.value.filter shouldBe UpcomingViewModel.Filter(excluded = setOf(4L))

        viewModel.cycleCategory(4L)
        advanceUntilIdle()
        viewModel.state.value.filter shouldBe UpcomingViewModel.Filter()
    }

    @Test
    fun `cycleCategory writes the preferences`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.cycleCategory(4L)
        upcomingPreferences.filterIncludedCategories.get() shouldBe listOf(4L)
        upcomingPreferences.filterExcludedCategories.get() shouldBe emptyList()

        viewModel.cycleCategory(4L)
        upcomingPreferences.filterIncludedCategories.get() shouldBe emptyList()
        upcomingPreferences.filterExcludedCategories.get() shouldBe listOf(4L)
    }

    @Test
    fun `cycleCategory leaves the other categories alone`() = runTest(testDispatcher) {
        upcomingPreferences.filterIncludedCategories.set(listOf(1L))
        upcomingPreferences.filterExcludedCategories.set(listOf(2L))
        val viewModel = viewModel()

        viewModel.cycleCategory(3L)

        upcomingPreferences.filterIncludedCategories.get() shouldBe listOf(1L, 3L)
        upcomingPreferences.filterExcludedCategories.get() shouldBe listOf(2L)
    }

    @Test
    fun `a category in both lists clears in one tap`() = runTest(testDispatcher) {
        upcomingPreferences.filterIncludedCategories.set(listOf(4L))
        upcomingPreferences.filterExcludedCategories.set(listOf(4L))
        val viewModel = viewModel()

        viewModel.cycleCategory(4L)

        upcomingPreferences.filterIncludedCategories.get() shouldBe emptyList()
        upcomingPreferences.filterExcludedCategories.get() shouldBe emptyList()
    }

    @Test
    fun `categoryState of a category in both lists is excluded`() {
        val filter = UpcomingViewModel.Filter(included = setOf(4L), excluded = setOf(4L))

        filter.categoryState(4L) shouldBe TriState.ENABLED_NOT
    }

    @Test
    fun `a category in both lists hides its entries and reads excluded in the filter`() = runTest(testDispatcher) {
        categories.value = listOf(category(5L), category(6L))
        library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)))
        upcomingPreferences.filterIncludedCategories.set(listOf(5L))
        upcomingPreferences.filterExcludedCategories.set(listOf(5L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids().contains(1L) shouldBe false
        viewModel.state.value.filter.categoryState(5L) shouldBe TriState.ENABLED_NOT
        collector.cancel()
    }

    @Test
    fun `an included category keeps only its entries in the items`() = runTest(testDispatcher) {
        categories.value = listOf(category(0L), category(5L), category(6L))
        library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)), due(3L, listOf(0L)))
        upcomingPreferences.filterIncludedCategories.set(listOf(5L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L)
        collector.cancel()
    }

    @Test
    fun `an excluded category drops its entries from the items`() = runTest(testDispatcher) {
        categories.value = listOf(category(0L), category(5L), category(6L))
        library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)), due(3L, listOf(0L)))
        upcomingPreferences.filterExcludedCategories.set(listOf(5L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(2L, 3L)
        collector.cancel()
    }

    @Test
    fun `excluded beats included in the items`() = runTest(testDispatcher) {
        categories.value = listOf(category(5L), category(6L))
        library.value = listOf(due(1L, listOf(5L, 6L)), due(2L, listOf(5L)))
        upcomingPreferences.filterIncludedCategories.set(listOf(5L))
        upcomingPreferences.filterExcludedCategories.set(listOf(6L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(2L)
        collector.cancel()
    }

    @Test
    fun `cycling a category re-filters the items`() = runTest(testDispatcher) {
        categories.value = listOf(category(5L), category(6L))
        library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)))
        val viewModel = viewModel()
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        viewModel.ids() shouldContainExactly listOf(1L, 2L)

        viewModel.cycleCategory(5L)
        advanceUntilIdle()
        viewModel.ids() shouldContainExactly listOf(1L)

        viewModel.cycleCategory(5L)
        advanceUntilIdle()
        viewModel.ids() shouldContainExactly listOf(2L)
        collector.cancel()
    }

    @Test
    fun `an included id that is not a live category is ignored`() = runTest(testDispatcher) {
        library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)))
        categories.value = listOf(category(5L), category(6L))
        upcomingPreferences.filterIncludedCategories.set(listOf(9L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L, 2L)
        viewModel.state.value.filter.included.shouldBeEmpty()
        viewModel.state.value.filter.active shouldBe false
        collector.cancel()
    }

    @Test
    fun `an excluded id that is not a live category is ignored`() = runTest(testDispatcher) {
        library.value = listOf(due(1L, listOf(9L)), due(2L, listOf(6L)))
        categories.value = listOf(category(5L), category(6L))
        upcomingPreferences.filterExcludedCategories.set(listOf(9L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L, 2L)
        viewModel.state.value.filter.excluded.shouldBeEmpty()
        viewModel.state.value.filter.active shouldBe false
        collector.cancel()
    }

    @Test
    fun `a dead id is dropped while a live one in the same list stays`() = runTest(testDispatcher) {
        library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)))
        categories.value = listOf(category(5L), category(6L))
        upcomingPreferences.filterIncludedCategories.set(listOf(5L, 9L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L)
        viewModel.state.value.filter shouldBe UpcomingViewModel.Filter(included = setOf(5L))
        collector.cancel()
    }

    @Test
    fun `deleting an included category drops it from the filter and shows the entries again`() =
        runTest(testDispatcher) {
            library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)))
            categories.value = listOf(category(5L), category(6L))
            upcomingPreferences.filterIncludedCategories.set(listOf(6L))
            val viewModel = viewModel()
            val collector = backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()
            viewModel.ids() shouldContainExactly listOf(2L)
            viewModel.state.value.filter.active shouldBe true

            categories.value = listOf(category(5L))
            advanceUntilIdle()

            viewModel.ids() shouldContainExactly listOf(1L, 2L)
            viewModel.state.value.filter shouldBe UpcomingViewModel.Filter()
            collector.cancel()
        }

    @Test
    fun `deleting an excluded category drops it from the filter and shows the entries again`() =
        runTest(testDispatcher) {
            library.value = listOf(due(1L, listOf(5L)), due(2L, listOf(6L)))
            categories.value = listOf(category(5L), category(6L))
            upcomingPreferences.filterExcludedCategories.set(listOf(5L))
            val viewModel = viewModel()
            val collector = backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()
            viewModel.ids() shouldContainExactly listOf(2L)

            categories.value = listOf(category(6L))
            advanceUntilIdle()

            viewModel.ids() shouldContainExactly listOf(1L, 2L)
            viewModel.state.value.filter shouldBe UpcomingViewModel.Filter()
            collector.cancel()
        }

    @Test
    fun `the default category id 0 stays usable when the categories list holds it`() = runTest(testDispatcher) {
        library.value = listOf(due(1L, listOf(0L)), due(2L, listOf(6L)))
        categories.value = listOf(category(0L), category(6L))
        upcomingPreferences.filterIncludedCategories.set(listOf(0L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L)
        viewModel.state.value.filter shouldBe UpcomingViewModel.Filter(included = setOf(0L))
        collector.cancel()
    }

    @Test
    fun `the default category id 0 is ignored when the categories list lacks it`() = runTest(testDispatcher) {
        library.value = listOf(due(1L, listOf(0L)), due(2L, listOf(6L)))
        categories.value = listOf(category(6L))
        upcomingPreferences.filterExcludedCategories.set(listOf(0L))
        val viewModel = viewModel()

        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.ids() shouldContainExactly listOf(1L, 2L)
        viewModel.state.value.filter.active shouldBe false
        collector.cancel()
    }

    @Test
    fun `no dialog is open at first`() {
        viewModel().state.value.dialog shouldBe null
    }

    @Test
    fun `showFilterDialog opens the filter sheet and dismissDialog closes it`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.showFilterDialog()
        viewModel.state.value.dialog shouldBe UpcomingViewModel.Dialog.FilterSheet

        viewModel.dismissDialog()
        viewModel.state.value.dialog shouldBe null
    }

    @Test
    fun `the categories in the state come from GetCategories`() = runTest(testDispatcher) {
        categories.value = listOf(category(0L), category(3L), category(4L))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.categories shouldBe listOf(category(0L), category(3L), category(4L))
    }

    @Test
    fun `the categories in the state follow GetCategories`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.categories.shouldBeEmpty()

        categories.value = listOf(category(3L))
        advanceUntilIdle()

        viewModel.state.value.categories shouldBe listOf(category(3L))
    }
}
