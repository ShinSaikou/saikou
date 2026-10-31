package com.shinsaikou.ui.history

import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

/** The History list: upstream's day header before the first row of each day, in the device's zone. */
class HistoryUiModelTest {

    private val paris = TimeZone.of("Europe/Paris")
    private val newYork = TimeZone.of("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int, zone: TimeZone = paris): Date =
        Date(LocalDateTime(year, month, day, hour, 0).toInstant(zone).toEpochMilliseconds())

    private fun row(id: Long, readAt: Date?) = HistoryWithRelations(
        id = id,
        chapterId = id * 10,
        mangaId = id * 100,
        title = "Entry $id",
        displayTitle = "Entry $id",
        chapterNumber = 1.0,
        readAt = readAt,
        readDuration = 0L,
        coverData = MangaCover(mangaId = id * 100, sourceId = 1L, isMangaFavorite = true, url = null, lastModified = 0L),
    )

    private fun List<HistoryUiModel>.shape(): List<String> = map { model ->
        when (model) {
            is HistoryUiModel.Header -> "H ${model.date}"
            is HistoryUiModel.Item -> "I ${model.history.id}"
        }
    }

    @Test
    fun `an empty list has no header`() {
        historyUiModels(emptyList(), zone = paris) shouldBe emptyList()
    }

    @Test
    fun `a header goes before the first row of each day and nowhere else`() {
        val rows = listOf(
            row(1, at(2026, 10, 30, 21)),
            row(2, at(2026, 10, 30, 8)),
            row(3, at(2026, 10, 29, 23)),
            row(4, at(2026, 10, 12, 10)),
            row(5, at(2026, 10, 12, 9)),
        )

        historyUiModels(rows, zone = paris).shape() shouldBe listOf(
            "H 2026-10-30",
            "I 1",
            "I 2",
            "H 2026-10-29",
            "I 3",
            "H 2026-10-12",
            "I 4",
            "I 5",
        )
    }

    @Test
    fun `the day is read in the given zone`() {
        // 01:00 in Paris is still the evening before in New York.
        val rows = listOf(row(1, at(2026, 10, 30, 1)), row(2, at(2026, 10, 29, 20)))

        historyUiModels(rows, zone = paris).shape() shouldBe listOf("H 2026-10-30", "I 1", "H 2026-10-29", "I 2")
        historyUiModels(rows, zone = newYork).shape() shouldBe listOf("H 2026-10-29", "I 1", "I 2")
    }

    @Test
    fun `a row with no read date gets no header of its own`() {
        val rows = listOf(row(1, at(2026, 10, 30, 9)), row(2, null))

        historyUiModels(rows, zone = paris).shape() shouldBe listOf("H 2026-10-30", "I 1", "I 2")
    }

    @Test
    fun `keys never collide between headers and rows`() {
        val rows = (1L..20L).map { row(it, at(2026, 10, (it % 28 + 1).toInt(), 12)) }

        historyUiModels(rows, zone = paris).map { it.key }.shouldNotContainDuplicates()
    }
}
