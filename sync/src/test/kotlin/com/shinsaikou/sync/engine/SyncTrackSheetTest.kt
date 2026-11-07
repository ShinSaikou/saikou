package com.shinsaikou.sync.engine

import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.AddTracks
import com.shinsaikou.tracking.BaseTracker
import com.shinsaikou.tracking.DelayedTrackingScheduler
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.DelayedTrackingUpdate
import com.shinsaikou.tracking.EditTrack
import com.shinsaikou.tracking.RefreshTracks
import com.shinsaikou.tracking.TrackProgressRecorder
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.tracking.TrackerSignInNotifier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Clock

/**
 * The track sheet's writes against the sync engine and the real schema (phase 3 unit T3), on two
 * devices sharing one backend.
 *
 * A rebind from the sheet reaches the other device, whose next push goes to the new entry: the
 * rebind is stored as a delete and an insert, which moves the tracker-set clock, so the newer set
 * wins over the other device's old binding even when that one read further. The worker's store of a
 * service's answer, and a hand edit's, never undoes a rebind made while the call was on the wire.
 * And a hand edit is progress, which moves no clock (R82): a lowered chapter does not reach the
 * other device, and a status change at equal progress reaches it in one direction only (challenge
 * S2); the merge is not changed for it.
 */
class SyncTrackSheetTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend, exported = true)
    private val b = SyncTestDevice(backend, exported = true)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    /** One tracker, id 1, signed in, recording every update it is sent and answering with the row. */
    private class RecordingTracker {
        val updates = mutableListOf<Track>()
        var refreshed: (Track) -> Track = { it }
        var beforeAnswer: suspend (Track) -> Unit = {}
        val tracker = mockk<Tracker>(relaxed = true).also { tracker ->
            every { tracker.id } returns TRACKER
            every { tracker.isLoggedIn } returns true
            every { tracker.isAuthExpired } returns false
            // As MyAnimeList's bind adds an entry not on the list: plan to read.
            coEvery { tracker.bind(any(), any()) } answers { firstArg<Track>().copy(status = PLAN_TO_READ) }
            coEvery { tracker.refresh(any()) } answers { refreshed(firstArg()) }
            coEvery { tracker.update(any(), any()) } coAnswers {
                val sent = firstArg<Track>()
                updates += sent
                beforeAnswer(sent)
                sent
            }
        }
        val manager = TrackerManager(setOf(tracker))
    }

    /**
     * [BaseTracker] itself, id 1, whose `setRemote*` store the answer as the app's tracker does:
     * [update] records what it was sent, runs [beforeAnswer] and answers with the row.
     */
    private class EditingTracker(repository: TrackRepository) : BaseTracker(
        id = TRACKER,
        name = "Service",
        trackPreferences = TrackPreferences(MapPreferenceStore()),
        trackRepository = repository,
        httpClient = mockk<TrackerHttpClient>(),
        clock = Clock.System,
    ) {
        val sent = mutableListOf<Track>()
        var beforeAnswer: suspend (Track) -> Unit = {}

        override fun getLogo(): Int = 0
        override fun getStatusList(): List<Long> = listOf(1, DROPPED, PLAN_TO_READ)
        override fun getStatus(status: Long): Int? = null
        override fun getReadingStatus(): Long = 1
        override fun getRereadingStatus(): Long = 7
        override fun getCompletionStatus(): Long = 2
        override fun getScoreList(): List<String> = (0..10).map(Int::toString)
        override fun displayScore(track: Track): String = track.score.toString()
        override suspend fun search(query: String): List<TrackSearchResult> = emptyList()
        override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = track
        override suspend fun refresh(track: Track): Track = track
        override suspend fun login(username: String, password: String) = Unit

        override suspend fun update(track: Track, didReadChapter: Boolean): Track {
            sent += track
            beforeAnswer(track)
            return track
        }
    }

    /** The tracking side of one device: its repository, queue, recorder and worker. */
    private class Tracking(device: SyncTestDevice, recording: RecordingTracker) {
        val repository = TrackRepositoryImpl(device.handler)
        private val preferences = MapPreferenceStore()
        val store = DelayedTrackingStore(preferences) { key, value ->
            if (value == null) preferences.values.remove(key) else preferences.values[key] = value
        }
        val recorder = TrackProgressRecorder(repository, recording.manager, store, DelayedTrackingScheduler {}, mockk { coEvery { await(any()) } returns null })
        val worker = DelayedTrackingUpdate(
            store,
            repository,
            recording.manager,
            object : TrackerSignInNotifier {
                override fun expired(tracker: Tracker) = Unit
                override fun signedIn(tracker: Tracker) = Unit
            },
        )
        val addTracks = AddTracks(
            repository,
            GetChaptersByMangaId(ChapterRepositoryImpl(device.handler)),
            GetHistory(HistoryRepositoryImpl(device.handler)),
            DatabaseTransactionRunner(device.handler),
        )
    }

    private fun SyncTestDevice.bindTrack(mangaId: Long, remoteId: Long, lastChapterRead: Double = 0.0, status: Long = 1) = exec(
        """
        INSERT INTO manga_sync(manga_id, sync_id, remote_id, library_id, title, last_chapter_read,
            total_chapters, status, score, remote_url, start_date, finish_date, private)
        VALUES ($mangaId, $TRACKER, $remoteId, NULL, 'Entry $remoteId', $lastChapterRead, 0, $status, 0.0,
            '/entry/$remoteId', 0, 0, 0)
        """,
    )

    private fun SyncTestDevice.track(mangaId: Long): List<String?> = rows(
        "SELECT remote_id, last_chapter_read, status FROM manga_sync WHERE manga_id = $mangaId AND sync_id = $TRACKER",
        3,
    ).single()

    /** A holds manga 1 bound to entry 1001; both devices sync, so B holds it too. */
    private fun bothBound(lastChapterRead: Double = 0.0, status: Long = 1): Long {
        a.addFavourite(1)
        a.bindTrack(1, 1001, lastChapterRead, status)
        a.sync()
        b.startReading(a.deviceId)
        b.sync()
        a.startReading(b.deviceId)
        return b.mangaId(SyncTestDevice.SOURCE, "/series/1").shouldNotBeNull()
    }

    @Test
    fun `a rebind reaches the other device over its further read old entry, whose next push goes to the new entry`() {
        val recording = RecordingTracker()
        val onA = Tracking(a, recording)
        val onB = Tracking(b, recording)
        val bManga = bothBound()
        // B reads on the old entry: its row moves to 5, and chapter 6 is queued against 1001.
        b.exec("UPDATE manga_sync SET last_chapter_read = 5 WHERE manga_id = $bManga")
        runBlocking { onB.recorder.record(bManga, 6.0) } shouldBe 1

        // On A the owner picks another search result for the pair.
        val result = TrackSearchResult(TRACKER, 2002, "Another entry", "/entry/2002", "", "", "", "", "", 0, emptyList(), emptyList())
        runBlocking { onA.addTracks.bind(recording.tracker, result, 1) }
        a.sync()
        b.sync()

        b.track(bManga) shouldBe listOf("2002", "0.0", "$PLAN_TO_READ")
        // The push queued against the old entry is dropped unsent; the next chapter goes to the new one.
        runBlocking { onB.worker.run() } shouldBe true
        recording.updates.shouldBeEmpty()
        runBlocking { onB.recorder.record(bManga, 1.0) } shouldBe 1
        runBlocking { onB.worker.run() } shouldBe true
        recording.updates.map { it.remoteId to it.lastChapterRead } shouldBe listOf(2002L to 1.0)
        // And B's push does not bring the old entry back on A.
        b.sync()
        a.sync()
        a.track(1) shouldBe listOf("2002", "1.0", "$PLAN_TO_READ")
    }

    @Test
    fun `the worker does not store the old entry's answer over a rebind made while the push was on the wire`() {
        val recording = RecordingTracker()
        val onA = Tracking(a, recording)
        a.addFavourite(1)
        a.bindTrack(1, 1001, lastChapterRead = 2.0)
        runBlocking { onA.recorder.record(1, 3.0) } shouldBe 1
        recording.beforeAnswer = {
            val bound = onA.repository.getTracksByMangaId(1).single()
            onA.repository.rebind(bound.copy(remoteId = 2002, title = "Another entry", lastChapterRead = 0.0))
        }

        runBlocking { onA.worker.run() } shouldBe true

        recording.updates.map { it.remoteId to it.lastChapterRead } shouldBe listOf(1001L to 3.0)
        a.track(1) shouldBe listOf("2002", "0.0", "1")
        onA.store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a hand edit does not store the old entry's answer over a rebind synced in while the edit was on the wire`() {
        val recording = RecordingTracker()
        val onA = Tracking(a, recording)
        val onB = Tracking(b, recording)
        val bManga = bothBound()
        // On A the owner picks another search result for the pair, and A syncs it out.
        val result = TrackSearchResult(TRACKER, 2002, "Another entry", "/entry/2002", "", "", "", "", "", 0, emptyList(), emptyList())
        runBlocking { onA.addTracks.bind(recording.tracker, result, 1) }
        a.sync()

        // On B, not yet synced, the owner sets a status by hand; B's sync applies A's rebind while
        // the edit is on the wire to the entry B held when the owner confirmed.
        val service = EditingTracker(onB.repository)
        service.beforeAnswer = { b.engine.run(b.remote) }
        runBlocking { EditTrack(onB.repository, onB.store).setStatus(service, bManga, DROPPED) }

        service.sent.map { it.remoteId to it.status } shouldBe listOf(1001L to DROPPED)
        b.track(bManga) shouldBe listOf("2002", "0.0", "$PLAN_TO_READ")
        // And the old entry does not come back on A either.
        b.sync()
        a.sync()
        a.track(1) shouldBe listOf("2002", "0.0", "$PLAN_TO_READ")
    }

    @Test
    fun `a chapter lowered by hand does not reach the other device, and nothing read up to its number is sent there`() {
        val recording = RecordingTracker()
        val onB = Tracking(b, recording)
        val bManga = bothBound(lastChapterRead = 120.0)

        // The owner lowers the chapter to 40 on A: BaseTracker stores the service's answer for the
        // entry it was sent to.
        runBlocking {
            val onA = Tracking(a, recording)
            val bound = onA.repository.getTracksByMangaId(1).single()
            onA.repository.insertIfBoundTo(bound.copy(lastChapterRead = 40.0), bound.remoteId) shouldBe true
        }
        a.sync()
        b.sync()

        b.track(bManga) shouldBe listOf("1001", "120.0", "1")
        runBlocking { onB.recorder.wouldRecord(bManga, 80.0) } shouldBe false
        // Opening the sheet on B refreshes the row from the service, which repairs it.
        recording.refreshed = { it.copy(lastChapterRead = 40.0) }
        runBlocking { RefreshTracks(onB.repository, recording.manager, onB.store, DelayedTrackingScheduler {}).await(bManga) }.shouldBeEmpty()
        b.track(bManga) shouldBe listOf("1001", "40.0", "1")
    }

    @Test
    fun `a status changed by hand at equal progress reaches the other device in one direction only`() {
        val reached = listOf(1L to 4L, 4L to 1L).map { (held, set) ->
            SyncTestDevice(InMemorySyncBackend(), exported = true).use { first ->
                SyncTestDevice(first.backend, exported = true).use { second ->
                    first.addFavourite(1)
                    first.bindTrack(1, 1001, lastChapterRead = 5.0, status = held)
                    first.sync()
                    second.startReading(first.deviceId)
                    second.sync()
                    val secondManga = second.mangaId(SyncTestDevice.SOURCE, "/series/1").shouldNotBeNull()
                    first.exec("UPDATE manga_sync SET status = $set WHERE manga_id = 1")
                    first.sync()
                    second.sync()
                    second.track(secondManga)[2] == "$set"
                }
            }
        }

        reached.count { it } shouldBe 1
    }

    private companion object {
        const val TRACKER = 1L
        const val PLAN_TO_READ = 6L
        const val DROPPED = 4L
    }
}
