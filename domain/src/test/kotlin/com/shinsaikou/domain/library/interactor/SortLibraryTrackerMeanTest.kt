package com.shinsaikou.domain.library.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibrarySort

/**
 * The tracker score sort (phase 3 unit T3): a manga with no scored row of a signed-in tracker sorts
 * as [SortLibrary.NO_TRACKER_SCORE], -1.0, below an unscored row's 0 (upstream's mean over rows of no
 * signed-in tracker is NaN, which heads a descending sort); and with no tracker signed in the sort
 * orders by title.
 */
class SortLibraryTrackerMeanTest {

    private val sortLibrary = SortLibrary()
    // Titled against the score order, so a tie broken by title cannot pass for the score order.
    private val none = libraryItem(1, "Charlie")
    private val unscored = libraryItem(2, "Bravo")
    private val scored = libraryItem(3, "Alpha")
    private val items = listOf(scored, unscored, none)

    private fun sorted(direction: LibrarySort.Direction, trackerMeans: Map<Long, Double>?) = sortLibrary.apply(
        items = items,
        sort = LibrarySort(LibrarySort.Type.TrackerMean, direction),
        seed = 0,
        trackerMeans = trackerMeans,
    ).map { it.manga.title }

    @Test
    fun `a manga with no tracker score sorts below one scored 0`() {
        val means = mapOf(2L to 0.0, 3L to 8.0)

        sorted(LibrarySort.Direction.Ascending, means) shouldBe listOf("Charlie", "Bravo", "Alpha")
        sorted(LibrarySort.Direction.Descending, means) shouldBe listOf("Alpha", "Bravo", "Charlie")
    }

    @Test
    fun `with no tracker signed in the tracker score sort orders by title`() {
        sorted(LibrarySort.Direction.Ascending, trackerMeans = null) shouldBe listOf("Alpha", "Bravo", "Charlie")
    }
}
