package com.shinsaikou.ui.manga

import androidx.lifecycle.viewModelScope
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.AddTracks
import com.shinsaikou.tracking.ApplyTrackChoice
import com.shinsaikou.tracking.CompareTrackProgress
import com.shinsaikou.tracking.DelayedTrackingScheduler
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.DeletableTracker
import com.shinsaikou.tracking.EditTrack
import com.shinsaikou.tracking.EntryRead
import com.shinsaikou.tracking.FakeComparingTracker
import com.shinsaikou.tracking.FakeOAuthTracker
import com.shinsaikou.tracking.OAuthTracker
import com.shinsaikou.tracking.RefreshTracks
import com.shinsaikou.tracking.RemoteProgressOffer
import com.shinsaikou.tracking.RemoveTrack
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.manga.TrackSheetViewModel.Page
import com.shinsaikou.ui.manga.TrackSheetViewModel.SearchResults
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The track sheet's view model (phase 3 unit T3) over `:tracking`'s real interactors, a fake
 * browser sign-in tracker and rows in memory: one row per signed-in tracker that follows the
 * sign-in and its expiry, a refresh once per opening, the search's query and preselection, the
 * writes' return to the home page with a failure kept per tracker, and back from each page. The
 * interactors' own order of calls is `:tracking`'s tests.
 *
 * The interactors run their work on the IO dispatcher, as the app's do, so a test waits for what it
 * expects with [settle] rather than for the test dispatcher alone to go idle, and [tearDown] waits
 * for what is still running there before it resets the main dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackSheetViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)
    private val repository = MemoryTrackRepository()
    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences) { key, value ->
        if (value == null) preferences.values.remove(key) else preferences.values[key] = value
    }
    private val chapterRepository = mockk<ChapterRepository>()
    private val historyRepository = mockk<HistoryRepository>()
    private val mangaRepository = mockk<MangaRepository>()
    private val viewModels = mutableListOf<TrackSheetViewModel>()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns emptyList()
    }

    /**
     * A test ends once it has seen what it expects, which can be before an interactor's work on the
     * IO dispatcher has returned: a refresh goes on after the fake tracker has counted it. That work
     * resumes in the view model's scope on the main dispatcher, so if it returned after
     * `resetMain`, the resume would throw, and the next test to start would fail with
     * `UncaughtExceptionsBeforeTest` (the staging gate on `09f6773`). Each view model's scope is
     * cancelled and the test dispatcher run until the scope has ended before the reset.
     */
    @AfterEach
    fun tearDown() {
        val scopes = viewModels.map { it.viewModelScope.coroutineContext.job }
        viewModels.forEach { it.viewModelScope.cancel() }
        try {
            repeat(SETTLE_ATTEMPTS) {
                testDispatcher.scheduler.advanceUntilIdle()
                if (scopes.all { it.isCompleted }) return
                Thread.sleep(10)
            }
            error("a view model's work on the IO dispatcher never ended")
        } finally {
            KotlinxDispatchers.resetMain()
        }
    }

    /** Runs the test dispatcher until [done], giving the IO dispatcher's work real time between runs. */
    private suspend fun TestScope.settle(done: () -> Boolean) {
        repeat(SETTLE_ATTEMPTS) {
            advanceUntilIdle()
            if (done()) return
            withContext(KotlinxDispatchers.Default) { delay(10) }
        }
        error("the view model never reached the expected state")
    }

    private fun viewModel(vararg trackers: Tracker): TrackSheetViewModel {
        val manager = TrackerManager(trackers.toSet())
        return TrackSheetViewModel(
            mangaId = MANGA_ID,
            trackerManager = manager,
            trackRepository = repository,
            refreshTracks = RefreshTracks(repository, manager, store, DelayedTrackingScheduler {}),
            addTracks = AddTracks(
                repository,
                GetChaptersByMangaId(chapterRepository),
                GetHistory(historyRepository),
                PassThroughTransactionRunner,
            ),
            editTrack = EditTrack(repository, store),
            removeTrack = RemoveTrack(repository, store),
            remoteProgressOffer = RemoteProgressOffer(
                mangaRepository,
                repository,
                CompareTrackProgress(GetFavorites(mangaRepository), repository, chapterRepository, historyRepository, store),
                ApplyTrackChoice(
                    repository,
                    chapterRepository,
                    mangaRepository,
                    PassThroughTransactionRunner,
                    store,
                    RemoveTrack(repository, store),
                ),
            ),
            uiPreferences = UiPreferences(preferences),
            dispatchers = dispatchers,
        ).also { viewModels += it }
    }

    /** Upstream's track dialog reads the owner's date format when it opens (phase 4 unit SR). */
    @Test
    fun `the sheet writes dates in the owner's date format`() {
        UiPreferences(preferences).dateFormat.set("yyyy-MM-dd")

        viewModel().dateFormat().format(java.time.LocalDate.of(2026, 9, 27)) shouldBe "2026-09-27"
    }

    @Test
    fun `one row per signed-in tracker, following the sign-in and its expiry`() = runTest(testDispatcher) {
        val signedIn = FakeOAuthTracker(1, loggedIn = true)
        val signedOut = FakeOAuthTracker(2, loggedIn = false)
        repository.insert(track(trackerId = 1))
        val viewModel = viewModel(signedIn, signedOut)
        advanceUntilIdle()

        viewModel.state.value.rows.map { Triple(it.tracker.id, it.track?.remoteId, it.authExpired) } shouldBe
            listOf(Triple(1L, 1001L, false))

        signedOut.loggedInState.value = true
        signedIn.authExpiredState.value = true
        advanceUntilIdle()

        viewModel.state.value.rows.map { Triple(it.tracker.id, it.track?.remoteId, it.authExpired) } shouldBe
            listOf(Triple(1L, 1001L, true), Triple(2L, null, false))
    }

    @Test
    fun `open refreshes once per opening, skips an expired tracker, and keeps a failure for its row`() = runTest(testDispatcher) {
        val failing = FakeOAuthTracker(1, loggedIn = true).apply { refreshed = { throw IOException("fixture: 503") } }
        val expired = FakeOAuthTracker(2, loggedIn = true, authExpired = true)
        repository.insert(track(trackerId = 1))
        repository.insert(track(trackerId = 2))
        val viewModel = viewModel(failing, expired)

        viewModel.open()
        viewModel.open()
        settle { viewModel.state.value.problems.isNotEmpty() }

        failing.refreshes.size shouldBe 1
        expired.refreshes.shouldBeEmpty()
        viewModel.state.value.problems shouldBe mapOf(1L to TrackSheetViewModel.Problem(ErrorKey.GENERIC, "IOException"))

        viewModel.close()
        failing.refreshed = { it }
        viewModel.open()
        settle { failing.refreshes.size == 2 }
        advanceUntilIdle()

        viewModel.state.value.problems shouldBe emptyMap()
    }

    /**
     * The refresh on open finding MyAnimeList ahead of the chapters read here offers to mark them
     * read up to its number; accepting marks them through Compare's "Mark as read", and the offer
     * goes once the app agrees.
     */
    @Test
    fun `a service ahead of the app is offered after the refresh, and accepting marks the chapters up to it`() =
        runTest(testDispatcher) {
            val tracker = FakeComparingTracker(FakeOAuthTracker(1, loggedIn = true))
            tracker.base.refreshed = { it.copy(lastChapterRead = 12.0) }
            tracker.entry = { EntryRead.OnList(it) }
            val chapters = (1..20).map { number ->
                Chapter.create().copy(id = number.toLong(), mangaId = MANGA_ID, chapterNumber = number.toDouble(), read = number <= 8)
            }.toMutableList()
            coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } answers { synchronized(chapters) { chapters.toList() } }
            coEvery { chapterRepository.updateAll(any()) } answers {
                synchronized(chapters) {
                    firstArg<List<ChapterUpdate>>().forEach { update ->
                        val index = chapters.indexOfFirst { it.id == update.id }
                        chapters[index] = chapters[index].copy(read = update.read ?: chapters[index].read)
                    }
                }
            }
            coEvery { mangaRepository.getMangaById(MANGA_ID) } returns Manga.create().copy(id = MANGA_ID, favorite = true)
            repository.insert(track(trackerId = 1))
            val viewModel = viewModel(tracker)

            viewModel.open()
            settle { viewModel.state.value.offers.isNotEmpty() }
            viewModel.state.value.offers.getValue(1L).chapter shouldBe 12

            viewModel.markReadUpToRemote(1L)
            settle { synchronized(chapters) { chapters.count { it.read } } == 12 }
            advanceUntilIdle()

            synchronized(chapters) { chapters.filter { it.read }.map { it.chapterNumber } } shouldBe (1..12).map(Int::toDouble)
            viewModel.state.value.offers shouldBe emptyMap()
            viewModel.state.value.problems shouldBe emptyMap()
        }

    @Test
    fun `the search starts from the bound title, else the manga's, and preselects the bound entry`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(1, loggedIn = true)
        tracker.searched = { listOf(result(1001), result(1002)) }
        val viewModel = viewModel(tracker)
        advanceUntilIdle()

        viewModel.showSearch(1, "Manga title")
        advanceUntilIdle()
        tracker.searches shouldBe listOf("Manga title")
        (viewModel.state.value.page as Page.Search).selected.shouldBeNull()

        repository.insert(track(trackerId = 1))
        advanceUntilIdle()
        viewModel.back()
        viewModel.showSearch(1, "Manga title")
        advanceUntilIdle()

        tracker.searches shouldBe listOf("Manga title", "Entry 1001")
        val page = viewModel.state.value.page as Page.Search
        page.selected shouldBe result(1001)
        page.results shouldBe SearchResults.Found(listOf(result(1001), result(1002)))
    }

    @Test
    fun `a failed search shows its key on the search page`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(1, loggedIn = true)
        tracker.searched = { throw TrackerAuthExpiredException(1) }
        val viewModel = viewModel(tracker)
        advanceUntilIdle()

        viewModel.showSearch(1, "Manga title")
        advanceUntilIdle()

        val failed = (viewModel.state.value.page as Page.Search).results.shouldBeInstanceOf<SearchResults.Failed>()
        failed.problem.key shouldBe ErrorKey.TRACKER_AUTH_EXPIRED
    }

    @Test
    fun `tracking a selected result binds it and goes back to the home page`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(1, loggedIn = true)
        tracker.searched = { listOf(result(1002)) }
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        viewModel.showSearch(1, "Manga title")
        advanceUntilIdle()

        viewModel.select(result(1002))
        viewModel.bindSelected()
        settle { viewModel.state.value.rows.single().track != null }

        viewModel.state.value.page shouldBe Page.Home
        viewModel.state.value.rows.single().track?.remoteId shouldBe 1002L
    }

    /** AniList's private entries (R162 item 8): bound privately from the search, then toggled on the row. */
    @Test
    fun `a tracker with private entries binds a result privately, and the toggle sends the row's flipped privacy`() =
        runTest(testDispatcher) {
            val tracker = FakeOAuthTracker(1, loggedIn = true, supportsPrivateTracking = true)
            tracker.searched = { listOf(result(1002)) }
            val viewModel = viewModel(tracker)
            advanceUntilIdle()
            viewModel.showSearch(1, "Manga title")
            advanceUntilIdle()

            viewModel.select(result(1002))
            viewModel.bindSelected(private = true)
            settle { viewModel.state.value.rows.single().track != null }

            viewModel.state.value.rows.single().track?.private shouldBe true

            viewModel.togglePrivate(1)
            settle { tracker.remoteEdits.size == 1 }

            tracker.remoteEdits.single().first shouldBe "private"
            tracker.remoteEdits.single().second.private shouldBe false
        }

    @Test
    fun `a write goes back to the home page, keeps its failure for the row, and the next write clears it`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(1, loggedIn = true)
        repository.insert(track(trackerId = 1))
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        store.add(MANGA_ID, 1, 1001, 12.0)

        viewModel.showChapter(1)
        tracker.remoteFailure = IOException("fixture: 503")
        viewModel.setChapter(1, 4)
        settle { viewModel.state.value.problems.isNotEmpty() }

        viewModel.state.value.page shouldBe Page.Home
        viewModel.state.value.problems.keys shouldBe setOf(1L)
        store.getItems().size shouldBe 1

        tracker.remoteFailure = null
        viewModel.setChapter(1, 4)
        settle { tracker.remoteEdits.size == 2 && store.getItems().isEmpty() }

        viewModel.state.value.problems shouldBe emptyMap()
        tracker.remoteEdits.map { (field, sent) -> field to sent.lastChapterRead } shouldBe
            listOf("chapter" to 4.0, "chapter" to 4.0)
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `status, score and a date are sent on the row, and removing a date sends zero`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(1, loggedIn = true)
        repository.insert(track(trackerId = 1))
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        val utcMidnight = Instant.parse("2026-09-01T00:00:00Z").toEpochMilliseconds()

        viewModel.setStatus(1, 2)
        settle { tracker.remoteEdits.size == 1 }
        viewModel.setScore(1, "7")
        settle { tracker.remoteEdits.size == 2 }
        viewModel.setDate(1, start = true, utcMillis = utcMidnight)
        settle { tracker.remoteEdits.size == 3 }
        viewModel.removeDate(1, start = false)
        settle { tracker.remoteEdits.size == 4 }

        tracker.remoteEdits.map { it.first } shouldBe listOf("status", "score", "startDate", "finishDate")
        tracker.remoteEdits[0].second.status shouldBe 2L
        tracker.remoteEdits[1].second.score shouldBe 7.0
        tracker.remoteEdits[2].second.startDate shouldBe
            utcMidnight.convertEpochMillisZone(TimeZone.UTC, TimeZone.currentSystemDefault())
        tracker.remoteEdits[3].second.finishDate shouldBe 0L
    }

    @Test
    fun `remove on an expired sign-in deletes the local row only`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(1, loggedIn = true, authExpired = true)
        repository.insert(track(trackerId = 1))
        val viewModel = viewModel(tracker)
        advanceUntilIdle()

        viewModel.showRemove(1)
        viewModel.remove(alsoRemote = true)
        settle { viewModel.state.value.rows.single().track == null }

        viewModel.state.value.page shouldBe Page.Home
        viewModel.state.value.rows.single().track.shouldBeNull()
        viewModel.state.value.problems shouldBe emptyMap()
    }

    @Test
    fun `remove acts on the row the dialog was opened on, keeping a rebind synced in while it was open`() = runTest(testDispatcher) {
        val tracker = DeletingTracker(FakeOAuthTracker(1, loggedIn = true))
        repository.insert(track(trackerId = 1))
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        val opened = viewModel.state.value.rows.single().track!!

        viewModel.showRemove(1)
        // A sync pull rebinds the pair to another entry while the dialog is up, and a push is
        // queued against the new entry.
        repository.rebind(track(trackerId = 1, remoteId = 2002))
        store.add(MANGA_ID, 1, 2002, 5.0)
        settle { viewModel.state.value.rows.single().track?.remoteId == 2002L }
        viewModel.remove(alsoRemote = true)
        settle { tracker.deletes.isNotEmpty() }

        viewModel.state.value.rows.single().track?.remoteId shouldBe 2002L
        store.getItems().map { it.remoteId to it.chapterNumber } shouldBe listOf(2002L to 5.0)
        tracker.deletes shouldBe listOf(opened)
        viewModel.state.value.problems shouldBe emptyMap()
    }

    @Test
    fun `back goes from the remove-date confirmation to its date picker, and from every other page home`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(1, loggedIn = true)
        repository.insert(track(trackerId = 1))
        val viewModel = viewModel(tracker)
        advanceUntilIdle()

        viewModel.showRemoveDate(1, start = false)
        viewModel.back()
        viewModel.state.value.page shouldBe Page.Date(1, start = false)
        viewModel.back()
        viewModel.state.value.page shouldBe Page.Home
        for (show in listOf(viewModel::showStatus, viewModel::showChapter, viewModel::showScore, viewModel::showRemove)) {
            show(1)
            viewModel.back()
            viewModel.state.value.page shouldBe Page.Home
        }
    }

    @Test
    fun `the date picker offers no future day, no start after the finish and no finish before the start`() {
        val now = Instant.parse("2026-09-27T12:00:00Z")
        val clock = object : Clock {
            override fun now(): Instant = now
        }
        val day = 1.days.inWholeMilliseconds
        val today = Instant.parse("2026-09-27T00:00:00Z").toEpochMilliseconds()
        val finish = today - 10 * day
        val start = today - 20 * day

        trackSelectableDates(start = true, startDate = 0, finishDate = 0, clock = clock, zone = TimeZone.UTC).run {
            isSelectableDate(today) shouldBe true
            isSelectableDate(today + day) shouldBe false
            isSelectableYear(2027) shouldBe false
        }
        trackSelectableDates(start = true, startDate = 0, finishDate = finish, clock = clock, zone = TimeZone.UTC).run {
            isSelectableDate(finish) shouldBe true
            isSelectableDate(finish + day) shouldBe false
        }
        trackSelectableDates(start = false, startDate = start, finishDate = 0, clock = clock, zone = TimeZone.UTC).run {
            isSelectableDate(start) shouldBe true
            isSelectableDate(start - day) shouldBe false
        }
    }

    /**
     * The picker's days are UTC midnights, the row's dates local midnights, and today is the
     * device's day: at 08:00 on the 27th at +09:00 (still the 26th in UTC) today is offered, and at
     * 22:00 on the 27th at -05:00 (already the 28th in UTC) the 28th is not. A start on the finish
     * day, and a finish on the start day, are offered in both zones.
     */
    @Test
    fun `the date picker reads today and the row's dates as days on the device, east and west of UTC`() {
        fun pickerDay(date: String) = LocalDate.parse(date).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        for ((offset, now) in listOf("+09:00" to "2026-09-27T08:00:00+09:00", "-05:00" to "2026-09-27T22:00:00-05:00")) {
            val zone = TimeZone.of(offset)
            val clock = object : Clock {
                override fun now(): Instant = Instant.parse(now)
            }
            fun stored(date: String) = LocalDate.parse(date).atStartOfDayIn(zone).toEpochMilliseconds()

            trackSelectableDates(start = true, startDate = 0, finishDate = 0, clock = clock, zone = zone).run {
                isSelectableDate(pickerDay("2026-09-27")) shouldBe true
                isSelectableDate(pickerDay("2026-09-28")) shouldBe false
                isSelectableYear(2026) shouldBe true
            }
            trackSelectableDates(start = true, startDate = 0, finishDate = stored("2026-09-20"), clock = clock, zone = zone).run {
                isSelectableDate(pickerDay("2026-09-20")) shouldBe true
                isSelectableDate(pickerDay("2026-09-21")) shouldBe false
            }
            trackSelectableDates(start = false, startDate = stored("2026-09-10"), finishDate = 0, clock = clock, zone = zone).run {
                isSelectableDate(pickerDay("2026-09-10")) shouldBe true
                isSelectableDate(pickerDay("2026-09-09")) shouldBe false
            }
        }
    }

    private fun track(trackerId: Long, remoteId: Long = 1001) = Track(
        id = 0,
        mangaId = MANGA_ID,
        trackerId = trackerId,
        remoteId = remoteId,
        libraryId = null,
        title = "Entry $remoteId",
        lastChapterRead = 0.0,
        totalChapters = 12,
        status = 1,
        score = 0.0,
        remoteUrl = "https://tracker.example.invalid/entry/$remoteId",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    private fun result(remoteId: Long) = TrackSearchResult(
        trackerId = 1,
        remoteId = remoteId,
        title = "Entry $remoteId",
        trackingUrl = "https://tracker.example.invalid/entry/$remoteId",
        coverUrl = "",
        summary = "",
        publishingStatus = "",
        publishingType = "",
        startDate = "",
        totalChapters = 12,
        authors = emptyList(),
        artists = emptyList(),
    )

    /** Rows by manga and tracker, as `manga_sync` keys them, behind a flow as the real repository's. */
    private class MemoryTrackRepository : TrackRepository {
        private val rows = MutableStateFlow<List<Track>>(emptyList())
        private var lastId = 0L

        override suspend fun getTrackById(id: Long): Track? = rows.value.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> = rows.value.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = rows
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            rows.map { all -> all.filter { it.mangaId == mangaId } }

        override suspend fun insert(track: Track) {
            delete(track.mangaId, track.trackerId)
            rows.value += track.copy(id = ++lastId)
        }

        override suspend fun insertAll(tracks: List<Track>) = tracks.forEach { insert(it) }
        override suspend fun rebind(track: Track) = insert(track)

        override suspend fun insertIfBoundTo(track: Track, boundRemoteId: Long): Boolean {
            if (getTracksByMangaId(track.mangaId).none { it.trackerId == track.trackerId && it.remoteId == boundRemoteId }) return false
            insert(track)
            return true
        }

        override suspend fun deleteIfBoundTo(mangaId: Long, trackerId: Long, boundRemoteId: Long): Boolean {
            if (getTracksByMangaId(mangaId).none { it.trackerId == trackerId && it.remoteId == boundRemoteId }) return false
            delete(mangaId, trackerId)
            return true
        }

        private fun delete(mangaId: Long, trackerId: Long) {
            rows.value = rows.value.filterNot { it.mangaId == mangaId && it.trackerId == trackerId }
        }
    }

    /** No database here: the block runs as it is. */
    private object PassThroughTransactionRunner : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    /** [FakeOAuthTracker] whose entries can be removed on the service, as MyAnimeList's; each delete is kept. */
    private class DeletingTracker(tracker: FakeOAuthTracker) : OAuthTracker by tracker, DeletableTracker {
        val deletes = mutableListOf<Track>()

        override suspend fun delete(track: Track) {
            deletes += track
        }
    }

    private companion object {
        const val MANGA_ID = 42L
        const val SETTLE_ATTEMPTS = 500
    }
}
