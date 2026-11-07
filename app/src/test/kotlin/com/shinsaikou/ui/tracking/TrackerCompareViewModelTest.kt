package com.shinsaikou.ui.tracking

import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.SyncRunRecord
import com.shinsaikou.sync.SyncScheduler
import com.shinsaikou.sync.SyncWorkPreferences
import com.shinsaikou.tracking.ApplyTrackChoice
import com.shinsaikou.tracking.CompareChoice
import com.shinsaikou.tracking.CompareSection
import com.shinsaikou.tracking.CompareTrackProgress
import com.shinsaikou.tracking.CompareWarning
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.EntryRead
import com.shinsaikou.tracking.FakeComparingTracker
import com.shinsaikou.tracking.ListEntry
import com.shinsaikou.tracking.ListRead
import com.shinsaikou.tracking.RemoveTrack
import com.shinsaikou.tracking.StopKind
import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.ConfirmSummary
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.Event
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.RowResult
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.Screen
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.SetBack
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.SyncNotice
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.util.Collections
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * "Compare with MyAnimeList"'s view model (phase 4 unit TC, the design's tests 43 to 50) over
 * `:tracking`'s real `CompareTrackProgress` and `ApplyTrackChoice`, a fake comparing tracker, the
 * library, chapters and rows in memory, and WorkManager mocked with its `sync-once` flow, as Sync
 * settings' test mocks it.
 *
 * The library, by the design's section 6 (no count on the service unless named, status Reading):
 * - Alpha: chapters 1 to 10 with a second copy of 7, 1 to 5 read; the service at 10. The service is
 *   ahead, with 6 to 10 to mark (six rows).
 * - Bravo: chapters 1 to 8 read, 8 opened in the reader; the service at 2 of 8. The app is ahead,
 *   and an update to 8 completes it.
 * - Charlie: chapters 1 to 6 read, none opened; the service at 3. The app is ahead, warned.
 * - Delta: chapters 1 to 4 read; the service at 9. The service is ahead with nothing to mark.
 * - Echo: chapters 1 to 3 read; absent from the list, and off it when read alone.
 *
 * The interactors run their work on the IO dispatcher, as the app's do, so a test waits for what it
 * expects with [settle], which runs only the work due now between real sleeps: the sync's wait is a
 * virtual-time timeout, and a test that let the scheduler idle would time it out.
 *
 * Every wait on that IO work is bounded by [SETTLE_TIMEOUT] of wall time, a hang guard: Apply's rows
 * run on the real `Dispatchers.IO` through `withIOContext`, which no test dispatcher holds, and a
 * bound of 500 ten-millisecond sleeps, sized for an idle machine, ran out before a five-row Apply
 * finished at a load average of 51 (phase 5 unit TG1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerCompareViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)
    private val values = mutableMapOf<String, Any>()
    private val preferences = MapPreferenceStore(values)
    private val syncPreferences = SyncPreferences(preferences)
    private val record = SyncRunRecord(preferences)
    private val once = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val workManager = mockk<WorkManager>(relaxed = true).apply {
        every { getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NAME_ONCE) } returns once
    }
    private val scheduler = SyncScheduler(lazyOf(workManager), syncPreferences, SyncWorkPreferences(preferences))
    private val store = DelayedTrackingStore(preferences) { key, value ->
        if (value == null) preferences.values.remove(key) else preferences.values[key] = value
    }
    private val tracks = MemoryTrackRepository()
    private val mangaRepository = mockk<MangaRepository>()
    private val chapterRepository = mockk<ChapterRepository>()
    private val historyRepository = mockk<HistoryRepository>()
    private val tracker = FakeComparingTracker()
    private val viewModels = mutableListOf<TrackerCompareViewModel>()

    /** Every chapter read and the test's own marks, in order ("chapters", "sync finished"). */
    private val order: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var chapters: List<Chapter> = emptyList()
    private val manga = mutableMapOf<Long, Manga>()

    /** The service's answer to a read of one entry, by remote id. */
    private val answers = ConcurrentHashMap<Long, suspend () -> EntryRead>()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        library()
        coEvery { mangaRepository.getFavorites() } answers { manga.values.filter { it.favorite } }
        coEvery { mangaRepository.getMangaById(any()) } answers { manga.getValue(firstArg()) }
        coEvery { chapterRepository.getChapterByMangaId(any(), any()) } answers {
            order += "chapters"
            chapters.filter { it.mangaId == firstArg<Long>() }
        }
        coEvery { chapterRepository.updateAll(any()) } answers {
            val updates = firstArg<List<ChapterUpdate>>().associateBy { it.id }
            chapters = chapters.map { chapter -> if (updates[chapter.id]?.read == true) chapter.copy(read = true) else chapter }
        }
        coEvery { historyRepository.getHistoryByMangaId(any()) } returns emptyList()
        coEvery { historyRepository.getHistoryByMangaId(BRAVO) } returns listOf(History(1, chapterId(BRAVO, 8), Date(), 0))
        tracker.list = {
            ListRead.Entries(
                mapOf(
                    remoteId(ALPHA) to ListEntry(remoteId(ALPHA), 10, 0, READING),
                    remoteId(BRAVO) to ListEntry(remoteId(BRAVO), 2, 8, READING),
                    remoteId(CHARLIE) to ListEntry(remoteId(CHARLIE), 3, 0, READING),
                    remoteId(DELTA) to ListEntry(remoteId(DELTA), 9, 0, READING),
                ),
            )
        }
        answers[remoteId(ECHO)] = { EntryRead.OffList(track(ECHO)) }
        tracker.entry = { track -> answers.getValue(track.remoteId)() }
    }

    /** As the track sheet's test: each view model's scope cancelled and its IO work ended before the reset. */
    @AfterEach
    fun tearDown() {
        val scopes = viewModels.map { it.viewModelScope.coroutineContext.job }
        viewModels.forEach { it.viewModelScope.cancel() }
        try {
            val deadline = TimeSource.Monotonic.markNow() + SETTLE_TIMEOUT
            while (deadline.hasNotPassedNow()) {
                testDispatcher.scheduler.runCurrent()
                if (scopes.all { it.isCompleted }) return
                Thread.sleep(10)
            }
            error("a view model's work on the IO dispatcher never ended")
        } finally {
            KotlinxDispatchers.resetMain()
        }
    }

    private fun library() {
        listOf(ALPHA to "Alpha", BRAVO to "Bravo", CHARLIE to "Charlie", DELTA to "Delta", ECHO to "Echo").forEach { (id, title) ->
            manga[id] = Manga.create().copy(id = id, title = title, favorite = true)
        }
        chapters = chapters(ALPHA, 1..10, readThrough = 5) + chapter(ALPHA, 7.0, copy = true) +
            chapters(BRAVO, 1..8, readThrough = 8) +
            chapters(CHARLIE, 1..6, readThrough = 6) +
            chapters(DELTA, 1..4, readThrough = 4) +
            chapters(ECHO, 1..3, readThrough = 3)
        listOf(ALPHA, BRAVO, CHARLIE, DELTA, ECHO).forEach { tracks.put(track(it)) }
    }

    private fun viewModel(): TrackerCompareViewModel {
        val removeTrack = RemoveTrack(tracks, store)
        return TrackerCompareViewModel(
            trackerId = tracker.id,
            trackerManager = TrackerManager(setOf(tracker)),
            compareTrackProgress = CompareTrackProgress(GetFavorites(mangaRepository), tracks, chapterRepository, historyRepository, store),
            applyTrackChoice = ApplyTrackChoice(tracks, chapterRepository, mangaRepository, PassThroughTransactionRunner, store, removeTrack, mockk(relaxed = true)),
            syncScheduler = scheduler,
            syncRunRecord = record,
            workManager = workManager,
            dispatchers = dispatchers,
        ).also { viewModels += it }
    }

    /**
     * Runs the work due now until [done], sleeping for real between runs so the IO dispatcher's work
     * lands. It never lets virtual time move: the sync's 60 second wait stays pending until a test
     * advances it.
     */
    private fun TestScope.settle(done: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + SETTLE_TIMEOUT
        while (deadline.hasNotPassedNow()) {
            runCurrent()
            if (done()) return
            Thread.sleep(10)
        }
        error("the view model never reached the expected state")
    }

    private fun TrackerCompareViewModel.compared(): Screen.Compared = state.value.screen as Screen.Compared

    private fun TrackerCompareViewModel.row(mangaId: Long) = compared().rows.single { it.mangaId == mangaId }

    private fun TestScope.comparedViewModel(): TrackerCompareViewModel {
        val viewModel = viewModel()
        settle { viewModel.state.value.screen is Screen.Compared }
        return viewModel
    }

    private fun signInToSync() = syncPreferences.backendId.set("webdav-0123456789abcdef")

    private fun workInfo(state: WorkInfo.State) = listOf(WorkInfo(UUID.randomUUID(), state, setOf(SyncScheduler.TAG)))

    // Test 43.
    @Test
    fun `every row starts on don't change and Apply is off until a choice changes something`() = runTest(testDispatcher) {
        val viewModel = comparedViewModel()

        viewModel.compared().rows.map { it.mangaId to it.entry.section } shouldBe listOf(
            ALPHA to CompareSection.REMOTE_AHEAD,
            DELTA to CompareSection.REMOTE_AHEAD,
            BRAVO to CompareSection.APP_AHEAD,
            CHARLIE to CompareSection.APP_AHEAD,
            ECHO to CompareSection.NOT_ON_LIST,
        )
        viewModel.compared().rows.map { it.choice }.toSet() shouldBe setOf(CompareChoice.DONT_CHANGE)
        viewModel.state.value.applyShown shouldBe true
        viewModel.state.value.applyEnabled shouldBe false

        viewModel.choose(ALPHA, CompareChoice.MARK_READ)
        viewModel.state.value.applyEnabled shouldBe true
        viewModel.choose(ALPHA, CompareChoice.DONT_CHANGE)
        viewModel.state.value.applyEnabled shouldBe false
        // A choice the row does not offer is not taken.
        viewModel.choose(DELTA, CompareChoice.MARK_READ)
        viewModel.row(DELTA).choice shouldBe CompareChoice.DONT_CHANGE
    }

    // Test 44.
    @Test
    fun `with a backend the chapters are read only after the sync run finishes, and it succeeded so no line shows`() =
        runTest(testDispatcher) {
            signInToSync()
            val viewModel = viewModel()
            settle { tracker.listReads.isNotEmpty() && (viewModel.state.value.screen as? Screen.Reading)?.syncing == true }
            runCurrent()

            order.filter { it == "chapters" }.shouldBeEmpty()
            verify(exactly = 1) {
                workManager.enqueueUniqueWork(SyncScheduler.WORK_NAME_ONCE, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
            }

            order += "sync finished"
            record.lastSuccessAt.set(System.currentTimeMillis())
            once.value = workInfo(WorkInfo.State.SUCCEEDED)
            settle { viewModel.state.value.screen is Screen.Compared }

            order.first() shouldBe "sync finished"
            (order.count { it == "chapters" } > 0) shouldBe true
            viewModel.compared().sync.shouldBeNull()
        }

    // Test 44.
    @Test
    fun `a failed or timed out sync run shows the last synced line, and without a backend nothing is synced or shown`() =
        runTest(testDispatcher) {
            signInToSync()
            record.lastSuccessAt.set(1_000L)
            val failed = viewModel()
            settle { (failed.state.value.screen as? Screen.Reading)?.syncing == true }
            once.value = workInfo(WorkInfo.State.RUNNING)
            runCurrent()
            once.value = workInfo(WorkInfo.State.FAILED)
            settle { failed.state.value.screen is Screen.Compared }
            failed.compared().sync shouldBe SyncNotice(1_000L)

            record.lastSuccessAt.set(0L)
            once.value = workInfo(WorkInfo.State.RUNNING)
            val timedOut = viewModel()
            settle { tracker.listReads.size == 2 && (timedOut.state.value.screen as? Screen.Reading)?.syncing == true }
            runCurrent()
            timedOut.state.value.screen.shouldBeInstanceOf<Screen.Reading>()
            testScheduler.advanceTimeBy(TrackerCompareViewModel.SYNC_WAIT.inWholeMilliseconds + 1)
            settle { timedOut.state.value.screen is Screen.Compared }
            timedOut.compared().sync shouldBe SyncNotice(0L)

            syncPreferences.backendId.set("")
            val unsynced = comparedViewModel()
            unsynced.compared().sync.shouldBeNull()
            verify(exactly = 2) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
        }

    // Test 44.
    @Test
    fun `a list read that stops ends the wait for the sync at once`() = runTest(testDispatcher) {
        signInToSync()
        once.value = workInfo(WorkInfo.State.RUNNING)
        tracker.list = { throw HttpException(503) }
        val viewModel = viewModel()
        settle { viewModel.state.value.screen is Screen.Stopped }

        testScheduler.currentTime shouldBe 0L
        (viewModel.state.value.screen as Screen.Stopped).stop.kind shouldBe StopKind.OTHER
        order.filter { it == "chapters" }.shouldBeEmpty()
    }

    // Test 45.
    @Test
    fun `keep the higher number skips warned rows, rows with nothing to mark and rows off the list, and don't change any resets`() =
        runTest(testDispatcher) {
            val viewModel = comparedViewModel()
            viewModel.row(CHARLIE).entry.warnings shouldBe setOf(CompareWarning.NOT_OPENED)
            viewModel.choose(ECHO, CompareChoice.REMOVE_TRACKING)

            viewModel.keepHigher()

            viewModel.compared().rows.associate { it.mangaId to it.choice } shouldBe mapOf(
                ALPHA to CompareChoice.MARK_READ,
                DELTA to CompareChoice.DONT_CHANGE,
                BRAVO to CompareChoice.UPDATE_REMOTE,
                CHARLIE to CompareChoice.DONT_CHANGE,
                ECHO to CompareChoice.REMOVE_TRACKING,
            )
            viewModel.events.first() shouldBe Event.ChoseHigher(chose = 2, left = 1)

            viewModel.dontChangeAny()

            viewModel.compared().rows.map { it.choice }.toSet() shouldBe setOf(CompareChoice.DONT_CHANGE)
            viewModel.state.value.applyEnabled shouldBe false
        }

    // Test 46.
    @Test
    fun `the confirmation counts the changes, the chapter rows per copy, each set back, and the completed entries`() =
        runTest(testDispatcher) {
            val viewModel = comparedViewModel()
            viewModel.choose(ALPHA, CompareChoice.MARK_READ)
            viewModel.choose(DELTA, CompareChoice.SET_REMOTE_BACK)
            viewModel.choose(BRAVO, CompareChoice.UPDATE_REMOTE)
            viewModel.choose(ECHO, CompareChoice.REMOVE_TRACKING)

            viewModel.requestApply()

            viewModel.state.value.confirming shouldBe ConfirmSummary(
                changes = 4,
                markChapters = 6,
                markEntries = 1,
                updates = 1,
                setBacks = listOf(SetBack("Delta", from = 9, to = 4)),
                completed = 1,
                removals = 1,
            )
            viewModel.dismissConfirm()
            viewModel.state.value.confirming.shouldBeNull()
        }

    // Test 47.
    @Test
    fun `after Apply done rows leave, changed rows stay without a choice, failed and unreached rows keep theirs`() =
        runTest(testDispatcher) {
            signInToSync()
            record.lastSuccessAt.set(Long.MAX_VALUE / 2)
            val viewModel = comparedViewModel()
            viewModel.choose(ALPHA, CompareChoice.MARK_READ)
            viewModel.choose(DELTA, CompareChoice.SET_REMOTE_BACK)
            viewModel.choose(BRAVO, CompareChoice.UPDATE_REMOTE)
            viewModel.choose(CHARLIE, CompareChoice.UPDATE_REMOTE)
            viewModel.choose(ECHO, CompareChoice.REMOVE_TRACKING)
            // Chapter 7's first copy is renumbered after the comparison: it drops out of the marks.
            chapters = chapters.map { if (it.id == chapterId(ALPHA, 7)) it.copy(chapterNumber = 107.0) else it }
            answers[remoteId(ALPHA)] = { EntryRead.OnList(track(ALPHA).copy(lastChapterRead = 10.0)) }
            answers[remoteId(DELTA)] = { throw HttpException(400) }
            answers[remoteId(BRAVO)] = { EntryRead.OnList(track(BRAVO).copy(lastChapterRead = 2.0, totalChapters = 8)) }
            tracker.sent = { track, _ -> track.copy(lastChapterRead = 7.0) }
            answers[remoteId(CHARLIE)] = { throw HttpException(503) }

            viewModel.requestApply()
            viewModel.apply()
            settle { viewModel.state.value.applying == null }

            viewModel.compared().rows.map { Triple(it.mangaId, it.choice, it.result) } shouldBe listOf(
                Triple(ALPHA, null, RowResult.PartlyMarked(marked = 5, listed = 6)),
                Triple(DELTA, CompareChoice.SET_REMOTE_BACK, viewModel.row(DELTA).result),
                Triple(BRAVO, null, RowResult.Kept(7)),
                Triple(CHARLIE, CompareChoice.UPDATE_REMOTE, viewModel.row(CHARLIE).result),
                Triple(ECHO, CompareChoice.REMOVE_TRACKING, null),
            )
            viewModel.row(DELTA).result.shouldBeInstanceOf<RowResult.Failed>()
            viewModel.row(CHARLIE).result.shouldBeInstanceOf<RowResult.Failed>()
            viewModel.compared().runStop?.let { Triple(it.reached, it.total, it.stop.kind) } shouldBe Triple(4, 5, StopKind.OTHER)
            viewModel.state.value.applyEnabled shouldBe true
            viewModel.events.first() shouldBe Event.Applied(done = 1, changed = 0, failed = 3)
            verify(exactly = 1) {
                workManager.enqueueUniqueWork(SyncScheduler.WORK_NAME_ONCE, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
            }

            answers[remoteId(DELTA)] = { EntryRead.OnList(track(DELTA).copy(lastChapterRead = 9.0)) }
            tracker.sent = { track, number -> track.copy(lastChapterRead = number.toDouble()) }
            answers[remoteId(CHARLIE)] = { EntryRead.OnList(track(CHARLIE).copy(lastChapterRead = 4.0)) }
            viewModel.requestApply()
            viewModel.state.value.confirming?.changes shouldBe 3
            viewModel.apply()
            settle { viewModel.state.value.applying == null }

            viewModel.compared().rows.map { Triple(it.mangaId, it.choice, it.result) } shouldBe listOf(
                Triple(ALPHA, null, RowResult.PartlyMarked(marked = 5, listed = 6)),
                Triple(BRAVO, null, RowResult.Kept(7)),
                Triple(CHARLIE, null, RowResult.ChangedSince),
            )
            viewModel.compared().runStop.shouldBeNull()
            viewModel.state.value.applyEnabled shouldBe false
            viewModel.events.first() shouldBe Event.Applied(done = 2, changed = 1, failed = 0)
            tracks.rows.value.map { it.mangaId }.contains(ECHO) shouldBe false
        }

    // Test 48.
    @Test
    fun `cancel switches the dialog to stopping and no row after the current one runs`() = runTest(testDispatcher) {
        val viewModel = comparedViewModel()
        viewModel.choose(ALPHA, CompareChoice.MARK_READ)
        viewModel.choose(BRAVO, CompareChoice.UPDATE_REMOTE)
        val held = CompletableDeferred<EntryRead>()
        val readCancelled = AtomicBoolean(false)
        answers[remoteId(ALPHA)] = {
            try {
                held.await()
            } catch (e: CancellationException) {
                readCancelled.set(true)
                throw e
            }
        }
        answers[remoteId(BRAVO)] = { EntryRead.OnList(track(BRAVO).copy(lastChapterRead = 2.0, totalChapters = 8)) }

        viewModel.requestApply()
        viewModel.apply()
        settle { tracker.entryReads.size == 2 }
        viewModel.state.value.applying shouldBe TrackerCompareViewModel.Applying(current = 1, total = 2)

        viewModel.cancelApply()
        viewModel.state.value.applying?.stopping shouldBe true
        // The held read is cancelled; were the cancel ignored, the answer below would let the run go on.
        val deadline = TimeSource.Monotonic.markNow() + SETTLE_TIMEOUT
        while (!readCancelled.get() && deadline.hasNotPassedNow()) Thread.sleep(10)
        readCancelled.get() shouldBe true
        held.complete(EntryRead.OnList(track(ALPHA).copy(lastChapterRead = 10.0)))
        settle { viewModel.state.value.applying == null }

        // The comparison read Echo alone once; Apply read Alpha's entry and nothing after it.
        tracker.entryReads.map { it.remoteId } shouldBe listOf(remoteId(ECHO), remoteId(ALPHA))
        tracker.base.remoteEdits.shouldBeEmpty()
        viewModel.row(ALPHA).choice shouldBe CompareChoice.MARK_READ
        viewModel.row(BRAVO).choice shouldBe CompareChoice.UPDATE_REMOTE
        chapters.filter { it.mangaId == ALPHA && it.read }.size shouldBe 5
    }

    // Test 49.
    @Test
    fun `no tracked entry shows the empty state with no sync and no call to the tracker`() = runTest(testDispatcher) {
        signInToSync()
        tracks.rows.value = emptyList()
        val viewModel = viewModel()
        settle { viewModel.state.value.screen == Screen.NothingTracked }

        tracker.listReads.shouldBeEmpty()
        tracker.entryReads.shouldBeEmpty()
        verify(exactly = 0) { workManager.getWorkInfosForUniqueWorkFlow(any()) }
        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    // Test 50.
    @Test
    fun `sign in again and back to settings go back, never to a new settings screen`() = runTest(testDispatcher) {
        tracker.list = { throw TrackerAuthExpiredException(tracker.id) }
        val refused = viewModel()
        settle { refused.state.value.screen is Screen.Stopped }
        (refused.state.value.screen as Screen.Stopped).stop.kind shouldBe StopKind.SIGN_IN_REFUSED
        refused.stopAction()
        refused.events.first() shouldBe Event.Back

        tracker.list = { throw HttpException(401) }
        val unauthorized = viewModel()
        settle { unauthorized.state.value.screen is Screen.Stopped }
        (unauthorized.state.value.screen as Screen.Stopped).stop.kind shouldBe StopKind.UNAUTHORIZED
        unauthorized.stopAction()
        unauthorized.events.first() shouldBe Event.Back
    }

    private fun chapters(mangaId: Long, numbers: IntRange, readThrough: Int): List<Chapter> =
        numbers.map { chapter(mangaId, it.toDouble()).copy(read = it <= readThrough) }

    private fun chapter(mangaId: Long, number: Double, copy: Boolean = false) = Chapter.create().copy(
        id = chapterId(mangaId, number.toInt()) + if (copy) COPY_OFFSET else 0,
        mangaId = mangaId,
        name = "Chapter $number",
        url = "/$mangaId/$number/$copy",
        chapterNumber = number,
        scanlator = if (copy) "Second group" else null,
    )

    private fun chapterId(mangaId: Long, number: Int): Long = mangaId * 1_000 + number

    private fun remoteId(mangaId: Long): Long = 1_000 + mangaId

    private fun track(mangaId: Long) = Track(
        id = mangaId,
        mangaId = mangaId,
        trackerId = tracker.id,
        remoteId = remoteId(mangaId),
        libraryId = null,
        title = "Entry $mangaId",
        lastChapterRead = 0.0,
        totalChapters = 0,
        status = READING,
        score = 0.0,
        remoteUrl = "",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    /** Rows by manga and tracker, as `manga_sync` keys them, behind a flow as the real repository's. */
    private class MemoryTrackRepository : TrackRepository {
        val rows = MutableStateFlow<List<Track>>(emptyList())

        fun put(track: Track) {
            rows.value = rows.value.filterNot { it.mangaId == track.mangaId && it.trackerId == track.trackerId } + track
        }

        override suspend fun getTrackById(id: Long): Track? = rows.value.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> = rows.value.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = rows
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            rows.map { all -> all.filter { it.mangaId == mangaId } }

        override suspend fun insert(track: Track) = put(track)
        override suspend fun insertAll(tracks: List<Track>) = tracks.forEach { put(it) }
        override suspend fun rebind(track: Track) = put(track)

        override suspend fun insertIfBoundTo(track: Track, boundRemoteId: Long): Boolean {
            if (getTracksByMangaId(track.mangaId).none { it.trackerId == track.trackerId && it.remoteId == boundRemoteId }) return false
            put(track)
            return true
        }

        override suspend fun deleteIfBoundTo(mangaId: Long, trackerId: Long, boundRemoteId: Long): Boolean {
            if (getTracksByMangaId(mangaId).none { it.trackerId == trackerId && it.remoteId == boundRemoteId }) return false
            rows.value = rows.value.filterNot { it.mangaId == mangaId && it.trackerId == trackerId }
            return true
        }
    }

    /** No database here: the block runs as it is. */
    private object PassThroughTransactionRunner : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private companion object {
        const val ALPHA = 1L
        const val BRAVO = 2L
        const val CHARLIE = 3L
        const val DELTA = 4L
        const val ECHO = 5L
        const val READING = 1L
        const val COPY_OFFSET = 500L
        val SETTLE_TIMEOUT = 30.seconds
    }
}
