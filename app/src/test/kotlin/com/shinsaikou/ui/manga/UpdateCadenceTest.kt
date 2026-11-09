package com.shinsaikou.ui.manga

import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/** Pure function, so every case passes its zone and inputs and reads the result. */
class UpdateCadenceTest {

    private val zone = TimeZone.UTC
    private val nextDay = LocalDate(2026, 11, 20)
    private val nextMillis = nextDay.atStartOfDayIn(zone).toEpochMilliseconds()

    private val allRules = setOf(
        LibraryUpdateRestrictions.MANGA_NON_COMPLETED,
        LibraryUpdateRestrictions.MANGA_HAS_UNREAD,
        LibraryUpdateRestrictions.MANGA_NON_READ,
        LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD,
    )

    private fun manga(
        status: MangaStatus = MangaStatus.ONGOING,
        fetchInterval: Int = 7,
        nextUpdate: Long = nextMillis,
        strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    ) = Manga.create().copy(
        status = status.code.toLong(),
        fetchInterval = fetchInterval,
        nextUpdate = nextUpdate,
        updateStrategy = strategy,
    )

    private fun cadence(
        manga: Manga = manga(),
        restrictions: Set<String> = emptySet(),
        automatic: Boolean = true,
        total: Int = 10,
        read: Int = 10,
    ) = updateCadenceOf(manga, restrictions, automatic, total, read, zone)

    @Test
    fun `an entry fetched once that has chapters is FetchOnce`() {
        cadence(manga(strategy = UpdateStrategy.ONLY_FETCH_ONCE), total = 3, read = 3) shouldBe UpdateCadence.FetchOnce
    }

    @Test
    fun `fetch once beats automatic updates being off`() {
        cadence(manga(strategy = UpdateStrategy.ONLY_FETCH_ONCE), automatic = false, total = 1, read = 0) shouldBe
            UpdateCadence.FetchOnce
    }

    @Test
    fun `fetch once beats every skip rule`() {
        cadence(
            manga(status = MangaStatus.COMPLETED, strategy = UpdateStrategy.ONLY_FETCH_ONCE),
            restrictions = allRules,
            total = 5,
            read = 0,
        ) shouldBe UpdateCadence.FetchOnce
    }

    @Test
    fun `fetch once with no chapters yet falls through to the other rules`() {
        cadence(manga(strategy = UpdateStrategy.ONLY_FETCH_ONCE), restrictions = emptySet(), total = 0, read = 0) shouldBe
            UpdateCadence.EveryUpdate
    }

    @Test
    fun `automatic updates off gives Predicted with the date and days of the prediction`() {
        cadence(manga(fetchInterval = 7), automatic = false) shouldBe UpdateCadence.Predicted(7, nextDay)
    }

    @Test
    fun `automatic updates off reads a chosen negative interval as its days`() {
        cadence(manga(fetchInterval = -5), automatic = false) shouldBe UpdateCadence.Predicted(5, nextDay)
    }

    @Test
    fun `automatic updates off gives Completed for a completed entry, whatever it predicts`() {
        cadence(manga(status = MangaStatus.COMPLETED), automatic = false) shouldBe UpdateCadence.Completed
    }

    @Test
    fun `automatic updates off with no interval gives NoPrediction`() {
        cadence(manga(fetchInterval = 0), automatic = false) shouldBe UpdateCadence.NoPrediction
    }

    @Test
    fun `automatic updates off with no next update gives NoPrediction`() {
        cadence(manga(nextUpdate = 0L), automatic = false) shouldBe UpdateCadence.NoPrediction
    }

    @Test
    fun `automatic updates off ignores the skip rules`() {
        cadence(manga(), restrictions = allRules, automatic = false, total = 5, read = 0) shouldBe
            UpdateCadence.Predicted(7, nextDay)
    }

    @Test
    fun `a completed entry is CompletedSkipped when the non completed rule is on`() {
        cadence(manga(status = MangaStatus.COMPLETED), restrictions = setOf(LibraryUpdateRestrictions.MANGA_NON_COMPLETED)) shouldBe
            UpdateCadence.CompletedSkipped
    }

    @Test
    fun `a completed entry is not skipped when the non completed rule is off`() {
        cadence(manga(status = MangaStatus.COMPLETED), restrictions = emptySet()) shouldBe UpdateCadence.EveryUpdate
    }

    @Test
    fun `an ongoing entry is not CompletedSkipped by the non completed rule`() {
        cadence(manga(), restrictions = setOf(LibraryUpdateRestrictions.MANGA_NON_COMPLETED)) shouldBe
            UpdateCadence.EveryUpdate
    }

    @Test
    fun `the owner's status edit is the one the non completed rule reads (R165 item 4)`() {
        val completed = MangaStatus.COMPLETED.code.toLong()
        val ongoing = MangaStatus.ONGOING.code.toLong()
        val rule = setOf(LibraryUpdateRestrictions.MANGA_NON_COMPLETED)

        cadence(manga().copy(statusOverride = completed), restrictions = rule) shouldBe UpdateCadence.CompletedSkipped
        cadence(manga(status = MangaStatus.COMPLETED).copy(statusOverride = ongoing), restrictions = rule) shouldBe
            UpdateCadence.EveryUpdate
    }

    @Test
    fun `unread chapters are UnreadSkipped when the has unread rule is on`() {
        cadence(restrictions = setOf(LibraryUpdateRestrictions.MANGA_HAS_UNREAD), total = 10, read = 9) shouldBe
            UpdateCadence.UnreadSkipped
    }

    @Test
    fun `everything read is not UnreadSkipped`() {
        cadence(restrictions = setOf(LibraryUpdateRestrictions.MANGA_HAS_UNREAD), total = 10, read = 10) shouldBe
            UpdateCadence.EveryUpdate
    }

    @Test
    fun `the has unread rule off does not skip an entry with unread chapters`() {
        cadence(restrictions = emptySet(), total = 10, read = 0) shouldBe UpdateCadence.EveryUpdate
    }

    @Test
    fun `an entry with chapters and none read is NotStartedSkipped when the not started rule is on`() {
        cadence(restrictions = setOf(LibraryUpdateRestrictions.MANGA_NON_READ), total = 4, read = 0) shouldBe
            UpdateCadence.NotStartedSkipped
    }

    @Test
    fun `an entry with no chapters is not NotStartedSkipped`() {
        cadence(restrictions = setOf(LibraryUpdateRestrictions.MANGA_NON_READ), total = 0, read = 0) shouldBe
            UpdateCadence.EveryUpdate
    }

    @Test
    fun `an entry with one chapter read is not NotStartedSkipped`() {
        cadence(restrictions = setOf(LibraryUpdateRestrictions.MANGA_NON_READ), total = 4, read = 1) shouldBe
            UpdateCadence.EveryUpdate
    }

    @Test
    fun `the completed skip comes before the unread skip`() {
        cadence(
            manga(status = MangaStatus.COMPLETED),
            restrictions = setOf(LibraryUpdateRestrictions.MANGA_NON_COMPLETED, LibraryUpdateRestrictions.MANGA_HAS_UNREAD),
            total = 10,
            read = 0,
        ) shouldBe UpdateCadence.CompletedSkipped
    }

    @Test
    fun `the unread skip comes before the not started skip`() {
        cadence(
            restrictions = setOf(LibraryUpdateRestrictions.MANGA_HAS_UNREAD, LibraryUpdateRestrictions.MANGA_NON_READ),
            total = 10,
            read = 0,
        ) shouldBe UpdateCadence.UnreadSkipped
    }

    @Test
    fun `a skip rule comes before the release rule`() {
        cadence(restrictions = allRules, total = 10, read = 0) shouldBe UpdateCadence.UnreadSkipped
    }

    @Test
    fun `the release rule off gives EveryUpdate even with a prediction`() {
        cadence(manga(fetchInterval = 7), restrictions = emptySet()) shouldBe UpdateCadence.EveryUpdate
    }

    @Test
    fun `the release rule off gives EveryUpdate for a chosen interval`() {
        cadence(manga(fetchInterval = -3), restrictions = emptySet()) shouldBe UpdateCadence.EveryUpdate
    }

    @Test
    fun `a negative interval under the release rule gives Custom with its days and next check`() {
        cadence(
            manga(fetchInterval = -14),
            restrictions = setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD),
        ) shouldBe UpdateCadence.Custom(14, nextDay)
    }

    @Test
    fun `a negative interval beats a completed entry once the skips pass`() {
        cadence(
            manga(status = MangaStatus.COMPLETED, fetchInterval = -14),
            restrictions = setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD),
        ) shouldBe UpdateCadence.Custom(14, nextDay)
    }

    @Test
    fun `a completed entry under the release rule alone is Completed`() {
        cadence(
            manga(status = MangaStatus.COMPLETED),
            restrictions = setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD),
        ) shouldBe UpdateCadence.Completed
    }

    @Test
    fun `an interval of 0 under the release rule gives NoPrediction`() {
        cadence(manga(fetchInterval = 0), restrictions = setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD)) shouldBe
            UpdateCadence.NoPrediction
    }

    @Test
    fun `a next update of 0 under the release rule gives NoPrediction`() {
        cadence(manga(nextUpdate = 0L), restrictions = setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD)) shouldBe
            UpdateCadence.NoPrediction
    }

    @Test
    fun `a positive interval under the release rule gives Estimated`() {
        cadence(manga(fetchInterval = 7), restrictions = setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD)) shouldBe
            UpdateCadence.Estimated(7, nextDay)
    }

    @Test
    fun `the next date follows the zone it is given`() {
        // 23:30 UTC on the 20th is already the 21st in Tokyo.
        val lateUtc = nextMillis + 23 * 3_600_000L + 30 * 60_000L

        updateCadenceOf(
            manga(nextUpdate = lateUtc),
            setOf(LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD),
            true,
            1,
            1,
            TimeZone.of("Asia/Tokyo"),
        ) shouldBe UpdateCadence.Estimated(7, LocalDate(2026, 11, 21))
    }
}
