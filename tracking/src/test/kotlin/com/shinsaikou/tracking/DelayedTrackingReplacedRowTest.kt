package com.shinsaikou.tracking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A push survives the row it was recorded for being replaced (phase 3 unit TQ, T2's ruling draft
 * finding 1). Every store of a bound pair gives the row a new id ([ReplacingTrackRepository] models
 * `manga_sync`'s `ON CONFLICT REPLACE`), so the queue is keyed by manga and tracker and the worker
 * reads the row by that pair. Both tests record through the one hook, [TrackProgressRecorder], and
 * send through the worker's pass, [DelayedTrackingUpdate]; with the store keyed by the row's id,
 * as in T1 and in upstream, both lose the push.
 *
 * A recording made after a replacement proves nothing (it names the current row either way), so
 * each test records before the row is replaced.
 *
 * The last two replace the row with one naming another entry on the service (verifier finding 1):
 * the pair is the same, the binding is not, so the push recorded against the old entry is dropped
 * rather than sent to the new one, and a push read on the new entry is sent whatever its number.
 */
class DelayedTrackingReplacedRowTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private var scheduled = 0
    private val scheduler = DelayedTrackingScheduler { scheduled++ }

    private fun recorder(repository: ReplacingTrackRepository, tracker: Tracker) =
        TrackProgressRecorder(repository, TrackerManager(setOf(tracker)), store, scheduler)

    private fun update(repository: ReplacingTrackRepository, tracker: Tracker) =
        DelayedTrackingUpdate(store, repository, TrackerManager(setOf(tracker)), FakeSignInNotifier())

    @Test
    fun `a chapter recorded while the push before it is on the wire is sent by the next run`() = runTest {
        val repository = ReplacingTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 2.0))
        val tracker = FakeTracker(1)
        val recorder = recorder(repository, tracker)
        val onTheWire = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        tracker.beforeUpdate = {
            onTheWire.complete(Unit)
            answer.await()
        }
        recorder.record(MANGA_ID, 3.0) shouldBe 1

        val run = async { update(repository, tracker).run() }
        onTheWire.await()
        // The reader finishes chapter 4 while chapter 3 is on the wire, still against row 1.
        repository.rows.keys shouldBe setOf(1L)
        recorder.record(MANGA_ID, 4.0) shouldBe 1
        tracker.beforeUpdate = {}
        answer.complete(Unit)
        run.await() shouldBe true

        // The run stored the service's answer, which replaced row 1, and left chapter 4 queued.
        repository.rows.keys shouldNotBe setOf(1L)
        store.getItems().map { it.chapterNumber } shouldBe listOf(4.0)
        scheduled shouldBe 2

        update(repository, tracker).run() shouldBe true

        tracker.updates.map { (sent, _) -> sent.lastChapterRead } shouldBe listOf(3.0, 4.0)
        repository.rows.values.single().lastChapterRead shouldBe 4.0
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a push recorded before the row was stored again is sent to the row that replaced it`() = runTest {
        val repository = ReplacingTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 2.0))
        val tracker = FakeTracker(1)
        recorder(repository, tracker).record(MANGA_ID, 3.0) shouldBe 1

        // Before any run, the row is stored again by a plain insert, as a sync pull applying the
        // manga's tracks or the tracking sheet storing an answer does.
        repository.insert(repository.rows.values.single())
        val replaced = repository.rows.values.single()
        replaced.id shouldNotBe 1L

        update(repository, tracker).run() shouldBe true

        tracker.refreshes.map { it.id } shouldBe listOf(replaced.id)
        tracker.updates.map { (sent, _) -> sent.lastChapterRead } shouldBe listOf(3.0)
        repository.rows.values.single().lastChapterRead shouldBe 3.0
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a push recorded before the pair was bound to another entry is dropped, not sent to the new one`() = runTest {
        val repository = ReplacingTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 2.0))
        val tracker = FakeTracker(1)
        val readOn = repository.rows.values.single()
        recorder(repository, tracker).record(MANGA_ID, 3.0) shouldBe 1

        // Before any run, the pair is bound to another entry on the service (a bind to another
        // search result, or a sync pull carrying one), stored by a plain insert as those do.
        repository.insert(readOn.copy(remoteId = OTHER_ENTRY, title = "Another entry", lastChapterRead = 0.0))
        repository.rows.values.single().remoteId shouldBe OTHER_ENTRY

        update(repository, tracker).run() shouldBe true

        tracker.refreshes.shouldBeEmpty()
        tracker.updates.shouldBeEmpty()
        repository.rows.values.single().remoteId shouldBe OTHER_ENTRY
        repository.rows.values.single().lastChapterRead shouldBe 0.0
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `after the pair was bound to another entry, a chapter read on it is sent even below the stale push`() = runTest {
        val repository = ReplacingTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 2.0))
        val tracker = FakeTracker(1)
        val recorder = recorder(repository, tracker)
        recorder.record(MANGA_ID, 3.0) shouldBe 1
        repository.insert(repository.rows.values.single().copy(remoteId = OTHER_ENTRY, lastChapterRead = 0.0))

        recorder.record(MANGA_ID, 1.0) shouldBe 1
        store.getItems().map { it.remoteId to it.chapterNumber } shouldBe listOf(OTHER_ENTRY to 1.0)

        update(repository, tracker).run() shouldBe true

        tracker.refreshes.map { it.remoteId } shouldBe listOf(OTHER_ENTRY)
        tracker.updates.map { (sent, _) -> sent.remoteId to sent.lastChapterRead } shouldBe listOf(OTHER_ENTRY to 1.0)
        repository.rows.values.single().lastChapterRead shouldBe 1.0
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a rebind stored while the push is on the wire is kept, and the old entry's answer is not stored`() = runTest {
        val repository = ReplacingTrackRepository(track(id = 1, trackerId = 1, lastChapterRead = 2.0))
        val tracker = FakeTracker(1)
        recorder(repository, tracker).record(MANGA_ID, 3.0) shouldBe 1
        // The owner binds the pair to another entry from the track sheet between the worker's read
        // of the row and its store of the service's answer (phase 3 unit T3).
        tracker.beforeUpdate = {
            repository.rebind(repository.rows.values.single().copy(remoteId = OTHER_ENTRY, lastChapterRead = 0.0))
        }

        update(repository, tracker).run() shouldBe true

        tracker.updates.map { (sent, _) -> sent.remoteId to sent.lastChapterRead } shouldBe listOf(1001L to 3.0)
        repository.rows.values.single().remoteId shouldBe OTHER_ENTRY
        repository.rows.values.single().lastChapterRead shouldBe 0.0
        store.getItems().shouldBeEmpty()
    }

    private companion object {
        /** The service's entry the pair is bound to after the rebind; [track] gave the row 1001. */
        const val OTHER_ENTRY = 2002L
    }
}
