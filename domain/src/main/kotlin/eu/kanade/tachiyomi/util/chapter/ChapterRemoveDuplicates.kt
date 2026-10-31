// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/util/chapter/ChapterRemoveDuplicates.kt (modified: yes)
package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter

/**
 * Returns a copy of the list with duplicate chapters removed
 *
 * Saikou adds the title's scanlator ranking ([ranking], group to rank, lowest first, empty when the
 * title is unranked; phase 4 unit SC). Within one chapter number the current chapter still wins
 * first. Then, if any copy's group is ranked, the best-ranked copy wins: a stored 0 is the top rank,
 * and a group with no rank comes after every ranked one. If no copy is ranked, upstream's rule runs
 * unchanged, so an unranked title behaves as it does in upstream.
 *
 * With a ranking the next chapter follows the ranking, not the group being read. To follow the
 * group being read instead, move the `currentChapter.scanlator` line above the `bestRanked` line.
 *
 * [currentChapter] is null for the resume buttons, which have no chapter open: the ranked copy
 * wins, else the first copy, which is the chapter the button opened before the ranking existed.
 */
fun List<Chapter>.removeDuplicates(currentChapter: Chapter?, ranking: Map<String, Long>): List<Chapter> {
    return groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter?.id }
                ?: chapters.bestRanked(ranking)
                ?: chapters.find { currentChapter != null && it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }
}

/** The copy whose group has the lowest rank, or null when no copy's group is ranked. */
private fun List<Chapter>.bestRanked(ranking: Map<String, Long>): Chapter? {
    if (ranking.isEmpty()) return null
    return mapNotNull { chapter -> ranking[chapter.scanlator]?.let { rank -> chapter to rank } }
        .minByOrNull { (_, rank) -> rank }
        ?.first
}
