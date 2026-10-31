package com.shinsaikou.ui.migration.list

import app.cash.turbine.test
import com.shinsaikou.ui.migration.list.MigratingManga.SearchResult
import com.shinsaikou.ui.migration.list.MigrationListHarness.Companion.OLD_SOURCE
import com.shinsaikou.ui.migration.list.MigrationListHarness.Companion.matchOf
import com.shinsaikou.ui.migration.list.MigrationListHarness.Companion.result
import com.shinsaikou.ui.migration.list.MigrationListViewModel.Dialog
import com.shinsaikou.ui.migration.list.MigrationListViewModel.Event
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import tachiyomi.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * The migration list's writes (phase 4 unit MB, design g7 final section 19.3): Migrate all and Copy
 * all, Migrate now, the hand-picked target, and a list restored after a process death, with the
 * departures M1, M2, M3, M6 and M7. Every write goes through a mockk [MigrateManga][com.shinsaikou.migration.MigrateManga]
 * that records its arguments; the rows it changes are the harness's map.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationListWritesTest {

    private val dispatcher = StandardTestDispatcher()
    private val h = MigrationListHarness(dispatcher)
    private val source = h.source(2)

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(dispatcher)
        h.preferences.enabledLanguages.set(setOf("en"))
        h.preferences.migrationSources.set(listOf(2L))
        h.source(OLD_SOURCE)
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    /** Library entries 10 onwards with these titles, each matched on source 2 unless listed in [unmatched]. */
    private fun entries(vararg titles: String, unmatched: Set<String> = emptySet()): List<Long> =
        titles.mapIndexed { index, title ->
            val id = 10L + index
            h.libraryEntry(id, title)
            if (title !in unmatched) h.remoteEntry(source, title)
            id
        }

    private fun TestScope.start(ids: List<Long>, restored: RestoredOutcomes? = null): MigrationListViewModel {
        val viewModel = h.viewModel(ids, restored = restored)
        advanceUntilIdle()
        return viewModel
    }

    private fun writtenIds() = h.migrateCalls.map { it.current.id }

    @Test
    fun `Migrate all writes in list order and skips the unmatched, Copy all copies`() = runTest(dispatcher) {
        val ids = entries("Alpha Moon", "Bravo Sun", "Delta Road", unmatched = setOf("Bravo Sun"))

        val copying = start(ids)
        copying.showMigrateDialog(copy = true)
        copying.state.value.dialog shouldBe Dialog.Migrate(copy = true, totalCount = 3, skippedCount = 1)
        copying.copyMangas()
        advanceUntilIdle()
        writtenIds() shouldContainExactly listOf(10L, 12L)
        h.migrateCalls.map { it.replace }.distinct() shouldContainExactly listOf(false)

        h.migrateCalls.clear()
        val migrating = start(ids)
        migrating.migrateMangas()
        advanceUntilIdle()
        writtenIds() shouldContainExactly listOf(10L, 12L)
        h.migrateCalls.map { it.replace }.distinct() shouldContainExactly listOf(true)
    }

    @Test
    fun `one failing entry of three stays listed with its match, the others are written, and nothing closes`() =
        runTest(dispatcher) {
            val ids = entries("Alpha Moon", "Bravo Sun", "Delta Road")
            h.migrateFailures[11L] = IOException("refused")
            val viewModel = start(ids)
            val events = h.events(viewModel, backgroundScope)

            viewModel.migrateMangas()
            advanceUntilIdle()

            writtenIds() shouldContainExactly listOf(10L, 11L, 12L)
            viewModel.state.value.mangaIds shouldContainExactly listOf(11L)
            (viewModel.result(11) is SearchResult.Success) shouldBe true
            events shouldContainExactly listOf(Event.Failed(1))
            viewModel.state.value.dialog shouldBe null
        }

    @Test
    fun `a failing entry whose row left the library meanwhile leaves the list uncounted, a live one stays`() =
        runTest(dispatcher) {
            val ids = entries("Alpha Moon", "Bravo Sun", "Delta Road")
            h.migrateFailures[10L] = IOException("refused")
            h.migrateFailures[11L] = IOException("no longer in the library")
            // A pull removes entry 11 between the list's read and the write's own.
            h.beforeFailure = { id -> if (id == 11L) h.rows[id] = h.rows.getValue(id).copy(favorite = false) }
            val viewModel = start(ids)
            val events = h.events(viewModel, backgroundScope)

            viewModel.migrateMangas()
            advanceUntilIdle()

            writtenIds() shouldContainExactly listOf(10L, 11L, 12L)
            viewModel.state.value.mangaIds shouldContainExactly listOf(10L)
            events shouldContainExactly listOf(Event.Failed(1))
        }

    @Test
    fun `every entry written sends exactly one close event`() = runTest(dispatcher) {
        val viewModel = start(entries("Alpha Moon", "Bravo Sun"))

        viewModel.events.test {
            viewModel.migrateMangas()
            advanceUntilIdle()
            awaitItem() shouldBe Event.Close
            expectNoEvents()
        }
        viewModel.state.value.mangaIds.shouldBeEmpty()
    }

    @Test
    fun `the last entry removed while its picked target is fetched sends exactly one close event`() =
        runTest(dispatcher) {
            val viewModel = start(entries("Alpha Moon", unmatched = setOf("Alpha Moon")))
            h.storedRow(50, sourceId = 2L, url = "/picked", title = "Picked")
            val hold = CompletableDeferred<Unit>()
            h.refreshHold = hold

            viewModel.events.test {
                viewModel.useMangaForMigration(current = 10L, target = 50L)
                runCurrent()
                viewModel.removeManga(10L)
                advanceUntilIdle()
                hold.complete(Unit)
                advanceUntilIdle()
                awaitItem() shouldBe Event.Close
                expectNoEvents()
            }
        }

    @Test
    fun `unmatched entries left at the end do not keep the screen open`() = runTest(dispatcher) {
        val viewModel = start(entries("Alpha Moon", "Bravo Sun", unmatched = setOf("Bravo Sun")))
        val events = h.events(viewModel, backgroundScope)

        viewModel.migrateMangas()
        advanceUntilIdle()

        viewModel.state.value.mangaIds shouldContainExactly listOf(11L)
        events shouldContainExactly listOf(Event.Close)
    }

    @Test
    fun `M2 each write reads its entry again, so one gone is skipped and fresh notes are passed`() =
        runTest(dispatcher) {
            val viewModel = start(entries("Alpha Moon", "Bravo Sun"))
            h.rows[10] = h.rows.getValue(10).copy(favorite = false)
            h.rows[11] = h.rows.getValue(11).copy(notes = "written after the list was built")

            viewModel.migrateMangas()
            advanceUntilIdle()

            writtenIds() shouldContainExactly listOf(11L)
            h.migrateCalls.single().current.notes shouldBe "written after the list was built"
            viewModel.state.value.mangaIds.shouldBeEmpty()
        }

    @Test
    fun `M6 a second entry onto a match written in the same run is held, and a match already in the library is written`() =
        runTest(dispatcher) {
            h.libraryEntry(10, "Alpha Moon")
            h.libraryEntry(11, "Alpha Moon Two")
            h.libraryEntry(12, "Delta Road")
            h.remoteEntry(source, "Alpha Moon", url = "/shared")
            h.remoteEntry(source, "Alpha Moon Two", url = "/shared")
            h.storedRow(60, sourceId = 2L, url = "/favourite", title = "Delta Road", favorite = true)
            h.remoteEntry(source, "Delta Road", url = "/favourite")
            // The library as the favourite-id flow shows it before the press, 60 already in it.
            h.favoriteIds.value = setOf(10L, 11L, 12L, 60L)
            val viewModel = start(listOf(10L, 11L, 12L))
            val events = h.events(viewModel, backgroundScope)
            viewModel.matchOf(10)?.id shouldBe viewModel.matchOf(11)?.id

            viewModel.migrateMangas()
            advanceUntilIdle()

            writtenIds() shouldContainExactly listOf(10L, 12L)
            h.migrateCalls.last().target.id shouldBe 60L
            viewModel.state.value.mangaIds shouldContainExactly listOf(11L)
            events shouldContainExactly listOf(Event.Held(1))
        }

    @Test
    fun `M7 a row Migrate now is writing is written once, however often it is tapped or Copy all pressed`() =
        runTest(dispatcher) {
            val viewModel = start(entries("Alpha Moon", "Bravo Sun"))
            val hold = CompletableDeferred<Unit>()
            h.migrateHold = hold

            viewModel.migrateNow(10L, replace = false)
            viewModel.migrateNow(10L, replace = false)
            runCurrent()
            viewModel.copyMangas()
            runCurrent()
            writtenIds() shouldContainExactly listOf(10L)

            hold.complete(Unit)
            advanceUntilIdle()
            writtenIds() shouldContainExactly listOf(10L, 11L)
        }

    @Test
    fun `Cancel after the first entry stops the loop, the first leaves the list, the rest stay, and nothing closes`() =
        runTest(dispatcher) {
            val viewModel = start(entries("Alpha Moon", "Bravo Sun", "Delta Road"))
            val events = h.events(viewModel, backgroundScope)
            h.onMigrated = { id -> if (id == 10L) viewModel.cancelMigrate() }

            viewModel.migrateMangas()
            advanceUntilIdle()

            writtenIds() shouldContainExactly listOf(10L)
            viewModel.state.value.mangaIds shouldContainExactly listOf(11L, 12L)
            events.shouldBeEmpty()
            viewModel.state.value.migrating shouldBe false
            viewModel.state.value.dialog shouldBe null
        }

    @Test
    fun `a cancellation during a write ends the loop and is never counted as failed`() = runTest(dispatcher) {
        val viewModel = start(entries("Alpha Moon", "Bravo Sun"))
        val events = h.events(viewModel, backgroundScope)
        h.migrateHold = CompletableDeferred()

        viewModel.migrateMangas()
        runCurrent()
        viewModel.cancelMigrate()
        advanceUntilIdle()

        writtenIds() shouldContainExactly listOf(10L)
        events.shouldBeEmpty()
        viewModel.state.value.mangaIds shouldContainExactly listOf(10L, 11L)
        viewModel.state.value.migrating shouldBe false
    }

    @Test
    fun `downloads left behind are counted in one event, and the list closes only once it is answered`() =
        runTest(dispatcher) {
            val ids = entries("Alpha Moon", "Bravo Sun", "Delta Road")
            h.leftBehind[10L] = listOf(100L, 101L)
            h.leftBehind[12L] = listOf(120L)
            val viewModel = start(ids)
            val events = h.events(viewModel, backgroundScope)

            viewModel.migrateMangas()
            advanceUntilIdle()

            viewModel.state.value.mangaIds.shouldBeEmpty()
            events shouldContainExactly listOf(Event.DownloadsLeftBehind(listOf(100L, 101L, 120L), carried = true))

            viewModel.resolveLeftBehind(listOf(100L, 101L, 120L), delete = true)
            advanceUntilIdle()

            h.deletedLeftBehind shouldContainExactly listOf(listOf(100L, 101L, 120L))
            events shouldContainExactly listOf(Event.DownloadsLeftBehind(listOf(100L, 101L, 120L), carried = true), Event.Close)
        }

    @Test
    fun `Keep them deletes nothing, and a Migrate now that leaves downloads behind waits for its answer too`() =
        runTest(dispatcher) {
            val viewModel = start(entries("Alpha Moon"))
            val events = h.events(viewModel, backgroundScope)
            h.leftBehind[10L] = listOf(100L)

            viewModel.migrateNow(10L, replace = true)
            advanceUntilIdle()

            events shouldContainExactly listOf(Event.DownloadsLeftBehind(listOf(100L), carried = true))

            viewModel.resolveLeftBehind(listOf(100L), delete = false)
            advanceUntilIdle()

            h.deletedLeftBehind.shouldBeEmpty()
            events shouldContainExactly listOf(Event.DownloadsLeftBehind(listOf(100L), carried = true), Event.Close)
        }

    @Test
    fun `with Downloaded chapters unticked, the event says the downloads were not carried, for the loop and for Migrate now`() =
        runTest(dispatcher) {
            h.preferences.migrationFlags.set(setOf(MigrationFlag.CHAPTER))
            val ids = entries("Alpha Moon", "Bravo Sun", "Delta Road")
            h.leftBehind[10L] = listOf(100L, 101L)
            h.leftBehind[11L] = listOf(110L)
            val viewModel = start(ids)
            val events = h.events(viewModel, backgroundScope)

            viewModel.migrateNow(11L, replace = true)
            advanceUntilIdle()
            viewModel.migrateMangas()
            advanceUntilIdle()

            events shouldContainExactly listOf(
                Event.DownloadsLeftBehind(listOf(110L), carried = false),
                Event.DownloadsLeftBehind(listOf(100L, 101L), carried = false),
            )
            h.migrateCalls.map { it.flags }.distinct() shouldContainExactly listOf(setOf(MigrationFlag.CHAPTER))
        }

    @Test
    fun `a failed Migrate now leaves the row with its match and counts one failure`() = runTest(dispatcher) {
        val viewModel = start(entries("Alpha Moon", "Bravo Sun"))
        val events = h.events(viewModel, backgroundScope)
        h.migrateFailures[10L] = IOException("refused")

        viewModel.migrateNow(10L, replace = true)
        advanceUntilIdle()

        viewModel.state.value.mangaIds shouldContainExactly listOf(10L, 11L)
        (viewModel.result(10) is SearchResult.Success) shouldBe true
        events shouldContainExactly listOf(Event.Failed(1))
        viewModel.state.value.inFlight.shouldBeEmpty()
    }

    @Test
    fun `a failed Migrate now on a row that left the library meanwhile removes it with no failure`() =
        runTest(dispatcher) {
            val viewModel = start(entries("Alpha Moon", "Bravo Sun"))
            val events = h.events(viewModel, backgroundScope)
            h.migrateFailures[10L] = IOException("no longer in the library")
            h.beforeFailure = { id -> h.rows[id] = h.rows.getValue(id).copy(favorite = false) }

            viewModel.migrateNow(10L, replace = true)
            advanceUntilIdle()

            viewModel.state.value.mangaIds shouldContainExactly listOf(11L)
            events.shouldBeEmpty()
            viewModel.state.value.inFlight.shouldBeEmpty()
        }

    @Test
    fun `a picked target replaces the result, and a failed pick is unmatched with one toast, both updating progress`() =
        runTest(dispatcher) {
            val viewModel = start(entries("Alpha Moon", "Bravo Sun", unmatched = setOf("Alpha Moon", "Bravo Sun")))
            val events = h.events(viewModel, backgroundScope)
            h.storedRow(50, sourceId = 2L, url = "/picked", title = "Picked", chapterNumbers = listOf(1.0, 5.0))
            h.storedRow(51, sourceId = 2L, url = "/refused", title = "Refused")
            h.refreshFailures += 51L
            viewModel.state.value.migrationComplete shouldBe false

            viewModel.useMangaForMigration(current = 10L, target = 50L)
            advanceUntilIdle()
            viewModel.matchOf(10)?.id shouldBe 50L
            (viewModel.result(10) as SearchResult.Success).latestChapter shouldBe 5.0
            viewModel.state.value.migrationComplete shouldBe true

            viewModel.useMangaForMigration(current = 11L, target = 51L)
            viewModel.state.value.finishedCount shouldBe 1
            advanceUntilIdle()
            viewModel.result(11) shouldBe SearchResult.NotFound
            viewModel.state.value.finishedCount shouldBe 2
            events shouldContainExactly listOf(Event.MatchWithoutChapters)
        }

    @Test
    fun `a picked target that is one of the batch is refused`() = runTest(dispatcher) {
        val viewModel = start(entries("Alpha Moon", "Bravo Sun", unmatched = setOf("Alpha Moon")))
        h.refreshCalls.clear()

        viewModel.useMangaForMigration(current = 10L, target = 11L)
        advanceUntilIdle()

        viewModel.result(10) shouldBe SearchResult.NotFound
        h.refreshCalls.shouldBeEmpty()
    }

    @Test
    fun `M1 a restored list rebuilds saved outcomes from the database and searches only the rest`() =
        runTest(dispatcher) {
            val titles = listOf("Alpha Moon", "Bravo Sun", "Delta Road", "Echo Tower", "Foxtrot Lake", "Golf Hollow")
            titles.forEachIndexed { index, title -> h.libraryEntry(10L + index, title) }
            titles.drop(3).forEach { h.remoteEntry(source, it) }
            h.storedRow(50, sourceId = 2L, url = "/saved", title = "Saved", chapterNumbers = listOf(1.0, 8.0))
            h.chapters[50L] = listOf(MigrationListHarness.chapter(50L, 0, 1.0), MigrationListHarness.chapter(50L, 1, 8.0))
            h.preferences.migrationFlags.set(setOf(MigrationFlag.CHAPTER))
            val savedFlags = MigrationFlag.toBit(setOf(MigrationFlag.CATEGORY))
            val restored = RestoredOutcomes.of(
                // 13's target is gone; 14's is one of the batch; 15 had no outcome yet.
                outcomes = mapOf(10L to 50L, 11L to 0L, 13L to 99L, 14L to 15L),
                removedIds = setOf(12L),
                flags = savedFlags,
            )

            val viewModel = start((10L..15L).toList(), restored)

            source.searchCalls.map { it.query } shouldContainExactly listOf("Echo Tower", "Foxtrot Lake", "Golf Hollow")
            viewModel.matchOf(10)?.id shouldBe 50L
            (viewModel.result(10) as SearchResult.Success).latestChapter shouldBe 8.0
            h.refreshCalls.filter { it.mangaId == 50L }.shouldBeEmpty()
            viewModel.result(11) shouldBe SearchResult.NotFound
            viewModel.state.value.mangaIds shouldContainExactly listOf(10L, 11L, 13L, 14L, 15L)
            viewModel.state.value.runFlags shouldBe savedFlags

            viewModel.migrateMangas()
            advanceUntilIdle()
            h.migrateCalls.map { it.flags }.distinct() shouldContainExactly listOf(setOf(MigrationFlag.CATEGORY))
        }
}
