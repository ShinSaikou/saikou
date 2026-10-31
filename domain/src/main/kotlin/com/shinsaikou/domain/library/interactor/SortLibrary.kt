package com.shinsaikou.domain.library.interactor

import com.shinsaikou.domain.library.model.LibraryItem
import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.library.model.LibrarySort
import kotlin.random.Random

/**
 * Orders library rows by the active sort, breaking ties with the title collator. The title is the
 * one the grid shows, the owner's edit over the source's (phase 5 unit ED).
 */
@Inject
class SortLibrary {

    private val byTitle = Comparator<LibraryItem> { a, b ->
        a.manga.displayTitle.compareToWithCollator(b.manga.displayTitle)
    }

    /**
     * @param seed the stored random seed, so a Random sort stays stable across recompositions
     * @param trackerMeans the mean tracker score per manga id, over its rows of signed-in trackers;
     *   absent means [NO_TRACKER_SCORE], below every real score, as upstream's default is. Upstream's mean
     *   over rows of no signed-in tracker is NaN, which `Double.compareTo` puts above every score,
     *   so those manga head a descending sort; here they have no entry. Null while no tracker is
     *   signed in: a tracker score sort then orders by title (phase 3 unit T3).
     * @param manualFirst rows with a manual position sort ahead of everything else, ascending
     */
    fun apply(
        items: List<LibraryItem>,
        sort: LibrarySort,
        seed: Int,
        trackerMeans: Map<Long, Double>?,
        manualFirst: Boolean = false,
    ): List<LibraryItem> {
        if (items.isEmpty()) return items

        if (sort.type == LibrarySort.Type.Random) {
            return applyManual(items.shuffled(Random(seed)), manualFirst)
        }

        val sorted = items.sortedWith(comparatorFor(sort.type, trackerMeans))
        val directed = if (sort.isAscending) sorted else sorted.reversed()
        return applyManual(directed, manualFirst)
    }

    private fun comparatorFor(
        type: LibrarySort.Type,
        trackerMeans: Map<Long, Double>?,
    ): Comparator<LibraryItem> = when (type) {
        LibrarySort.Type.Alphabetical -> byTitle
        LibrarySort.Type.LastRead -> compareBy<LibraryItem> { it.libraryManga.lastRead }.then(byTitle)
        LibrarySort.Type.LastUpdate -> compareBy<LibraryItem> { it.manga.lastUpdate }.then(byTitle)
        LibrarySort.Type.UnreadCount -> compareBy<LibraryItem> { it.unreadCount }.then(byTitle)
        LibrarySort.Type.TotalChapters -> compareBy<LibraryItem> { it.libraryManga.totalChapters }.then(byTitle)
        LibrarySort.Type.LatestChapter -> compareBy<LibraryItem> { it.libraryManga.latestUpload }.then(byTitle)
        LibrarySort.Type.ChapterFetchDate -> compareBy<LibraryItem> { it.libraryManga.chapterFetchedAt }.then(byTitle)
        LibrarySort.Type.DateAdded -> compareBy<LibraryItem> { it.manga.dateAdded }.then(byTitle)
        LibrarySort.Type.TrackerMean -> if (trackerMeans == null) {
            byTitle
        } else {
            compareBy<LibraryItem> { trackerMeans[it.id] ?: NO_TRACKER_SCORE }.then(byTitle)
        }
        LibrarySort.Type.Random -> byTitle
    }

    private fun applyManual(items: List<LibraryItem>, manualFirst: Boolean): List<LibraryItem> {
        if (!manualFirst) return items
        val (ranked, rest) = items.partition { it.manga.manualPosition > 0L }
        if (ranked.isEmpty()) return items
        return ranked.sortedBy { it.manga.manualPosition } + rest
    }

    companion object {
        /** The tracker score of a manga with no scored row of a signed-in tracker. */
        const val NO_TRACKER_SCORE = -1.0
    }
}
