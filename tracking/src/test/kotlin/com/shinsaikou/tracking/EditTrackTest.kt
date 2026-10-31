package com.shinsaikou.tracking

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The owner's edits from the track sheet (phase 3 unit T3): each is sent straight to the service on
 * the pair's row as it is when confirmed, a failure is thrown for the sheet, and a chapter set by
 * hand clears the pair's queued push once the service took it. An edit waits for a worker call
 * already on the wire (phase 4 unit TR).
 */
class EditTrackTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private val repository = FakeTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 5.0))
    private val tracker = FakeTracker(1)
    private val editTrack = EditTrack(repository, store)

    @Test
    fun `each edit is sent on the pair's row as it is when confirmed`() = runTest {
        // A refresh stored a newer row after the sheet showed the old one.
        repository.insert(track(id = 1, trackerId = 1, lastChapterRead = 8.0))

        editTrack.setStatus(tracker, MANGA_ID, 4)
        editTrack.setScore(tracker, MANGA_ID, "7")
        editTrack.setStartDate(tracker, MANGA_ID, 1_000L)
        editTrack.setFinishDate(tracker, MANGA_ID, 2_000L)
        editTrack.setPrivate(tracker, MANGA_ID, true)

        tracker.remoteStatuses.map { (sent, status) -> sent.lastChapterRead to status } shouldBe listOf(8.0 to 4L)
        tracker.remoteScores.map { it.second } shouldBe listOf("7")
        tracker.remoteStartDates.map { it.second } shouldBe listOf(1_000L)
        tracker.remoteFinishDates.map { it.second } shouldBe listOf(2_000L)
        tracker.remotePrivates.map { it.second } shouldBe listOf(true)
    }

    @Test
    fun `a chapter set by hand clears the pair's queued push once the service took it, even a higher one`() = runTest {
        store.add(MANGA_ID, 1, 1001, 12.0)
        store.add(MANGA_ID, 2, 1002, 3.0)

        editTrack.setChapter(tracker, MANGA_ID, 4)

        tracker.remoteLastChapterReads.map { (sent, number) -> sent.id to number } shouldBe listOf(1L to 4)
        store.getItems().map { it.trackerId } shouldBe listOf(2L)
    }

    @Test
    fun `a chapter the service refused is thrown and leaves the queued push`() = runTest {
        store.add(MANGA_ID, 1, 1001, 12.0)
        tracker.remoteFailure = IOException("fixture: 503")

        shouldThrow<IOException> { editTrack.setChapter(tracker, MANGA_ID, 4) }

        store.getItems().map { it.chapterNumber } shouldBe listOf(12.0)
    }

    @Test
    fun `a chapter set by hand while the worker's push is on the wire is sent after it, on the row it stored`() = runTest {
        store.add(MANGA_ID, 1, 1001, 50.0)
        val worker = DelayedTrackingUpdate(store, repository, TrackerManager(setOf(tracker)), FakeSignInNotifier())
        val onTheWire = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        tracker.beforeUpdate = {
            onTheWire.complete(Unit)
            answer.await()
        }

        val run = async { worker.run() }
        onTheWire.await()
        val edit = async { editTrack.setChapter(tracker, MANGA_ID, 40) }
        // The edit runs on the IO dispatcher: give it real time to send, which the lock forbids.
        val sentMeanwhile = withContext(Dispatchers.Default) {
            withTimeoutOrNull(EDIT_WAIT_MILLIS) {
                while (tracker.remoteLastChapterReads.isEmpty()) delay(10)
            }
        }
        sentMeanwhile.shouldBeNull()

        answer.complete(Unit)
        run.await() shouldBe true
        edit.await()

        tracker.updates.map { (sent, _) -> sent.lastChapterRead } shouldBe listOf(50.0)
        tracker.remoteLastChapterReads.map { (sent, number) -> sent.lastChapterRead to number } shouldBe listOf(50.0 to 40)
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `an edit of a pair whose row is gone sends nothing`() = runTest {
        repository.deleteIfBoundTo(MANGA_ID, 1, boundRemoteId = 1001) shouldBe true

        editTrack.setStatus(tracker, MANGA_ID, 4)
        editTrack.setChapter(tracker, MANGA_ID, 4)

        tracker.remoteStatuses.shouldBeEmpty()
        tracker.remoteLastChapterReads.shouldBeEmpty()
    }
}

/** How long the lock test gives an edit to send while the worker's push is held. */
private const val EDIT_WAIT_MILLIS = 500L
