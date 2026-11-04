// Fresh: exercises the vendored FetchInterval on an interval the owner chose, kept apart from its vendored test.
package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * `FetchInterval.toMangaUpdate` for an entry whose owner chose the days between checks, which is
 * stored negative. The vendored `FetchIntervalTest` covers the estimate.
 */
class FetchIntervalCustomTest {

    private val start = LocalDateTime.parse("2020-01-01T00:00:00")
    private val zone = TimeZone.UTC
    private val windowAroundDayZero = day(-1) to day(1)

    @Test
    fun `a chosen negative interval is kept and the chapters are not read`() = runTest {
        val getChapters = mockk<GetChaptersByMangaId>()
        val manga = Manga.create().copy(id = 1L, fetchInterval = -5, lastUpdate = day(0))

        val update = FetchInterval(getChapters).toMangaUpdate(manga, atDay(0), zone, windowAroundDayZero)

        update.fetchInterval shouldBe -5
        coVerify(exactly = 0) { getChapters.await(any(), any()) }
    }

    @Test
    fun `next update is the next multiple of the chosen days after the last update`() = runTest {
        val manga = Manga.create().copy(id = 1L, fetchInterval = -7, lastUpdate = day(0), nextUpdate = 0L)

        // Day 10 is inside the second cycle, so the next check is day 14.
        FetchInterval(mockk()).toMangaUpdate(manga, atDay(10), zone, windowAroundDayZero).nextUpdate shouldBe day(14)
    }

    @Test
    fun `a day exactly on a cycle boundary moves on to the cycle after it`() = runTest {
        val manga = Manga.create().copy(id = 1L, fetchInterval = -7, lastUpdate = day(0), nextUpdate = 0L)

        FetchInterval(mockk()).toMangaUpdate(manga, atDay(7), zone, windowAroundDayZero).nextUpdate shouldBe day(14)
    }

    @Test
    fun `a chosen interval is not widened however many checks were missed`() = runTest {
        val manga = Manga.create().copy(id = 1L, fetchInterval = -1, lastUpdate = day(0), nextUpdate = 0L)

        FetchInterval(mockk()).toMangaUpdate(manga, atDay(200), zone, windowAroundDayZero).nextUpdate shouldBe day(201)
    }

    @Test
    fun `a next update inside the grace window is kept`() = runTest {
        val kept = day(30) + 1.hours.inWholeMilliseconds
        val manga = Manga.create().copy(id = 1L, fetchInterval = -7, lastUpdate = day(0), nextUpdate = kept)

        FetchInterval(mockk()).toMangaUpdate(manga, atDay(30), zone, day(29) to day(31)).nextUpdate shouldBe kept
    }

    @Test
    fun `a next update just past the grace window is recomputed`() = runTest {
        val manga = Manga.create().copy(id = 1L, fetchInterval = -7, lastUpdate = day(0), nextUpdate = day(31) + 2)

        FetchInterval(mockk()).toMangaUpdate(manga, atDay(30), zone, day(29) to day(31)).nextUpdate shouldBe day(35)
    }

    @Test
    fun `zero days re-estimates from the chapters`() = runTest {
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(1L, any()) } returns (1..20).map { chapterAt((2 * it).days.inWholeMilliseconds) }
        val manga = Manga.create().copy(id = 1L, fetchInterval = 0, lastUpdate = day(0))

        val update = FetchInterval(getChapters).toMangaUpdate(manga, atDay(0), zone, windowAroundDayZero)

        update.fetchInterval shouldBe 2
    }

    @Test
    fun `a stored positive interval is re-estimated, only a negative one is a choice`() = runTest {
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(1L, any()) } returns emptyList()
        val manga = Manga.create().copy(id = 1L, fetchInterval = 3, lastUpdate = day(0))

        FetchInterval(getChapters).toMangaUpdate(manga, atDay(0), zone, windowAroundDayZero).fetchInterval shouldBe 7
    }

    private fun day(n: Int): Long = start.toInstant(zone).toEpochMilliseconds() + n.days.inWholeMilliseconds

    private fun atDay(n: Int): LocalDateTime = Instant.fromEpochMilliseconds(day(n)).toLocalDateTime(zone)

    private fun chapterAt(offsetMillis: Long): Chapter {
        val time = start.toInstant(zone).toEpochMilliseconds() + offsetMillis
        return Chapter.create().copy(dateFetch = time, dateUpload = time)
    }
}
