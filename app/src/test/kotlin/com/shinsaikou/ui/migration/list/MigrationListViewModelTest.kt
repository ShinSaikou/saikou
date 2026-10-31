package com.shinsaikou.ui.migration.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.shinsaikou.ui.migration.FakeCatalogueSource
import com.shinsaikou.ui.migration.list.MigratingManga.SearchResult
import com.shinsaikou.ui.migration.list.MigrationListHarness.Companion.OLD_SOURCE
import com.shinsaikou.ui.migration.list.MigrationListHarness.Companion.matchOf
import com.shinsaikou.ui.migration.list.MigrationListHarness.Companion.result
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * The migration list's search (phase 4 unit MB, design g7 final section 19.2): the order entries and
 * sources are searched in, the two matching modes, the requests each hit costs, what a failing
 * source does, and the departures M2, M4, M5, M8, M9 and M11. The view model, its entries' scopes and the
 * engine run on one [StandardTestDispatcher], so a count of held calls after `runCurrent()` is exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val h = MigrationListHarness(dispatcher)

    @BeforeEach
    fun setUp() {
        KotlinDispatchers.setMain(dispatcher)
        h.preferences.enabledLanguages.set(setOf("en"))
        h.source(OLD_SOURCE)
    }

    @AfterEach
    fun tearDown() {
        KotlinDispatchers.resetMain()
    }

    private fun TestScope.start(ids: List<Long>): MigrationListViewModel {
        val viewModel = h.viewModel(ids)
        advanceUntilIdle()
        return viewModel
    }

    private fun FakeCatalogueSource.queries() = searchCalls.map { it.query }

    @Test
    fun `entries are searched one after another`() = runTest(dispatcher) {
        val source = h.source(2)
        h.preferences.migrationSources.set(listOf(2L))
        h.libraryEntry(10, "Alpha Moon")
        h.libraryEntry(11, "Bravo Sun")
        val hold = CompletableDeferred<Unit>()
        source.hold = hold

        val viewModel = h.viewModel(listOf(10L, 11L))
        runCurrent()
        source.queries() shouldContainExactly listOf("Alpha Moon")

        hold.complete(Unit)
        advanceUntilIdle()
        source.queries() shouldContainExactly listOf("Alpha Moon", "Bravo Sun")
        viewModel.result(10) shouldBe SearchResult.NotFound
    }

    @Test
    fun `by default the first source with a hit wins and the later ones are never asked`() = runTest(dispatcher) {
        val empty = h.source(2)
        val first = h.source(3)
        val later = h.source(4)
        h.preferences.migrationSources.set(listOf(2L, 3L, 4L))
        h.libraryEntry(10, "Alpha Moon")
        h.remoteEntry(first, "Alpha Moon")
        h.remoteEntry(later, "Alpha Moon")

        val viewModel = start(listOf(10L))

        empty.queries() shouldContainExactly listOf("Alpha Moon")
        viewModel.matchOf(10)?.source shouldBe 3L
        later.searchCalls.shouldBeEmpty()
    }

    @Test
    fun `matching on chapter number searches five sources at once and the sixth when one ends`() = runTest(dispatcher) {
        val holds = (2L..7L).associateWith { CompletableDeferred<Unit>() }
        val sources = (2L..7L).map { id -> h.source(id).also { it.hold = holds.getValue(id) } }
        h.preferences.migrationSources.set((2L..7L).toList())
        h.preferences.migrationPrioritizeByChapters.set(true)
        h.libraryEntry(10, "Alpha Moon")

        h.viewModel(listOf(10L))
        runCurrent()
        sources.sumOf { it.inFlight } shouldBe 5
        sources.last().searchCalls.shouldBeEmpty()

        holds.getValue(2L).complete(Unit)
        runCurrent()
        sources.last().searchCalls.size shouldBe 1

        holds.values.forEach { it.complete(Unit) }
        advanceUntilIdle()
    }

    @Test
    fun `matching on chapter number drops a hit with no chapters, takes the highest, and a tie goes to the earlier source`() =
        runTest(dispatcher) {
            val noChapters = h.source(2)
            val lower = h.source(3)
            val highest = h.source(4)
            val tied = h.source(5)
            h.preferences.migrationSources.set(listOf(2L, 3L, 4L, 5L))
            h.preferences.migrationPrioritizeByChapters.set(true)
            h.libraryEntry(10, "Alpha Moon")
            h.libraryEntry(11, "Bravo Sun")
            h.remoteEntry(noChapters, "Alpha Moon", chapterNumbers = emptyList())
            h.remoteEntry(lower, "Alpha Moon", chapterNumbers = listOf(1.0, 5.0))
            h.remoteEntry(highest, "Alpha Moon", chapterNumbers = listOf(7.0))
            h.remoteEntry(tied, "Alpha Moon", chapterNumbers = listOf(2.0, 7.0))
            h.remoteEntry(noChapters, "Bravo Sun", chapterNumbers = emptyList())

            val viewModel = start(listOf(10L, 11L))

            viewModel.matchOf(10)?.source shouldBe 4L
            viewModel.result(11) shouldBe SearchResult.NotFound
        }

    @Test
    fun `a hit with the entry's own URL is dropped only on the entry's own source`() = runTest(dispatcher) {
        val own = h.sources.getValue(OLD_SOURCE)
        val other = h.source(2)
        h.preferences.migrationSources.set(listOf(OLD_SOURCE, 2L))
        h.libraryEntry(10, "Alpha Moon", url = "/shared/10")
        h.libraryEntry(11, "Bravo Sun", url = "/shared/11")
        // Entry 10: its own row on its own source is dropped, the same URL on another source is kept.
        h.remoteEntry(own, "Alpha Moon", url = "/shared/10")
        h.remoteEntry(other, "Alpha Moon", url = "/shared/10")
        // Entry 11: another URL on its own source is kept.
        h.remoteEntry(own, "Bravo Sun", url = "/moved/11")

        val viewModel = start(listOf(10L, 11L))

        viewModel.matchOf(10)?.let { it.source to it.url } shouldBe (2L to "/shared/10")
        viewModel.matchOf(11)?.let { it.source to it.url } shouldBe (OLD_SOURCE to "/moved/11")
    }

    @Test
    fun `M4 a hit on another entry of the batch is discarded before any chapter call and the next source is tried`() =
        runTest(dispatcher) {
            val first = h.source(2)
            val next = h.source(3)
            h.preferences.migrationSources.set(listOf(2L, 3L))
            h.libraryEntry(10, "Alpha Moon")
            h.libraryEntry(11, "Bravo Sun", sourceId = 2L, url = "/b/11")
            h.remoteEntry(first, "Alpha Moon", url = "/b/11")
            h.remoteEntry(next, "Alpha Moon")

            val viewModel = start(listOf(10L, 11L))

            viewModel.matchOf(10)?.source shouldBe 3L
            h.refreshCalls.filter { it.mangaId == 11L }.shouldBeEmpty()
        }

    @Test
    fun `the hide switches remove unmatched entries and matches without a higher chapter, an equal one included`() =
        runTest(dispatcher) {
            val source = h.source(2)
            h.preferences.migrationSources.set(listOf(2L))
            h.libraryEntry(10, "Alpha Moon", chapterNumbers = listOf(1.0, 3.0))
            h.libraryEntry(11, "Bravo Sun", chapterNumbers = listOf(1.0, 3.0))
            h.libraryEntry(12, "Delta Road")
            h.remoteEntry(source, "Alpha Moon", chapterNumbers = listOf(2.0, 3.0))
            h.remoteEntry(source, "Bravo Sun", chapterNumbers = listOf(4.0))

            val shown = start(listOf(10L, 11L, 12L))
            shown.state.value.mangaIds shouldContainExactly listOf(10L, 11L, 12L)

            h.preferences.migrationHideUnmatched.set(true)
            h.preferences.migrationHideWithoutUpdates.set(true)
            val hidden = start(listOf(10L, 11L, 12L))
            hidden.state.value.mangaIds shouldContainExactly listOf(11L)
        }

    @Test
    fun `a source throwing IOException is passed over in both modes`() = runTest(dispatcher) {
        val failing = h.source(2)
        val working = h.source(3)
        h.preferences.migrationSources.set(listOf(2L, 3L))
        h.libraryEntry(10, "Alpha Moon")
        failing.searchFailures["Alpha Moon"] = IOException("refused")
        h.remoteEntry(working, "Alpha Moon")

        start(listOf(10L)).matchOf(10)?.source shouldBe 3L

        h.preferences.migrationPrioritizeByChapters.set(true)
        start(listOf(10L)).matchOf(10)?.source shouldBe 3L
    }

    @Test
    fun `a source that does not answer within its deadline is passed over, even one that ignores cancellation`() =
        runTest(dispatcher) {
            listOf(false, true).forEach { ignoresCancellation ->
                val dead = h.source(2)
                val working = h.source(3)
                h.preferences.migrationSources.set(listOf(2L, 3L))
                h.libraryEntry(10, "Alpha Moon")
                h.remoteEntry(working, "Alpha Moon")
                val hold = CompletableDeferred<Unit>()
                dead.hold = hold
                dead.holdIgnoresCancellation = ignoresCancellation

                val viewModel = h.viewModel(listOf(10L))
                advanceTimeBy(24_000)
                runCurrent()
                working.searchCalls.shouldBeEmpty()
                viewModel.result(10) shouldBe SearchResult.Searching

                advanceTimeBy(1_001)
                runCurrent()
                working.queries() shouldContainExactly listOf("Alpha Moon")
                advanceUntilIdle()
                viewModel.matchOf(10)?.source shouldBe 3L
                // The ignoring search is still on its thread, abandoned; a cancellable one has ended.
                dead.inFlight shouldBe if (ignoresCancellation) 1 else 0

                hold.complete(Unit)
                advanceUntilIdle()
                dead.inFlight shouldBe 0
            }
        }

    @Test
    fun `a source throwing CancellationException cancels the entry's search without calling it unmatched`() =
        runTest(dispatcher) {
            val source = h.source(2)
            h.preferences.migrationSources.set(listOf(2L))
            h.libraryEntry(10, "Alpha Moon")
            h.libraryEntry(11, "Bravo Sun")
            source.searchFailures["Alpha Moon"] = CancellationException("stopped")
            h.remoteEntry(source, "Bravo Sun")

            val viewModel = start(listOf(10L, 11L))

            viewModel.result(10) shouldBe SearchResult.Searching
            viewModel.matchOf(11)?.source shouldBe 2L
        }

    @Test
    fun `nothing is searched until the registry has loaded, then the saved order is used`() = runTest(dispatcher) {
        val second = h.source(2)
        val first = h.source(3)
        h.preferences.migrationSources.set(listOf(3L, 2L))
        h.libraryEntry(10, "Alpha Moon")
        h.initialized.value = false
        val hold = CompletableDeferred<Unit>()
        first.hold = hold

        h.viewModel(listOf(10L))
        advanceUntilIdle()
        first.searchCalls.shouldBeEmpty()
        second.searchCalls.shouldBeEmpty()

        h.initialized.value = true
        // runCurrent, not advanceUntilIdle: idling would move the clock past the held search's deadline.
        runCurrent()
        first.queries() shouldContainExactly listOf("Alpha Moon")
        second.searchCalls.shouldBeEmpty()

        hold.complete(Unit)
        advanceUntilIdle()
        second.queries() shouldContainExactly listOf("Alpha Moon")
    }

    @Test
    fun `a hit costs one search and one chapter fetch, a coverless winner one details fetch more, a loser none`() =
        runTest(dispatcher) {
            val source = h.source(2)
            h.preferences.migrationSources.set(listOf(2L))
            h.libraryEntry(10, "Alpha Moon")
            h.libraryEntry(11, "Bravo Sun")
            h.remoteEntry(source, "Alpha Moon", cover = "has-a-cover")
            h.remoteEntry(source, "Bravo Sun", cover = null)

            val viewModel = start(listOf(10L, 11L))
            val covered = viewModel.matchOf(10)!!.id
            val coverless = viewModel.matchOf(11)!!.id

            source.searchCalls.size shouldBe 2
            h.refreshCalls.filter { it.mangaId == covered } shouldContainExactly listOf(
                MigrationListHarness.RefreshCall(2L, covered, fetchDetails = false, fetchChapters = true, manualFetch = false),
            )
            h.refreshCalls.filter { it.mangaId == coverless } shouldContainExactly listOf(
                MigrationListHarness.RefreshCall(2L, coverless, fetchDetails = false, fetchChapters = true, manualFetch = false),
                MigrationListHarness.RefreshCall(2L, coverless, fetchDetails = true, fetchChapters = false, manualFetch = true),
            )

            // On chapter number, the losers' chapters are fetched to compare, and nothing more.
            val loser = h.source(3)
            val winner = h.source(4)
            h.preferences.migrationSources.set(listOf(3L, 4L))
            h.preferences.migrationPrioritizeByChapters.set(true)
            h.libraryEntry(12, "Delta Road")
            h.remoteEntry(loser, "Delta Road", cover = null, chapterNumbers = listOf(1.0))
            h.remoteEntry(winner, "Delta Road", cover = null, chapterNumbers = listOf(9.0))
            h.refreshCalls.clear()

            val chapterMode = start(listOf(12L))
            val loserId = h.rowIdOf(3L, "/remote/3/delta-road")
            chapterMode.matchOf(12)?.source shouldBe 4L
            h.refreshCalls.filter { it.mangaId == loserId } shouldContainExactly listOf(
                MigrationListHarness.RefreshCall(3L, loserId, fetchDetails = false, fetchChapters = true, manualFetch = false),
            )
            h.refreshCalls.count { it.fetchDetails } shouldBe 1
        }

    @Test
    fun `M11 a coverless winner is drawn from its row as the details fetch left it`() = runTest(dispatcher) {
        val source = h.source(2)
        h.preferences.migrationSources.set(listOf(2L))
        h.libraryEntry(10, "Alpha Moon")
        h.remoteEntry(source, "Alpha Moon", cover = null)

        val viewModel = start(listOf(10L))

        viewModel.matchOf(10)?.thumbnailUrl shouldBe MigrationListHarness.FETCHED_COVER
        viewModel.matchOf(10)?.description shouldBe "from the details"
    }

    @Test
    fun `the close icon cancels only its own entry and the next entry is searched`() = runTest(dispatcher) {
        val source = h.source(2)
        h.preferences.migrationSources.set(listOf(2L))
        h.libraryEntry(10, "Alpha Moon")
        h.libraryEntry(11, "Bravo Sun")
        h.remoteEntry(source, "Bravo Sun")
        val hold = CompletableDeferred<Unit>()
        source.hold = hold

        val viewModel = h.viewModel(listOf(10L, 11L))
        runCurrent()
        source.inFlight shouldBe 1

        viewModel.removeManga(10L)
        runCurrent()
        source.queries() shouldContainExactly listOf("Alpha Moon", "Bravo Sun")
        source.inFlight shouldBe 1
        viewModel.state.value.mangaIds shouldContainExactly listOf(11L)

        hold.complete(Unit)
        advanceUntilIdle()
        viewModel.matchOf(11)?.source shouldBe 2L
    }

    @Test
    fun `leaving the screen cancels every held call`() = runTest(dispatcher) {
        val sources = listOf(h.source(2), h.source(3))
        sources.forEach { it.hold = CompletableDeferred() }
        h.preferences.migrationSources.set(listOf(2L, 3L))
        h.preferences.migrationPrioritizeByChapters.set(true)
        h.libraryEntry(10, "Alpha Moon")
        val store = ViewModelStore()
        val built = h.viewModel(listOf(10L))
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = built as T
            },
        )[MigrationListViewModel::class.java]
        runCurrent()
        sources.sumOf { it.inFlight } shouldBe 2

        store.clear()
        advanceUntilIdle()

        sources.sumOf { it.inFlight } shouldBe 0
    }

    @Test
    fun `M2 an entry out of the library when the list is built is left out and never searched`() = runTest(dispatcher) {
        val source = h.source(2)
        h.preferences.migrationSources.set(listOf(2L))
        h.libraryEntry(10, "Alpha Moon")
        h.libraryEntry(11, "Bravo Sun")
        h.libraryEntry(12, "Delta Road")
        h.rows[10] = h.rows.getValue(10).copy(favorite = false)
        h.rows[11] = h.rows.getValue(11).copy(deletedAt = 99L)

        val viewModel = start(listOf(10L, 11L, 12L, 13L))

        viewModel.state.value.mangaIds shouldContainExactly listOf(12L)
        source.queries() shouldContainExactly listOf("Delta Road")
        viewModel.state.value.removedIds shouldBe setOf(10L, 11L, 13L)
    }

    @Test
    fun `M8 the flags are read when the list starts and every write gets them`() = runTest(dispatcher) {
        val source = h.source(2)
        h.preferences.migrationSources.set(listOf(2L))
        h.preferences.migrationFlags.set(setOf(MigrationFlag.CHAPTER))
        h.libraryEntry(10, "Alpha Moon")
        h.libraryEntry(11, "Bravo Sun")
        h.remoteEntry(source, "Alpha Moon")
        h.remoteEntry(source, "Bravo Sun")

        val viewModel = start(listOf(10L, 11L))
        h.preferences.migrationFlags.set(setOf(MigrationFlag.CATEGORY, MigrationFlag.NOTES))
        viewModel.migrateNow(10L, replace = true)
        advanceUntilIdle()
        viewModel.migrateMangas()
        advanceUntilIdle()

        h.migrateCalls.map { it.current.id } shouldContainExactly listOf(10L, 11L)
        h.migrateCalls.map { it.flags }.distinct() shouldContainExactly listOf(setOf(MigrationFlag.CHAPTER))
        viewModel.state.value.runFlags shouldBe MigrationFlag.toBit(setOf(MigrationFlag.CHAPTER))
    }

    @Test
    fun `M5 a match is marked in the library when the favourite ids hold it, and when they hold it later`() =
        runTest(dispatcher) {
            val source = h.source(2)
            h.preferences.migrationSources.set(listOf(2L))
            h.libraryEntry(10, "Alpha Moon")
            h.libraryEntry(11, "Bravo Sun")
            h.storedRow(60, sourceId = 2L, url = "/favourite", title = "Alpha Moon", favorite = true)
            h.remoteEntry(source, "Alpha Moon", url = "/favourite")
            h.remoteEntry(source, "Bravo Sun")
            h.favoriteIds.value = setOf(10L, 11L, 60L)

            val viewModel = start(listOf(10L, 11L))
            val later = viewModel.matchOf(11)!!.id
            viewModel.state.value.favoriteIds.contains(60L) shouldBe true
            viewModel.state.value.favoriteIds.contains(later) shouldBe false

            h.favoriteIds.value = setOf(10L, 11L, 60L, later)
            advanceUntilIdle()
            viewModel.state.value.favoriteIds.contains(later) shouldBe true
        }

    @Test
    fun `M9 the screen is kept awake while searching, fetching a picked target, and writing, and not after`() =
        runTest(dispatcher) {
            val source = h.source(2)
            h.preferences.migrationSources.set(listOf(2L))
            h.libraryEntry(10, "Alpha Moon")
            h.libraryEntry(11, "Bravo Sun")
            h.libraryEntry(12, "Delta Road")
            h.remoteEntry(source, "Alpha Moon")
            h.remoteEntry(source, "Bravo Sun")
            h.remoteEntry(source, "Delta Road")
            h.storedRow(50, sourceId = 2L, url = "/picked", title = "Picked")
            val searchHold = CompletableDeferred<Unit>()
            source.hold = searchHold

            val viewModel = h.viewModel(listOf(10L, 11L, 12L))
            runCurrent()
            viewModel.state.value.keepAwake shouldBe true
            searchHold.complete(Unit)
            advanceUntilIdle()
            viewModel.state.value.keepAwake shouldBe false

            val pickHold = CompletableDeferred<Unit>()
            h.refreshHold = pickHold
            viewModel.useMangaForMigration(current = 10L, target = 50L)
            runCurrent()
            viewModel.state.value.keepAwake shouldBe true
            pickHold.complete(Unit)
            advanceUntilIdle()
            viewModel.state.value.keepAwake shouldBe false
            viewModel.matchOf(10)?.id shouldBe 50L

            val nowHold = CompletableDeferred<Unit>()
            h.migrateHold = nowHold
            viewModel.migrateNow(11L, replace = true)
            runCurrent()
            viewModel.state.value.keepAwake shouldBe true
            nowHold.complete(Unit)
            advanceUntilIdle()
            viewModel.state.value.keepAwake shouldBe false

            val loopHold = CompletableDeferred<Unit>()
            h.migrateHold = loopHold
            viewModel.migrateMangas()
            runCurrent()
            viewModel.state.value.keepAwake shouldBe true
            loopHold.complete(Unit)
            advanceUntilIdle()
            viewModel.state.value.keepAwake shouldBe false
            h.migrateCalls.map { it.current.id } shouldContainExactly listOf(11L, 10L, 12L)
        }
}
