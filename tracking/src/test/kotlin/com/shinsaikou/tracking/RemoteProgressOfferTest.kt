package com.shinsaikou.tracking

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track

/**
 * The track sheet's "MyAnimeList is at chapter N: mark read up to N": offered only where Compare
 * would list the entry under "MyAnimeList is ahead" with chapters to mark, and accepted through
 * Compare's own "Mark as read", which marks only while the service still shows N.
 */
class RemoteProgressOfferTest {

    private val library = FakeLibrary()
    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private val repository = FakeTrackRepository()
    private val offers = RemoteProgressOffer(
        library.mangaRepository,
        repository,
        CompareTrackProgress(library.getFavorites, repository, library.chapterRepository, library.historyRepository, store),
        ApplyTrackChoice(
            repository,
            library.chapterRepository,
            library.mangaRepository,
            RecordingTransactionRunner(),
            store,
            RemoveTrack(repository, store),
        ),
    )

    /** A comparing tracker whose read of one entry answers [entry]; the stored row as it is, at first. */
    private class ComparingTracker : FakeTracker(TRACKER), ListComparingTracker {
        var entry: (Track) -> EntryRead = { EntryRead.OnList(it) }

        override suspend fun readList(remoteIds: Set<Long>): ListRead = ListRead.Entries(emptyMap())

        override suspend fun readEntry(track: Track): EntryRead = entry(track)
    }

    private val tracker = ComparingTracker()

    /** Chapters 1 to 20, read through [readThrough], and the service's row at [remote]. */
    private suspend fun entry(readThrough: Double, remote: Double, favorite: Boolean = true) {
        library.addManga(MANGA_ID, favorite = favorite)
        library.addChapters(MANGA_ID, 1..20, readThrough = readThrough)
        repository.insert(track(id = 10, trackerId = TRACKER, lastChapterRead = remote, totalChapters = 20))
    }

    @Test
    fun `the service ahead is offered with its chapter, and accepting marks the chapters up to it`() = runTest {
        entry(readThrough = 8.0, remote = 12.0)

        val offer = offers.find(tracker, MANGA_ID).shouldNotBeNull()

        offer.listed?.chaptersRead shouldBe 12
        offer.toMark.map { it.number } shouldBe listOf(9.0, 10.0, 11.0, 12.0)

        offers.accept(tracker, offer) shouldBe ApplyResult.Done(marked = 4, listed = 4)

        library.chapters.filter { it.read }.map { it.chapterNumber } shouldBe (1..12).map(Int::toDouble)
        offers.find(tracker, MANGA_ID).shouldBeNull()
    }

    @Test
    fun `nothing is offered where the app agrees or is ahead, outside the library, or for a tracker that cannot compare`() = runTest {
        val cases = listOf<Triple<String, suspend () -> Unit, Tracker>>(
            Triple("the app agrees", { entry(readThrough = 12.0, remote = 12.0) }, tracker),
            Triple("the app is ahead", { entry(readThrough = 15.0, remote = 12.0) }, tracker),
            Triple("not in the library", { entry(readThrough = 8.0, remote = 12.0, favorite = false) }, tracker),
            Triple("a tracker that cannot compare", { entry(readThrough = 8.0, remote = 12.0) }, FakeTracker(TRACKER)),
            Triple("no row for the tracker", { library.addManga(MANGA_ID) }, tracker),
        )
        for ((case, arrange, on) in cases) {
            library.manga.clear()
            library.chapters.clear()
            repository.rows.clear()
            arrange()

            withClue(case) { offers.find(on, MANGA_ID).shouldBeNull() }
        }
    }

    @Test
    fun `an offer the service moved past since marks nothing`() = runTest {
        entry(readThrough = 8.0, remote = 12.0)
        val offer = offers.find(tracker, MANGA_ID).shouldNotBeNull()
        tracker.entry = { EntryRead.OnList(it.copy(lastChapterRead = 15.0)) }

        offers.accept(tracker, offer) shouldBe ApplyResult.ChangedSince

        library.chapters.filter { it.read }.map { it.chapterNumber } shouldBe (1..8).map(Int::toDouble)
        repository.rows.getValue(10).lastChapterRead shouldBe 15.0
    }

    private companion object {
        const val TRACKER = 1L
    }
}
