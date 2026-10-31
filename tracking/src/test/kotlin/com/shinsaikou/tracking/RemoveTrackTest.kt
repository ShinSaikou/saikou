package com.shinsaikou.tracking

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The track sheet's remove (phase 3 unit T3), upstream's remove dialog: the local row is deleted and
 * the pair's queued push cleared before the service is asked, the service only when the box is
 * ticked, and the service's refusal is thrown without bringing the row back. The row and the push
 * go only while the pair still names the entry removed (phase 4 unit TR). The remove waits for
 * another holder of the sending lock (phase 4 unit TC, R103 carry-over 2).
 */
class RemoveTrackTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private val row = track(id = 1, trackerId = 1, lastChapterRead = 5.0)
    private val repository = FakeTrackRepository(row, track(id = 2, trackerId = 2))
    private val removeTrack = RemoveTrack(repository, store)

    @Test
    fun `with the box ticked the row is deleted and the push cleared before the service is asked`() = runTest {
        store.add(MANGA_ID, 1, 1001, 9.0)
        var seenRows: Set<Long>? = null
        var seenQueue: List<Long>? = null
        val tracker = FakeDeletableTracker(1, deleted = {
            seenRows = repository.rows.keys.toSet()
            seenQueue = store.getItems().map { it.trackerId }
        })

        removeTrack.await(tracker, row, alsoRemote = true)

        tracker.deletes shouldBe listOf(row)
        seenRows shouldBe setOf(2L)
        seenQueue shouldBe emptyList()
    }

    @Test
    fun `with the box unticked only the local row goes`() = runTest {
        store.add(MANGA_ID, 1, 1001, 9.0)
        val tracker = FakeDeletableTracker(1)

        removeTrack.await(tracker, row, alsoRemote = false)

        tracker.deletes.shouldBeEmpty()
        repository.rows.keys shouldBe setOf(2L)
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `the service's refusal is thrown and the row stays deleted`() = runTest {
        val tracker = FakeDeletableTracker(1, deleted = { throw IOException("fixture: 503") })

        shouldThrow<IOException> { removeTrack.await(tracker, row, alsoRemote = true) }

        repository.rows.keys shouldBe setOf(2L)
    }

    @Test
    fun `a rebind landed before the confirm keeps the new row and its queued push`() = runTest {
        val rebound = row.copy(remoteId = 2002, title = "Entry rebound")
        repository.rebind(rebound)
        store.add(MANGA_ID, 1, 2002, 3.0)
        val tracker = FakeDeletableTracker(1)

        // The sheet confirms on the row it held, which names the old entry.
        removeTrack.await(tracker, row, alsoRemote = true)

        repository.rows[1L] shouldBe rebound
        repository.rows.keys shouldBe setOf(1L, 2L)
        store.getItems().map { it.trackerId to it.remoteId } shouldBe listOf(1L to 2002L)
        // The service's delete still goes to the entry the sheet held.
        tracker.deletes shouldBe listOf(row)
    }

    @Test
    fun `a tracker that cannot delete on the service is never asked, box or not`() = runTest {
        val tracker = FakeTracker(1)

        removeTrack.await(tracker, row, alsoRemote = true)

        repository.rows.keys shouldBe setOf(2L)
    }

    @Test
    fun `a remove confirmed while another holder has the sending lock waits for it, then deletes`() {
        runBlocking {
            store.add(MANGA_ID, 1, 1001, 9.0)
            val tracker = FakeDeletableTracker(1)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) {
                store.whileSending {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()

            val remove = async(Dispatchers.Default) { removeTrack.await(tracker, row, alsoRemote = true) }
            val removedMeanwhile = withContext(Dispatchers.Default) {
                withTimeoutOrNull(LOCK_WAIT_MILLIS) {
                    while (1L in repository.rows && tracker.deletes.isEmpty()) delay(10)
                }
            }
            removedMeanwhile.shouldBeNull()
            repository.rows.keys shouldBe setOf(1L, 2L)
            store.getItems().map { it.trackerId } shouldBe listOf(1L)

            release.complete(Unit)
            holder.join()
            remove.await()

            repository.rows.keys shouldBe setOf(2L)
            store.getItems().shouldBeEmpty()
            tracker.deletes shouldBe listOf(row)
        }
    }
}

/** How long the lock test gives a remove to act while the lock is held. */
private const val LOCK_WAIT_MILLIS = 500L
