package com.shinsaikou.ui.updates

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * The Updates list (phase 4 unit UG): upstream's day header before the first row of each day, the
 * day read in the device's zone as upstream's `getUiModel` reads it, and inside a day one entry's
 * consecutive chapters folded, a fold of more than three starting collapsed.
 */
class UpdatesUiModelTest {

    private val paris = TimeZone.of("Europe/Paris")
    private val newYork = TimeZone.of("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: TimeZone = paris): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private fun row(chapterId: Long, mangaId: Long, dateFetch: Long) = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = "Entry $mangaId",
        displayTitle = "Entry $mangaId",
        chapterId = chapterId,
        chapterName = "Chapter $chapterId",
        scanlator = null,
        chapterUrl = "/chapter/$chapterId",
        read = false,
        bookmark = false,
        lastPageRead = 0L,
        sourceId = 1L,
        dateFetch = dateFetch,
        coverData = MangaCover(mangaId = mangaId, sourceId = 1L, isMangaFavorite = true, url = null, lastModified = 0L),
    )

    /** A readable shape of the list: H for a header with its date, F for a fold, I for a row. */
    private fun List<UpdatesUiModel>.shape(): List<String> = map { model ->
        when (model) {
            is UpdatesUiModel.Header -> "H ${model.date}"
            is UpdatesUiModel.Fold ->
                "F ${model.updates.first().mangaId} ${model.chapterIds} ${if (model.expanded) "open" else "closed"}"
            is UpdatesUiModel.Item -> if (model.inFold) "  I ${model.update.chapterId}" else "I ${model.update.chapterId}"
        }
    }

    @Test
    fun `a merged title's rows fold together, from whichever member each came`() {
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = at(2026, 9, 2, 10)).copy(mergedMainId = 10),
            row(2, mangaId = 11, dateFetch = at(2026, 9, 2, 9)).copy(mergedMainId = 10),
        )

        updatesUiModels(rows, zone = paris).shape() shouldBe
            listOf("H 2026-09-02", "F 10 [1, 2] open", "  I 1", "  I 2")
    }

    @Test
    fun `an empty list has no header`() {
        updatesUiModels(emptyList(), zone = paris) shouldBe emptyList()
    }

    @Test
    fun `a header goes before the first row of each day and nowhere else`() {
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = at(2026, 9, 27, 18)),
            row(2, mangaId = 11, dateFetch = at(2026, 9, 27, 9)),
            row(3, mangaId = 12, dateFetch = at(2026, 9, 26, 22)),
            row(4, mangaId = 13, dateFetch = at(2026, 9, 20, 8)),
        )

        updatesUiModels(rows, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "I 1",
            "I 2",
            "H 2026-09-26",
            "I 3",
            "H 2026-09-20",
            "I 4",
        )
    }

    @Test
    fun `midnight in the zone splits the day`() {
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = at(2026, 9, 27, 0, 30)),
            row(2, mangaId = 11, dateFetch = at(2026, 9, 26, 23, 30)),
        )

        updatesUiModels(rows, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "I 1",
            "H 2026-09-26",
            "I 2",
        )
    }

    @Test
    fun `the same two instants fall on one day in another zone`() {
        // 00:30 and 23:30 in Paris are 18:30 and 17:30 the day before in New York.
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = at(2026, 9, 27, 0, 30)),
            row(2, mangaId = 11, dateFetch = at(2026, 9, 26, 23, 30)),
        )

        updatesUiModels(rows, zone = newYork).shape() shouldContainExactly listOf(
            "H 2026-09-26",
            "I 1",
            "I 2",
        )
    }

    @Test
    fun `midnight splits one entry's run into a fold or a row under each day`() {
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = at(2026, 9, 27, 0, 20)),
            row(2, mangaId = 10, dateFetch = at(2026, 9, 27, 0, 10)),
            row(3, mangaId = 10, dateFetch = at(2026, 9, 26, 23, 50)),
        )

        updatesUiModels(rows, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 10 [1, 2] open",
            "  I 1",
            "  I 2",
            "H 2026-09-26",
            "I 3",
        )
        // In New York all three are on the 26th, so they fold together.
        updatesUiModels(rows, zone = newYork).shape() shouldContainExactly listOf(
            "H 2026-09-26",
            "F 10 [1, 2, 3] open",
            "  I 1",
            "  I 2",
            "  I 3",
        )
    }

    @Test
    fun `a single chapter of an entry is a plain row, not a fold`() {
        val rows = listOf(row(1, mangaId = 10, dateFetch = at(2026, 9, 27, 12)))

        updatesUiModels(rows, zone = paris).shape() shouldContainExactly listOf("H 2026-09-27", "I 1")
    }

    @Test
    fun `three chapters fold open and four fold closed`() {
        val day = at(2026, 9, 27, 12)
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = day),
            row(2, mangaId = 10, dateFetch = day),
            row(3, mangaId = 10, dateFetch = day),
            row(4, mangaId = 20, dateFetch = day - 1),
            row(5, mangaId = 20, dateFetch = day - 2),
            row(6, mangaId = 20, dateFetch = day - 3),
            row(7, mangaId = 20, dateFetch = day - 4),
        )

        updatesUiModels(rows, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 10 [1, 2, 3] open",
            "  I 1",
            "  I 2",
            "  I 3",
            "F 20 [4, 5, 6, 7] closed",
        )
    }

    @Test
    fun `the default is the threshold and nothing else`() {
        foldStartsExpanded(2) shouldBe true
        foldStartsExpanded(3) shouldBe true
        foldStartsExpanded(4) shouldBe false
        foldStartsExpanded(40) shouldBe false
    }

    @Test
    fun `another entry between two runs breaks the run and each run is its own fold`() {
        val day = at(2026, 9, 27, 12)
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = day),
            row(2, mangaId = 10, dateFetch = day),
            row(3, mangaId = 20, dateFetch = day),
            row(4, mangaId = 10, dateFetch = day),
            row(5, mangaId = 10, dateFetch = day),
            row(6, mangaId = 30, dateFetch = day),
            row(7, mangaId = 10, dateFetch = day),
        )

        updatesUiModels(rows, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 10 [1, 2] open",
            "  I 1",
            "  I 2",
            "I 3",
            "F 10 [4, 5] open",
            "  I 4",
            "  I 5",
            "I 6",
            "I 7",
        )
    }

    @Test
    fun `an entry that updated on two days shows once under each`() {
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = at(2026, 9, 27, 12)),
            row(2, mangaId = 10, dateFetch = at(2026, 9, 27, 12)),
            row(3, mangaId = 10, dateFetch = at(2026, 9, 25, 12)),
            row(4, mangaId = 10, dateFetch = at(2026, 9, 25, 12)),
            row(5, mangaId = 10, dateFetch = at(2026, 9, 25, 12)),
            row(6, mangaId = 10, dateFetch = at(2026, 9, 25, 12)),
        )

        val models = updatesUiModels(rows, zone = paris)

        models.shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 10 [1, 2] open",
            "  I 1",
            "  I 2",
            "H 2026-09-25",
            "F 10 [3, 4, 5, 6] closed",
        )
        models.filterIsInstance<UpdatesUiModel.Fold>().map { it.key } shouldContainExactly listOf(
            "updates-fold-2",
            "updates-fold-6",
        )
    }

    @Test
    fun `a fold the owner opened or closed keeps that choice`() {
        val day = at(2026, 9, 27, 12)
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = day),
            row(2, mangaId = 10, dateFetch = day),
            row(3, mangaId = 20, dateFetch = day),
            row(4, mangaId = 20, dateFetch = day),
            row(5, mangaId = 20, dateFetch = day),
            row(6, mangaId = 20, dateFetch = day),
        )
        val chosen = mapOf(1L to false, 2L to false) + (3L..6L).associateWith { true }

        updatesUiModels(rows, chosen, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 10 [1, 2] closed",
            "F 20 [3, 4, 5, 6] open",
            "  I 3",
            "  I 4",
            "  I 5",
            "  I 6",
        )
    }

    @Test
    fun `every item has its own key and a chapter row keeps its chapter id`() {
        val rows = listOf(
            row(1, mangaId = 10, dateFetch = at(2026, 9, 27, 12)),
            row(2, mangaId = 10, dateFetch = at(2026, 9, 27, 12)),
            row(3, mangaId = 10, dateFetch = at(2026, 9, 26, 12)),
            row(4, mangaId = 10, dateFetch = at(2026, 9, 26, 12)),
        )

        val models = updatesUiModels(rows, zone = paris)

        models.map { it.key }.shouldNotContainDuplicates()
        models.filterIsInstance<UpdatesUiModel.Item>().map { it.key } shouldContainExactly listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `a newer run of the same entry above a chosen fold does not take the choice`() {
        // Entry 100 has two runs on one day, split by entry 200; the owner opened the older one.
        val first = listOf(
            row(10, mangaId = 100, dateFetch = at(2026, 9, 27, 12, 4)),
            row(9, mangaId = 100, dateFetch = at(2026, 9, 27, 12, 3)),
            row(8, mangaId = 100, dateFetch = at(2026, 9, 27, 12, 2)),
            row(7, mangaId = 100, dateFetch = at(2026, 9, 27, 12, 1)),
            row(6, mangaId = 200, dateFetch = at(2026, 9, 27, 10)),
            row(5, mangaId = 100, dateFetch = at(2026, 9, 27, 9, 4)),
            row(4, mangaId = 100, dateFetch = at(2026, 9, 27, 9, 3)),
            row(3, mangaId = 100, dateFetch = at(2026, 9, 27, 9, 2)),
            row(2, mangaId = 100, dateFetch = at(2026, 9, 27, 9, 1)),
        )
        val chosen = (2L..5L).associateWith { true }
        updatesUiModels(first, chosen, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 100 [10, 9, 8, 7] closed",
            "I 6",
            "F 100 [5, 4, 3, 2] open",
            "  I 5",
            "  I 4",
            "  I 3",
            "  I 2",
        )

        // A later fetch the same day adds two newer chapters of 100 above a row of entry 300.
        val later = listOf(
            row(14, mangaId = 100, dateFetch = at(2026, 9, 27, 20, 2)),
            row(13, mangaId = 100, dateFetch = at(2026, 9, 27, 20, 1)),
            row(11, mangaId = 300, dateFetch = at(2026, 9, 27, 15)),
        ) + first

        updatesUiModels(later, chosen, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 100 [14, 13] open",
            "  I 14",
            "  I 13",
            "I 11",
            "F 100 [10, 9, 8, 7] closed",
            "I 6",
            "F 100 [5, 4, 3, 2] open",
            "  I 5",
            "  I 4",
            "  I 3",
            "  I 2",
        )
    }

    @Test
    fun `a chosen fold that gains newer chapters keeps the choice and its key`() {
        val day = at(2026, 9, 27, 12)
        val first = (4L downTo 1L).map { row(it, mangaId = 10, dateFetch = day) }
        val chosen = (1L..4L).associateWith { true }
        val before = updatesUiModels(first, chosen, zone = paris).filterIsInstance<UpdatesUiModel.Fold>().single()

        val later = listOf(row(6, mangaId = 10, dateFetch = day + 2), row(5, mangaId = 10, dateFetch = day + 1)) + first
        val after = updatesUiModels(later, chosen, zone = paris).filterIsInstance<UpdatesUiModel.Fold>().single()

        after.chapterIds shouldContainExactly listOf(6L, 5L, 4L, 3L, 2L, 1L)
        after.expanded shouldBe true
        after.key shouldBe before.key
    }

    @Test
    fun `a chosen fold split by another entry's row keeps the choice on both halves`() {
        val day = at(2026, 9, 27, 12)
        val whole = (6L downTo 1L).map { row(it, mangaId = 10, dateFetch = day) }
        val chosen = (1L..6L).associateWith { true }
        val split = whole.take(3) + row(7, mangaId = 20, dateFetch = day) + whole.drop(3)

        updatesUiModels(split, chosen, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 10 [6, 5, 4] open",
            "  I 6",
            "  I 5",
            "  I 4",
            "I 7",
            "F 10 [3, 2, 1] open",
            "  I 3",
            "  I 2",
            "  I 1",
        )
    }

    @Test
    fun `two chosen folds joined into one take the newer one's choice`() {
        // The row of entry 20 that split them left the list (a filter), so 10's runs join.
        val day = at(2026, 9, 27, 12)
        val rows = (6L downTo 1L).map { row(it, mangaId = 10, dateFetch = day) }
        val chosen = (4L..6L).associateWith { false } + (1L..3L).associateWith { true }

        updatesUiModels(rows, chosen, zone = paris).shape() shouldContainExactly listOf(
            "H 2026-09-27",
            "F 10 [6, 5, 4, 3, 2, 1] closed",
        )
    }
}
