package com.shinsaikou.ui.updates

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.merged.interactor.CarryMergedTracks
import com.shinsaikou.domain.merged.interactor.FoldMergedUpdates
import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.merged.interactor.MergeEntries
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.preferences.UpdatesPreferences
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.updates.interactor.GetUpdates
import java.util.Properties
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Updates tab's list for a merged title (R163) over the real schema and repositories on the
 * in-memory JVM driver: the query's own filters run in SQLite, then [FoldMergedUpdates] puts the
 * copies it did not list to the same filters. Entries 1 (the main entry), 2 and 3 are favourites of
 * one source; 1 and 2 are merged, 3 is on its own. Every chapter is number 5 unless a test says so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesMergedFiltersTest {

    private val testDispatcher = StandardTestDispatcher()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val updatesPreferences = UpdatesPreferences(InMemoryPreferenceStore())
    private val downloadedFlow = MutableStateFlow<Set<Long>>(emptySet())

    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val recent = Instant.parse("2026-09-29T12:00:00Z").toEpochMilliseconds()

    private object InlineTransactions : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        exec("PRAGMA foreign_keys = ON")
        (1L..3L).forEach { id ->
            exec(
                """
                INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                    chapter_flags, cover_last_modified, date_added, memo)
                VALUES ($id, 1, 'https://example.invalid/manga/$id', 'Title $id', 0, 1, 0, 0, 0, 0, 0,
                    CAST('{}' AS BLOB))
                """,
            )
        }
        libraryPreferences.mergedEntries.set(true)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
        driver.close()
    }

    private val database by lazy {
        Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
        )
    }
    private val handler by lazy { AndroidDatabaseHandler(database, testDispatcher) }
    private val chapterRepository by lazy { ChapterRepositoryImpl(handler) }
    private val groupRepository by lazy { MergedGroupRepositoryImpl(handler) }

    private fun chapter(
        id: Long,
        mangaId: Long,
        number: Double = 5.0,
        scanlator: String? = null,
        lastPageRead: Long = 0,
        bookmark: Boolean = false,
    ) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo)
        VALUES ($id, $mangaId, '/c/$id', 'Chapter $id', ${scanlator?.let { "'$it'" } ?: "NULL"}, 0,
            ${if (bookmark) 1 else 0}, $lastPageRead, $number, $id, ${recent + id}, $recent,
            CAST('{}' AS BLOB))
        """,
    )

    private fun category(id: Long, vararg mangaIds: Long) {
        exec("INSERT INTO categories(_id, name, sort, flags) VALUES ($id, 'Category $id', $id, 0)")
        mangaIds.forEach { exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES ($it, $id)") }
    }

    private suspend fun merge() {
        val getGroup = GetMergedGroup(groupRepository, libraryPreferences)
        val reconcile = ReconcileMergedRead(groupRepository, libraryPreferences)
        MergeEntries(
            groupRepository,
            getGroup,
            reconcile,
            CarryMergedTracks(TrackRepositoryImpl(handler)),
            libraryPreferences,
            InlineTransactions,
        ).await(mainId = 1, mangaIds = listOf(1, 2))
    }

    private fun viewModel(): UpdatesViewModel {
        val installed = mockk<SourceManager>()
        every { installed.get(any()) } returns mockk<Source>()
        val getManga = GetManga(MangaRepositoryImpl(handler))
        val downloadedChapters = mockk<DownloadedChapters>(relaxed = true) {
            every { subscribeAll() } returns downloadedFlow
        }
        return UpdatesViewModel(
            getUpdates = GetUpdates(UpdatesRepositoryImpl(handler)),
            getChapter = mockk(),
            setReadStatus = mockk(),
            toggleBookmark = mockk(),
            libraryUpdateScheduler = mockk(relaxed = true) { every { isPaused } returns false },
            errorStore = mockk(relaxed = true) { every { errors } returns MutableStateFlow(emptyList()) },
            sourceManager = installed,
            libraryPreferences = libraryPreferences,
            updatesPreferences = updatesPreferences,
            uiPreferences = UiPreferences(InMemoryPreferenceStore()),
            downloadedChapters = downloadedChapters,
            downloadQueue = mockk(relaxed = true) { every { subscribe() } returns MutableStateFlow(emptyList()) },
            downloadManifest = mockk(relaxed = true),
            getManga = getManga,
            getCategories = GetCategories(CategoryRepositoryImpl(handler)),
            getMergedGroups = GetMergedGroups(groupRepository, libraryPreferences),
            foldMergedUpdates = FoldMergedUpdates(chapterRepository, getManga, GetMergedChapters(chapterRepository, installed)),
            dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
            clock = object : Clock {
                override fun now(): Instant = this@UpdatesMergedFiltersTest.now
            },
        )
    }

    /** The chapter ids of the rows the tab shows, newest fetch first. */
    private suspend fun TestScope.shown(): List<Long> = viewModel().let {
        advanceUntilIdle()
        it.state.value.items.map { row -> row.chapterId }
    }

    /** The main entry each row stands under, in the same order as [shown]. */
    private suspend fun TestScope.titles(): List<Long> = viewModel().let {
        advanceUntilIdle()
        it.state.value.items.map { row -> row.titleId }
    }

    @Test
    fun `with no filter a number both members have is one row, the first source's, under the main entry`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()

        shown() shouldContainExactly listOf(10L)
        titles() shouldContainExactly listOf(1L)
    }

    @Test
    fun `the Started filter keeps the second source's copy when the first source's copy was not started`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2, lastPageRead = 3)
        merge()
        updatesPreferences.filterStarted.set(TriState.ENABLED_IS)

        shown() shouldContainExactly listOf(20L)
        titles() shouldContainExactly listOf(1L)
    }

    @Test
    fun `the Not started filter keeps the first source's copy when the second was started`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2, lastPageRead = 3)
        merge()
        updatesPreferences.filterStarted.set(TriState.ENABLED_NOT)

        shown() shouldContainExactly listOf(10L)
    }

    @Test
    fun `the Bookmarked filter keeps the copy that is bookmarked, wherever it is`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2, bookmark = true)
        merge()
        updatesPreferences.filterBookmarked.set(TriState.ENABLED_IS)

        shown() shouldContainExactly listOf(20L)
    }

    @Test
    fun `a copy from an excluded scanlator is no winner while the switch is on`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1, scanlator = "Group Hidden")
        chapter(20, mangaId = 2)
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Group Hidden')")
        merge()
        updatesPreferences.filterExcludedScanlators.set(true)

        shown() shouldContainExactly listOf(20L)
    }

    @Test
    fun `a copy from an excluded scanlator still wins while the switch is off`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1, scanlator = "Group Hidden")
        chapter(20, mangaId = 2)
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Group Hidden')")
        merge()
        updatesPreferences.filterExcludedScanlators.set(false)

        shown() shouldContainExactly listOf(10L)
    }

    @Test
    fun `a member outside the included categories cannot win a number`() = runTest(testDispatcher) {
        category(7, 2)
        category(8, 1)
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()
        updatesPreferences.filterIncludedCategories.set(listOf(7L))

        shown() shouldContainExactly listOf(20L)
        titles() shouldContainExactly listOf(1L)
    }

    @Test
    fun `a member in an excluded category cannot win a number`() = runTest(testDispatcher) {
        category(7, 1)
        category(8, 2)
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()
        updatesPreferences.filterExcludedCategories.set(listOf(7L))

        shown() shouldContainExactly listOf(20L)
    }

    @Test
    fun `a member in no category is outside an included category`() = runTest(testDispatcher) {
        category(7, 2)
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()
        updatesPreferences.filterIncludedCategories.set(listOf(7L))

        shown() shouldContainExactly listOf(20L)
    }

    @Test
    fun `a member in no category passes when the default category is included`() = runTest(testDispatcher) {
        category(7, 2)
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()
        updatesPreferences.filterIncludedCategories.set(listOf(0L))

        shown() shouldContainExactly listOf(10L)
    }

    @Test
    fun `the Downloaded filter keeps a hidden copy that is on disk`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()
        downloadedFlow.value = setOf(20L)
        updatesPreferences.filterDownloaded.set(TriState.ENABLED_IS)

        shown() shouldContainExactly listOf(20L)
        titles() shouldContainExactly listOf(1L)
    }

    @Test
    fun `without the Downloaded filter a downloaded copy does not outrank the first source`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()
        downloadedFlow.value = setOf(20L)

        shown() shouldContainExactly listOf(10L)
    }

    @Test
    fun `an entry outside the merged title keeps its own row beside the folded one`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        chapter(30, mangaId = 3)
        merge()

        shown() shouldContainExactly listOf(30L, 10L)
        titles() shouldContainExactly listOf(3L, 1L)
    }

    @Test
    fun `with Merged entries off the list is the query's, each row under its own entry`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2)
        merge()
        libraryPreferences.mergedEntries.set(false)

        shown() shouldContainExactly listOf(20L, 10L)
        titles() shouldContainExactly listOf(2L, 1L)
    }

    @Test
    fun `with Merged entries off the filters leave the list as the query answered it`() = runTest(testDispatcher) {
        chapter(10, mangaId = 1)
        chapter(20, mangaId = 2, lastPageRead = 3)
        merge()
        libraryPreferences.mergedEntries.set(false)
        updatesPreferences.filterStarted.set(TriState.ENABLED_IS)

        shown() shouldBe listOf(20L)
    }
}
