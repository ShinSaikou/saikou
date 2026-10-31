package com.shinsaikou.tracking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

/**
 * The bind and its seed step, upstream's `AddTracks.bind` (phase 3 unit T1): a first bind brings the
 * service up to the unbroken run of chapters read here and sets a start date from the first
 * history entry, and neither seed push can undo the bind. The pair's read and its write are one
 * transaction (phase 4 unit TR).
 */
class AddTracksTest {

    private val chapterRepository = mockk<ChapterRepository>()
    private val historyRepository = mockk<HistoryRepository>()
    private val repository = FakeTrackRepository()
    private val transactions = RecordingTransactionRunner()

    /** The pair's read and each write, with whether a transaction was open when it ran. */
    private val calls = mutableListOf<Pair<String, Boolean>>()
    private val recordingRepository = object : TrackRepository by repository {
        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
            calls += "read" to transactions.open
            return repository.getTracksByMangaId(mangaId)
        }

        override suspend fun rebind(track: Track) {
            calls += "rebind" to transactions.open
            repository.rebind(track)
        }

        override suspend fun insert(track: Track) {
            calls += "insert" to transactions.open
            repository.insert(track)
        }
    }
    private val addTracks = AddTracks(
        recordingRepository,
        GetChaptersByMangaId(chapterRepository),
        GetHistory(historyRepository),
        transactions,
    )

    private fun chapters(vararg read: Pair<Double, Boolean>) {
        coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns read.mapIndexed { index, (number, isRead) ->
            Chapter.create().copy(id = index + 1L, mangaId = MANGA_ID, chapterNumber = number, read = isRead)
        }
    }

    private fun at(instant: String): Long = Instant.parse(instant).toEpochMilliseconds()

    private fun history(vararg readAt: Long?) {
        coEvery { historyRepository.getHistoryByMangaId(MANGA_ID) } returns readAt.mapIndexed { index, at ->
            History(id = index + 1L, chapterId = index + 1L, readAt = at?.let(::Date), readDuration = 0)
        }
    }

    @Test
    fun `a first bind with local reads stores the row, then pushes the last of the unbroken run and a start date`() = runTest {
        // 1 to 3 read, 4 unread, 5 read out of order: the run ends at 3, as in upstream.
        chapters(1.0 to true, 2.0 to true, 3.0 to true, 4.0 to false, 5.0 to true)
        history(null, at("2026-05-29T10:00:00Z"), at("2026-05-28T10:00:00Z"))
        val tracker = FakeTracker(1, bound = { track, hasRead -> hasRead shouldBe true; track })

        val bound = inZone("+00:00") { addTracks.bind(tracker, track(id = 10, trackerId = 1)) }

        tracker.remoteLastChapterReads.map { (sent, number) -> sent.lastChapterRead to number } shouldBe listOf(3.0 to 3)
        tracker.remoteStartDates.map { it.second } shouldBe listOf(localMidnight("2026-05-28", "+00:00"))
        bound.lastChapterRead shouldBe 3.0
        bound.startDate shouldBe localMidnight("2026-05-28", "+00:00")
        repository.inserted.first().lastChapterRead shouldBe 0.0
    }

    /**
     * The start date seeded is the day of the first read where it was read, which MyAnimeList is
     * sent as `yyyy-MM-dd` in the same zone. Upstream moved the wall time into UTC: 20:00 at +09:00
     * went out as the next day, and 02:00 at -05:00 as the day before.
     */
    @Test
    fun `the start date seeded is the day of the first read on the device, east and west of UTC`() = runTest {
        for ((offset, firstRead) in listOf("+09:00" to "2026-10-30T20:00:00+09:00", "-05:00" to "2026-10-30T02:00:00-05:00")) {
            inZone(offset) {
                chapters(1.0 to true)
                history(at(firstRead))
                val tracker = FakeTracker(1)

                val bound = addTracks.bind(tracker, track(id = 10, trackerId = 1))

                bound.startDate shouldBe localMidnight("2026-10-30", offset)
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(bound.startDate) shouldBe "2026-10-30"
            }
        }
    }

    @Test
    fun `a bind with nothing read here stores the row and pushes nothing`() = runTest {
        chapters(1.0 to false, 2.0 to false)
        val tracker = FakeTracker(1, bound = { track, hasRead -> hasRead shouldBe false; track })

        val bound = addTracks.bind(tracker, track(id = 10, trackerId = 1))

        bound shouldBe track(id = 10, trackerId = 1)
        repository.inserted shouldBe listOf(bound)
        tracker.remoteLastChapterReads.shouldBeEmpty()
        tracker.remoteStartDates.shouldBeEmpty()
    }

    @Test
    fun `a service already past the local reads and with a start date is left as it is`() = runTest {
        chapters(1.0 to true, 2.0 to true)
        history(1_000_000L)
        val tracker = FakeTracker(1, bound = { track, _ -> track.copy(lastChapterRead = 9.0, startDate = 5L) })

        val bound = addTracks.bind(tracker, track(id = 10, trackerId = 1))

        bound.lastChapterRead shouldBe 9.0
        bound.startDate shouldBe 5L
        tracker.remoteLastChapterReads.shouldBeEmpty()
        tracker.remoteStartDates.shouldBeEmpty()
    }

    @Test
    fun `a seed push that fails leaves the bind standing`() = runTest {
        chapters(1.0 to true)
        history(1_000_000L)
        val tracker = FakeTracker(1).apply { remoteFailure = IOException("fixture: 503") }

        val bound = addTracks.bind(tracker, track(id = 10, trackerId = 1))

        bound shouldBe track(id = 10, trackerId = 1)
        repository.rows.getValue(10) shouldBe bound
        tracker.remoteLastChapterReads.size shouldBe 1
        tracker.remoteStartDates.size shouldBe 1
    }

    @Test
    fun `a bind of a pair bound to another entry is stored as a rebind`() = runTest {
        chapters()
        repository.insert(track(id = 10, trackerId = 1))
        repository.inserted.clear()
        val tracker = FakeTracker(1)

        val bound = addTracks.bind(tracker, track(id = 11, trackerId = 1))

        bound.remoteId shouldBe 1011L
        repository.rebound shouldBe listOf(bound)
        repository.inserted.shouldBeEmpty()
        repository.rows.values.toList() shouldBe listOf(bound)
    }

    @Test
    fun `a bind of a pair already bound to the same entry is a plain insert`() = runTest {
        chapters()
        repository.insert(track(id = 10, trackerId = 1, lastChapterRead = 4.0))
        repository.inserted.clear()
        val tracker = FakeTracker(1)

        addTracks.bind(tracker, track(id = 10, trackerId = 1))

        repository.rebound.shouldBeEmpty()
        repository.inserted.size shouldBe 1
    }

    @Test
    fun `the pair's read and its write are one transaction, for a rebind and for a bind to the same entry`() = runTest {
        chapters()
        repository.insert(track(id = 10, trackerId = 1))
        val tracker = FakeTracker(1)

        addTracks.bind(tracker, track(id = 11, trackerId = 1))
        addTracks.bind(tracker, track(id = 11, trackerId = 1))

        calls shouldBe listOf("read" to true, "rebind" to true, "read" to true, "insert" to true)
        transactions.opened shouldBe 2
        repository.rows.values.map { it.remoteId } shouldBe listOf(1011L)
    }

    @Test
    fun `a bind on another tracker leaves the first tracker's row and is a plain insert`() = runTest {
        chapters()
        repository.insert(track(id = 10, trackerId = 1))
        repository.inserted.clear()

        addTracks.bind(FakeTracker(2), track(id = 11, trackerId = 2))

        repository.rebound.shouldBeEmpty()
        repository.rows.keys shouldBe setOf(10L, 11L)
    }

    @Test
    fun `a search result is bound as a row with no progress of its own`() = runTest {
        chapters()
        val tracker = FakeTracker(1)
        val result = TrackSearchResult(
            trackerId = 1,
            remoteId = 1001,
            title = "Fixture Title",
            trackingUrl = "https://tracker.example.invalid/entry/1001",
            coverUrl = "",
            summary = "",
            publishingStatus = "",
            publishingType = "",
            startDate = "",
            totalChapters = 12,
            authors = emptyList(),
            artists = emptyList(),
        )

        val bound = addTracks.bind(tracker, result, MANGA_ID)

        bound.mangaId shouldBe MANGA_ID
        bound.trackerId shouldBe 1L
        bound.remoteId shouldBe 1001L
        bound.title shouldBe "Fixture Title"
        bound.totalChapters shouldBe 12L
        bound.remoteUrl shouldBe "https://tracker.example.invalid/entry/1001"
        bound.lastChapterRead shouldBe 0.0
        bound.status shouldBe 0L
        repository.inserted shouldBe listOf(bound)
    }
}
