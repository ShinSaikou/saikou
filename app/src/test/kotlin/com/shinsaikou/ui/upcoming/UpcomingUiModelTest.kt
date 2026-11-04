package com.shinsaikou.ui.upcoming

import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class UpcomingUiModelTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 11, 10)
    private val startOfToday = today.atStartOfDayIn(zone).toEpochMilliseconds()
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun item(
        id: Long,
        nextUpdate: Long = startOfToday + day,
        status: MangaStatus = MangaStatus.ONGOING,
        strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        categories: List<Long> = emptyList(),
    ) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            nextUpdate = nextUpdate,
            status = status.code.toLong(),
            updateStrategy = strategy,
        ),
        categories = categories,
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    private fun entries(
        vararg library: LibraryManga,
        included: Set<Long> = emptySet(),
        excluded: Set<Long> = emptySet(),
    ) = upcomingEntries(library.toList(), included, excluded, startOfToday).map { it.id }

    @Test
    fun `ongoing and publishing finished entries are listed`() {
        entries(
            item(1L, status = MangaStatus.ONGOING),
            item(2L, status = MangaStatus.PUBLISHING_FINISHED),
        ) shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `other statuses are dropped`() {
        val others = listOf(
            MangaStatus.UNKNOWN,
            MangaStatus.COMPLETED,
            MangaStatus.LICENSED,
            MangaStatus.CANCELLED,
            MangaStatus.ON_HIATUS,
        )

        entries(*others.mapIndexed { index, status -> item(index + 1L, status = status) }.toTypedArray())
            .shouldBeEmpty()
    }

    @Test
    fun `an entry due exactly at the start of today is listed`() {
        entries(item(1L, nextUpdate = startOfToday)) shouldContainExactly listOf(1L)
    }

    @Test
    fun `an entry due a millisecond before today is dropped`() {
        entries(item(1L, nextUpdate = startOfToday - 1)).shouldBeEmpty()
    }

    @Test
    fun `an entry never fetched, with a next update of 0, is dropped`() {
        entries(item(1L, nextUpdate = 0L)).shouldBeEmpty()
    }

    @Test
    fun `an entry only fetched once is dropped`() {
        entries(item(1L, strategy = UpdateStrategy.ONLY_FETCH_ONCE), item(2L)) shouldContainExactly listOf(2L)
    }

    @Test
    fun `an entry in an excluded category is dropped even when it is also in an included one`() {
        entries(
            item(1L, categories = listOf(5L, 6L)),
            item(2L, categories = listOf(5L)),
            included = setOf(5L),
            excluded = setOf(6L),
        ) shouldContainExactly listOf(2L)
    }

    @Test
    fun `with a non empty included set only entries in it are listed`() {
        entries(
            item(1L, categories = listOf(5L)),
            item(2L, categories = listOf(9L)),
            item(3L, categories = emptyList()),
            included = setOf(5L),
        ) shouldContainExactly listOf(1L)
    }

    @Test
    fun `an empty included set lists entries in any category or none`() {
        entries(item(1L, categories = listOf(9L)), item(2L, categories = emptyList())) shouldContainExactly
            listOf(1L, 2L)
    }

    @Test
    fun `entries are sorted soonest first`() {
        entries(
            item(1L, nextUpdate = startOfToday + 3 * day),
            item(2L, nextUpdate = startOfToday + day),
            item(3L, nextUpdate = startOfToday + 2 * day),
        ) shouldContainExactly listOf(2L, 3L, 1L)
    }

    @Test
    fun `an empty library lists nothing`() {
        entries().shouldBeEmpty()
        upcomingUiModels(emptyList(), zone).shouldBeEmpty()
    }

    @Test
    fun `each day gets one header with its count followed by its entries`() {
        val sorted = listOf(
            item(1L, nextUpdate = startOfToday + 2 * hour),
            item(2L, nextUpdate = startOfToday + 5 * hour),
            item(3L, nextUpdate = startOfToday + day + hour),
        ).map { it.manga }

        val models = upcomingUiModels(sorted, zone)

        models shouldContainExactly listOf(
            UpcomingUiModel.Header(today, 2),
            UpcomingUiModel.Item(sorted[0]),
            UpcomingUiModel.Item(sorted[1]),
            UpcomingUiModel.Header(LocalDate(2026, 11, 11), 1),
            UpcomingUiModel.Item(sorted[2]),
        )
    }

    @Test
    fun `days are grouped in the zone it is given`() {
        // 23:30 UTC and 00:30 UTC next day are two UTC days and one day in Tokyo (UTC+9).
        val late = item(1L, nextUpdate = startOfToday + 23 * hour + 30 * 60_000L).manga
        val early = item(2L, nextUpdate = startOfToday + day + 30 * 60_000L).manga

        upcomingUiModels(listOf(late, early), TimeZone.UTC).filterIsInstance<UpcomingUiModel.Header>()
            .map { it.date to it.count } shouldContainExactly listOf(today to 1, LocalDate(2026, 11, 11) to 1)
        upcomingUiModels(listOf(late, early), TimeZone.of("Asia/Tokyo")).filterIsInstance<UpcomingUiModel.Header>()
            .map { it.date to it.count } shouldContainExactly listOf(LocalDate(2026, 11, 11) to 2)
    }

    @Test
    fun `list keys keep headers and entries apart`() {
        val manga = item(7L).manga

        UpcomingUiModel.Item(manga).key shouldBe 7L
        UpcomingUiModel.Header(today, 1).key shouldBe "upcoming-header-2026-11-10"
    }
}
