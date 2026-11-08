package com.shinsaikou.ui.upcoming

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.preferences.UpcomingPreferences
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
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

/**
 * The Upcoming list for merged titles (R163), beyond `UpcomingUiModelTest`'s one-row cases: what the
 * row shows, the rules a member must pass, and the list following the groups. Entries 1 and 2 are a
 * title whose main entry is 1; entry 3 is on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpcomingMergedTest {

    private val zone = TimeZone.UTC
    private val startOfToday = LocalDate(2026, 11, 10).atStartOfDayIn(zone).toEpochMilliseconds()
    private val day = 24 * 3_600_000L

    private fun item(id: Long, title: String = "Title $id", nextUpdate: Long = startOfToday + day, categories: List<Long> = emptyList()) =
        LibraryManga(
            manga = Manga.create().copy(
                id = id,
                title = title,
                thumbnailUrl = "/cover/$id.jpg",
                nextUpdate = nextUpdate,
                status = MangaStatus.ONGOING.code.toLong(),
            ),
            categories = categories,
            totalChapters = 0L,
            readCount = 0L,
            bookmarkCount = 0L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 0L,
        )

    private fun title(main: Long = 1L, vararg members: Long = longArrayOf(1L, 2L)) = MergedGroup(
        id = main,
        mainMangaId = main,
        showAll = false,
        members = members.mapIndexed { index, id ->
            MergedMember(id, source = id, url = "/$id", position = index.toLong(), checkUpdates = true)
        },
    )

    private fun upcoming(
        library: List<LibraryManga>,
        groups: List<MergedGroup>,
        excluded: Set<Long> = emptySet(),
    ) = upcomingEntries(library, emptySet(), excluded, startOfToday, groups)

    @Test
    fun `the row carries the main entry's title and cover, not the member that earned the date`() {
        val library = listOf(
            item(1L, title = "Main Title", nextUpdate = startOfToday + 3 * day),
            item(2L, title = "Other Title", nextUpdate = startOfToday + day),
        )

        val row = upcoming(library, listOf(title())).single()

        row.id shouldBe 1L
        row.title shouldBe "Main Title"
        row.thumbnailUrl shouldBe "/cover/1.jpg"
        row.nextUpdate shouldBe startOfToday + day
    }

    @Test
    fun `with no groups every member is its own row at its own date`() {
        val library = listOf(
            item(1L, nextUpdate = startOfToday + 3 * day),
            item(2L, nextUpdate = startOfToday + day),
        )

        upcoming(library, emptyList()).map { it.id to it.nextUpdate } shouldContainExactly
            listOf(2L to startOfToday + day, 1L to startOfToday + 3 * day)
    }

    @Test
    fun `a member in an excluded category does not move the title's date`() {
        val library = listOf(
            item(1L, nextUpdate = startOfToday + 3 * day),
            item(2L, nextUpdate = startOfToday + day, categories = listOf(9L)),
        )

        upcoming(library, listOf(title()), excluded = setOf(9L)).map { it.id to it.nextUpdate } shouldContainExactly
            listOf(1L to startOfToday + 3 * day)
    }

    @Test
    fun `a title and a single entry are sorted together by date`() {
        val library = listOf(
            item(1L, nextUpdate = startOfToday + 4 * day),
            item(2L, nextUpdate = startOfToday + 2 * day),
            item(3L, nextUpdate = startOfToday + 3 * day),
        )

        upcoming(library, listOf(title())).map { it.id } shouldContainExactly listOf(1L, 3L)
        upcoming(library, listOf(title())).map { it.nextUpdate } shouldContainExactly
            listOf(startOfToday + 2 * day, startOfToday + 3 * day)
    }

    @Test
    fun `a member whose release is past does not count, the later one names the date`() {
        val library = listOf(
            item(1L, nextUpdate = startOfToday + 3 * day),
            item(2L, nextUpdate = startOfToday - day),
        )

        upcoming(library, listOf(title())).map { it.nextUpdate } shouldContainExactly listOf(startOfToday + 3 * day)
    }

    // The view model: the list follows the groups the gate lets through ------------------------

    private val testDispatcher = StandardTestDispatcher()
    private val library = MutableStateFlow<List<LibraryManga>>(emptyList())
    private val groups = MutableStateFlow<List<MergedGroup>>(emptyList())

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun due(id: Long) = item(id, nextUpdate = (Clock.System.now() + 3.days).toEpochMilliseconds())

    @Test
    fun `the list folds a title while the groups are there and unfolds when they go`() = runTest(testDispatcher) {
        library.value = listOf(due(1L), due(2L))
        groups.value = listOf(title())
        val viewModel = UpcomingViewModel(
            mockk<GetLibraryManga> { every { subscribe() } returns library },
            mockk { every { subscribe() } returns groups },
            LibraryPreferences(InMemoryPreferenceStore()),
            UpcomingPreferences(InMemoryPreferenceStore()),
            mockk { every { subscribe() } returns flowOf(emptyList()) },
            UiPreferences(InMemoryPreferenceStore()),
            AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        )
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        fun ids() = viewModel.state.value.items.filterIsInstance<UpcomingUiModel.Item>().map { it.manga.id }

        ids() shouldContainExactly listOf(1L)

        groups.value = emptyList()
        advanceUntilIdle()

        ids() shouldContainExactly listOf(1L, 2L)
        collector.cancel()
    }
}
