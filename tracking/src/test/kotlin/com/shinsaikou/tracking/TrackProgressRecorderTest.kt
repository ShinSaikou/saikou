package com.shinsaikou.tracking

import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The progress hook (phase 3 units T1 and TQ): which finished chapters record a push, that it
 * records before it asks for the worker, asks for nothing when it records nothing, and still asks
 * when a later track's write throws after an earlier one committed (R95 carry-over 3).
 */
class TrackProgressRecorderTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private var scheduled = 0
    private val scheduler = DelayedTrackingScheduler {
        // The push is on disk before the worker is asked for.
        store.getItems().isEmpty() shouldBe false
        scheduled++
    }

    private val notMerged = mockk<GetMergedGroup> { coEvery { await(any()) } returns null }

    private fun recorder(repository: FakeTrackRepository, vararg trackers: Tracker) =
        TrackProgressRecorder(repository, TrackerManager(trackers.toSet()), store, scheduler, notMerged)

    @Test
    fun `a finished chapter above the track's number records the push and enqueues the worker`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val recorder = recorder(repository, FakeTracker(1))

        recorder.wouldRecord(MANGA_ID, 5.0) shouldBe true
        recorder.record(MANGA_ID, 5.0) shouldBe 1

        store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(MANGA_ID, 1, 1010, 5.0))
        scheduled shouldBe 1
    }

    @Test
    fun `a chapter at or below the track's number records nothing, as a re-read`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 5.0))
        val recorder = recorder(repository, FakeTracker(1))

        for (number in listOf(5.0, 3.0)) {
            recorder.wouldRecord(MANGA_ID, number) shouldBe false
            recorder.record(MANGA_ID, number) shouldBe 0
        }

        store.getItems().shouldBeEmpty()
        scheduled shouldBe 0
    }

    @Test
    fun `a signed-out tracker records nothing`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1))
        val recorder = recorder(repository, FakeTracker(1, isLoggedIn = false))

        recorder.record(MANGA_ID, 5.0) shouldBe 0

        store.getItems().shouldBeEmpty()
        scheduled shouldBe 0
    }

    @Test
    fun `a tracker whose sign-in expired records the push for the next sign-in and asks for no worker`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1))
        val recorder = recorder(repository, FakeTracker(1, isAuthExpired = true))

        recorder.wouldRecord(MANGA_ID, 5.0) shouldBe true
        recorder.record(MANGA_ID, 5.0) shouldBe 1

        store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(MANGA_ID, 1, 1010, 5.0))
        scheduled shouldBe 0
    }

    @Test
    fun `a manga bound to no tracker records nothing`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, mangaId = MANGA_ID + 1))
        val recorder = recorder(repository, FakeTracker(1))

        recorder.record(MANGA_ID, 5.0) shouldBe 0

        store.getItems().shouldBeEmpty()
        scheduled shouldBe 0
    }

    @Test
    fun `with no tracker in the graph nothing is read and nothing written`() = runTest {
        val repository = spyk(FakeTrackRepository(track(id = 10, trackerId = 1)))
        val recorder = recorder(repository)

        recorder.record(MANGA_ID, 5.0) shouldBe 0

        coVerify(exactly = 0) { repository.getTracksByMangaId(any()) }
        preferences.values.keys.shouldBeEmpty()
        scheduled shouldBe 0
    }

    @Test
    fun `a row whose tracker the graph does not have is skipped, and the others are recorded`() = runTest {
        val repository = FakeTrackRepository(
            track(id = 10, trackerId = 1, lastChapterRead = 2.0),
            track(id = 11, trackerId = 2, lastChapterRead = 9.0),
            track(id = 12, trackerId = TrackerIds.SERVER_TRACKER_6),
        )
        val recorder = recorder(repository, FakeTracker(1), FakeTracker(2))

        recorder.record(MANGA_ID, 5.0) shouldBe 1

        store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(MANGA_ID, 1, 1010, 5.0))
        scheduled shouldBe 1
    }

    @Test
    fun `a store that refuses the write throws to the caller and asks for no worker`() = runTest {
        val refusing = DelayedTrackingStore(preferences) { _, _ -> throw IOException("fixture: commit refused") }
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1))
        val recorder = TrackProgressRecorder(repository, TrackerManager(setOf(FakeTracker(1))), refusing, scheduler, notMerged)

        shouldThrow<IOException> { recorder.record(MANGA_ID, 5.0) }

        scheduled shouldBe 0
    }

    @Test
    fun `an entry committed before a later track's write threw is still scheduled`() = runTest {
        val failing = DelayedTrackingStore.key(MANGA_ID, 2)
        val secondRefused = DelayedTrackingStore(preferences) { key, value ->
            if (key == failing) throw IOException("fixture: commit refused")
            preferences.write(key, value)
        }
        val repository = FakeTrackRepository(
            track(id = 10, trackerId = 1, lastChapterRead = 2.0),
            track(id = 11, trackerId = 2, lastChapterRead = 2.0),
        )
        val recorder = TrackProgressRecorder(
            repository,
            TrackerManager(setOf(FakeTracker(1), FakeTracker(2))),
            secondRefused,
            scheduler,
            notMerged,
        )

        shouldThrow<IOException> { recorder.record(MANGA_ID, 5.0) }

        store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(MANGA_ID, 1, 1010, 5.0))
        scheduled shouldBe 1
    }
}
