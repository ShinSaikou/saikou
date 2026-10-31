package com.shinsaikou.tracking

import com.shinsaikou.tracking.DelayedTrackingStore.DelayedTrackingItem
import com.shinsaikou.tracking.myanimelist.MALTitleNotApproved
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.io.IOException

/**
 * The retry worker's pass (phase 3 units T1 and TQ): what is sent and stored, what stays for a
 * retry, what is dropped unsent, that the number sent only ever goes up, and that T1's keys by track
 * row id are deleted. A row replaced under a new id is `DelayedTrackingReplacedRowTest`'s.
 */
class DelayedTrackingUpdateTest {

    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)

    private val notifier = FakeSignInNotifier()

    private fun update(repository: FakeTrackRepository, vararg trackers: Tracker) =
        DelayedTrackingUpdate(store, repository, TrackerManager(trackers.toSet()), notifier)

    @Test
    fun `a push that succeeds stores the service's row and removes the entry`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val tracker = FakeTracker(1, updated = { it.copy(status = 2L) })
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(repository, tracker).run() shouldBe true

        tracker.refreshes.map { it.id } shouldBe listOf(10L)
        tracker.updates.map { (sent, didRead) -> sent.lastChapterRead to didRead } shouldBe listOf(7.0 to true)
        repository.rows.getValue(10).lastChapterRead shouldBe 7.0
        repository.rows.getValue(10).status shouldBe 2L
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a transient failure keeps the entry and asks for a retry`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val tracker = FakeTracker(1, updated = { throw IOException("fixture: connection reset") })
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(repository, tracker).run() shouldBe false

        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, ENTRY, 7.0))
        repository.inserted.shouldBeEmpty()

        // The next attempt, once the service answers, sends it.
        tracker.updated = { it }
        update(repository, tracker).run() shouldBe true
        store.getItems().shouldBeEmpty()
        repository.rows.getValue(10).lastChapterRead shouldBe 7.0
    }

    /**
     * The service refusing the entry itself, which no retry fixes: it no longer has it (a 404 on the
     * refresh or the send), or will not list it while its title waits for approval. A 400 of any
     * other kind, and a server error, may pass and stay for a retry.
     */
    @Test
    fun `a push the service refuses for good is dropped, and one it may take later is kept`() = runTest {
        val forGood = listOf<Pair<String, (FakeTracker) -> Unit>>(
            "a 404 on the refresh" to { it.refreshed = { throw HttpException(404) } },
            "a 404 on the send" to { it.updated = { throw HttpException(404) } },
            "a title waiting for approval" to { it.updated = { throw MALTitleNotApproved() } },
        )
        for ((case, fail) in forGood) {
            val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
            val tracker = FakeTracker(1).also(fail)
            store.add(MANGA_ID, 1, ENTRY, 7.0)

            withClue(case) {
                update(repository, tracker).run() shouldBe true
                store.getItems().shouldBeEmpty()
                repository.inserted.shouldBeEmpty()
            }
        }

        for (code in listOf(400, 503)) {
            val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
            val tracker = FakeTracker(1, updated = { throw HttpException(code) })
            store.add(MANGA_ID, 1, ENTRY, 7.0)

            withClue("HTTP $code") {
                update(repository, tracker).run() shouldBe false
                store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, ENTRY, 7.0))
            }
            store.clear(MANGA_ID, 1)
        }
    }

    @Test
    fun `a signed-out tracker's entry is kept unsent for the next sign-in, with no retry`() = runTest {
        assertKeptUnsent(FakeTracker(1, isLoggedIn = false))
    }

    @Test
    fun `an entry of a tracker whose sign-in expired is kept unsent for the next sign-in, with no retry`() = runTest {
        assertKeptUnsent(FakeTracker(1, isAuthExpired = true))
        // An expiry met earlier, on the track sheet or by an earlier run, is not noticed again.
        notifier.expired.shouldBeEmpty()
    }

    @Test
    fun `an entry kept while signed out is sent by the first run after the sign-in`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val tracker = FakeTracker(1, isLoggedIn = false)
        store.add(MANGA_ID, 1, ENTRY, 7.0)
        update(repository, tracker).run() shouldBe true

        tracker.isLoggedIn = true
        update(repository, tracker).run() shouldBe true

        tracker.updates.map { (sent, _) -> sent.lastChapterRead } shouldBe listOf(7.0)
        repository.rows.getValue(10).lastChapterRead shouldBe 7.0
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `an entry whose manga has no row for its tracker is dropped`() = runTest {
        val tracker = FakeTracker(1)
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(FakeTrackRepository(), tracker).run() shouldBe true

        store.getItems().shouldBeEmpty()
        tracker.refreshes.shouldBeEmpty()
    }

    @Test
    fun `an entry recorded for another entry on the service than the row's is dropped unsent`() = runTest {
        // The pair was bound to entry 1010 after the push was recorded against entry 2002.
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 1.0))
        val tracker = FakeTracker(1)
        store.add(MANGA_ID, 1, 2002, 7.0)

        update(repository, tracker).run() shouldBe true

        store.getItems().shouldBeEmpty()
        tracker.refreshes.shouldBeEmpty()
        tracker.updates.shouldBeEmpty()
        repository.inserted.shouldBeEmpty()
        repository.rows.getValue(10).lastChapterRead shouldBe 1.0
    }

    @Test
    fun `an entry is not sent to a row the manga has for another tracker`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 2, lastChapterRead = 1.0))
        val tracker = FakeTracker(1)
        val other = FakeTracker(2)
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(repository, tracker, other).run() shouldBe true

        store.getItems().shouldBeEmpty()
        tracker.refreshes.shouldBeEmpty()
        other.refreshes.shouldBeEmpty()
        repository.inserted.shouldBeEmpty()
    }

    @Test
    fun `an entry of a tracker id the graph does not have is dropped`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = TrackerIds.MYANIMELIST))
        store.add(MANGA_ID, TrackerIds.MYANIMELIST, ENTRY, 7.0)

        update(repository, FakeTracker(TrackerIds.ANILIST)).run() shouldBe true

        store.getItems().shouldBeEmpty()
        repository.inserted.shouldBeEmpty()
    }

    @Test
    fun `a refresh the service refuses as an expired sign-in keeps the entry and posts one notice`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1), track(id = 11, trackerId = 1, mangaId = 43))
        val tracker = FakeTracker(
            1,
            refreshed = { throw IOException("fixture: 400 invalid_grant") },
            expireOnFailure = true,
        )
        store.add(MANGA_ID, 1, ENTRY, 7.0)
        store.add(43, 1, 1011, 3.0)

        update(repository, tracker).run() shouldBe true
        update(repository, tracker).run() shouldBe true

        tracker.isAuthExpired shouldBe true
        // The first push met the refusal; the second, and the next run, found the sign-in expired.
        tracker.refreshes.size shouldBe 1
        notifier.expired shouldBe listOf(1L)
        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, ENTRY, 7.0), DelayedTrackingItem(43, 1, 1011, 3.0))
        tracker.updates.shouldBeEmpty()
    }

    @Test
    fun `two pushes for one track send the higher number once`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 1.0))
        val tracker = FakeTracker(1)
        store.add(MANGA_ID, 1, ENTRY, 9.0)
        store.add(MANGA_ID, 1, ENTRY, 5.0)

        update(repository, tracker).run() shouldBe true

        tracker.updates.map { it.first.lastChapterRead } shouldBe listOf(9.0)
        repository.rows.getValue(10).lastChapterRead shouldBe 9.0
    }

    @Test
    fun `a service already past the number keeps its own and is not pulled back`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val tracker = FakeTracker(1, refreshed = { it.copy(lastChapterRead = 12.0) })
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(repository, tracker).run() shouldBe true

        tracker.updates.shouldBeEmpty()
        repository.rows.getValue(10).lastChapterRead shouldBe 12.0
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `an entry the stored row already reached is removed unsent`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 8.0))
        val tracker = FakeTracker(1)
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(repository, tracker).run() shouldBe true

        tracker.refreshes.shouldBeEmpty()
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a push raised while the service answered stays for the next run`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val tracker = FakeTracker(1)
        tracker.updated = { sent ->
            // The reader finishes chapter 8 while chapter 7 is on the wire.
            store.add(MANGA_ID, 1, ENTRY, 8.0)
            sent
        }
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(repository, tracker).run() shouldBe true

        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, ENTRY, 8.0))
    }

    @Test
    fun `an entry cleared while the refresh was on the wire is not sent over the number the service holds`() = runTest {
        // Phase 4 unit TR: the sheet's remove clears the queue without the sending lock, so the
        // worker reads the entry once more after the refresh answers.
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 40.0))
        val tracker = FakeTracker(1, refreshed = { remote ->
            store.clear(MANGA_ID, 1)
            remote.copy(lastChapterRead = 40.0)
        })
        store.add(MANGA_ID, 1, ENTRY, 50.0)

        update(repository, tracker).run() shouldBe true

        tracker.updates.shouldBeEmpty()
        repository.rows.getValue(10).lastChapterRead shouldBe 40.0
    }

    @Test
    fun `a run deletes a key in T1's form by track row id and sends the entries in the new form`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val tracker = FakeTracker(1)
        val oldForm = Preference.appStateKey("tracking_queue_10")
        preferences.write(oldForm, "6.0")
        store.add(MANGA_ID, 1, ENTRY, 7.0)

        update(repository, tracker).run() shouldBe true

        preferences.values.keys.filter { it.startsWith(Preference.appStateKey("tracking_queue_")) }.shouldBeEmpty()
        tracker.updates.map { it.first.lastChapterRead } shouldBe listOf(7.0)
        repository.rows.getValue(10).lastChapterRead shouldBe 7.0
    }

    @Test
    fun `a run with only a key in T1's form deletes it and sends nothing`() = runTest {
        val repository = FakeTrackRepository(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        val tracker = FakeTracker(1)
        preferences.write(Preference.appStateKey("tracking_queue_10"), "6.0")

        update(repository, tracker).run() shouldBe true

        preferences.values.keys.shouldBeEmpty()
        tracker.refreshes.shouldBeEmpty()
        repository.inserted.shouldBeEmpty()
    }

    private suspend fun assertKeptUnsent(tracker: FakeTracker) {
        val repository = FakeTrackRepository(track(id = 10, trackerId = tracker.id))
        store.add(MANGA_ID, tracker.id, ENTRY, 7.0)

        update(repository, tracker).run() shouldBe true

        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, tracker.id, ENTRY, 7.0))
        tracker.refreshes.shouldBeEmpty()
        tracker.updates.shouldBeEmpty()
        repository.inserted.shouldBeEmpty()
    }
}

/** The remote id [track] gives the row with id 10. */
private const val ENTRY = 1010L
