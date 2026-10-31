package com.shinsaikou.tracking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.IOException

/**
 * The track sheet's refresh on open (phase 3 unit T3), upstream's `RefreshTracks`: every row whose
 * tracker is signed in and not expired is read back, one failure is reported per failed tracker and
 * never stops the others, an unchanged answer is not stored, and an answer for an entry the pair is
 * no longer bound to is not stored. A pair with a push queued asks for a run of the retry worker
 * (challenge S1's guard, phase 4 unit TR), and a scheduler's failure never fails the refresh. A
 * refresh that waited for the sending lock works from the pair's row as it is once the lock is held
 * (phase 4 unit TC, R103 carry-over 1).
 */
class RefreshTracksTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private var scheduled = 0
    private var scheduler = DelayedTrackingScheduler { scheduled++ }

    private fun refreshTracks(repository: FakeTrackRepository, vararg trackers: Tracker) =
        RefreshTracks(repository, TrackerManager(trackers.toSet()), store, scheduler)

    @Test
    fun `every signed-in tracker is refreshed and a changed answer is stored`() = runTest {
        val repository = FakeTrackRepository(track(id = 1, trackerId = 1), track(id = 2, trackerId = 2))
        val first = FakeTracker(1, refreshed = { it.copy(lastChapterRead = 5.0) })
        val second = FakeTracker(2)

        val failures = refreshTracks(repository, first, second).await(MANGA_ID)

        failures.shouldBeEmpty()
        first.refreshes.map { it.id } shouldBe listOf(1L)
        second.refreshes.map { it.id } shouldBe listOf(2L)
        repository.inserted.map { it.id to it.lastChapterRead } shouldBe listOf(1L to 5.0)
    }

    @Test
    fun `a signed-out or expired tracker and a row of an unknown tracker are skipped`() = runTest {
        val repository = FakeTrackRepository(
            track(id = 1, trackerId = 1),
            track(id = 2, trackerId = 2),
            track(id = 3, trackerId = 3),
        )
        val signedOut = FakeTracker(1, isLoggedIn = false)
        val expired = FakeTracker(2, isAuthExpired = true)

        val failures = refreshTracks(repository, signedOut, expired).await(MANGA_ID)

        failures.shouldBeEmpty()
        signedOut.refreshes.shouldBeEmpty()
        expired.refreshes.shouldBeEmpty()
        repository.inserted.shouldBeEmpty()
    }

    @Test
    fun `a failed tracker is reported once and the other is still refreshed and stored`() = runTest {
        val repository = FakeTrackRepository(track(id = 1, trackerId = 1), track(id = 2, trackerId = 2))
        val failure = IOException("fixture: 503")
        val failing = FakeTracker(1, refreshed = { throw failure })
        val working = FakeTracker(2, refreshed = { it.copy(status = 3) })

        val failures = refreshTracks(repository, failing, working).await(MANGA_ID)

        failures shouldBe listOf(failing to failure)
        repository.inserted.map { it.id to it.status } shouldBe listOf(2L to 3L)
    }

    @Test
    fun `an answer equal to the row is not stored`() = runTest {
        val repository = FakeTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 3.0))
        val tracker = FakeTracker(1)

        refreshTracks(repository, tracker).await(MANGA_ID)

        tracker.refreshes.size shouldBe 1
        repository.inserted.shouldBeEmpty()
    }

    @Test
    fun `an answer for the old entry is not stored over a rebind made while the refresh was on the wire`() = runTest {
        val repository = FakeTrackRepository(track(id = 1, trackerId = 1))
        val rebound = track(id = 1, trackerId = 1).copy(remoteId = 2002, title = "Another entry")
        val tracker = FakeTracker(1)
        tracker.refreshed = { track ->
            runBlocking { repository.rebind(rebound) }
            track.copy(lastChapterRead = 7.0)
        }

        refreshTracks(repository, tracker).await(MANGA_ID)

        repository.rows.values.single() shouldBe rebound
    }

    @Test
    fun `a queued push for a refreshed pair asks for a run of the worker`() = runTest {
        val repository = FakeTrackRepository(track(id = 1, trackerId = 1))
        store.add(MANGA_ID, 1, ENTRY, 5.0)

        refreshTracks(repository, FakeTracker(1)).await(MANGA_ID)

        scheduled shouldBe 1
    }

    @Test
    fun `a refresh with nothing queued asks for no run`() = runTest {
        val repository = FakeTrackRepository(track(id = 1, trackerId = 1))
        // A push queued for another manga is not this sheet's.
        store.add(MANGA_ID + 1, 1, ENTRY, 5.0)
        val tracker = FakeTracker(1)

        refreshTracks(repository, tracker).await(MANGA_ID)

        tracker.refreshes.size shouldBe 1
        scheduled shouldBe 0
    }

    @Test
    fun `a scheduler that throws leaves the refresh's result as it is`() = runTest {
        val repository = FakeTrackRepository(track(id = 1, trackerId = 1))
        store.add(MANGA_ID, 1, ENTRY, 5.0)
        scheduler = DelayedTrackingScheduler { throw IllegalStateException("fixture: WorkManager's future failed") }
        val tracker = FakeTracker(1, refreshed = { it.copy(lastChapterRead = 5.0) })

        val failures = refreshTracks(repository, tracker).await(MANGA_ID)

        failures.shouldBeEmpty()
        repository.rows.getValue(1).lastChapterRead shouldBe 5.0
    }

    @Test
    fun `a refresh that waited for the lock refreshes and stores from the row the worker stored meanwhile`() {
        runBlocking {
            val repository = FakeTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 5.0))
            val pairsRead = CompletableDeferred<Unit>()
            val watched = object : TrackRepository by repository {
                override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
                    pairsRead.complete(Unit)
                    return repository.getTracksByMangaId(mangaId)
                }
            }
            val tracker = FakeTracker(1, refreshed = { it.copy(status = 3) })
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = launch(Dispatchers.Default) {
                store.whileSending {
                    held.complete(Unit)
                    release.await()
                    // The worker's push is stored before it lets go.
                    repository.insert(track(id = 1, trackerId = 1, lastChapterRead = 6.0))
                }
            }
            held.await()

            val refresh = async(Dispatchers.Default) {
                RefreshTracks(watched, TrackerManager(setOf(tracker)), store, scheduler).await(MANGA_ID)
            }
            pairsRead.await()
            release.complete(Unit)
            worker.join()

            refresh.await().shouldBeEmpty()
            tracker.refreshes.map { it.lastChapterRead } shouldBe listOf(6.0)
            repository.rows.getValue(1).let { it.lastChapterRead to it.status } shouldBe (6.0 to 3L)
        }
    }
}

/** The remote id [track] gives the row with id 1. */
private const val ENTRY = 1001L
