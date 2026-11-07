package com.shinsaikou.domain.merged

import com.shinsaikou.domain.merged.model.MergedGroup
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

/** Which chapters of a merged entry show (`design/plans/merged-entries.md` section 4). */
object MergedChapters {

    /**
     * [chapters] with one copy of each number, in the order given. A number's winner is the highest
     * member in the group's order that has it, an uninstalled source last; order is the only rule.
     * Every row of the winning member stays, so scanlators inside one source behave as they do
     * alone. A chapter with no recognised number is always kept, a chapter of no member ranks last,
     * and [MergedGroup.showAll] skips the pass.
     *
     * A downloaded copy never outranks the order: members refresh concurrently, so a lower source's
     * chapter can land first, be auto-downloaded and then pin that source for good, and a row would
     * change identity under an open reader when a download finishes. Under Downloaded only, the
     * caller's downloaded filter has already run, so a number whose winner is not downloaded keeps
     * the copy that is.
     */
    fun visible(chapters: List<Chapter>, group: MergedGroup, installedSources: Set<Long>): List<Chapter> {
        if (group.showAll) return chapters

        val order = group.members.sortedWith(compareBy({ it.source !in installedSources }, { it.position }))
        val ranks = order.withIndex().associate { (rank, member) -> member.mangaId to rank }

        fun rankOf(chapter: Chapter): Int = ranks[chapter.mangaId] ?: ranks.size

        val winners = HashMap<Double, Int>()
        for (chapter in chapters) {
            if (!chapter.isRecognizedNumber) continue
            val rank = rankOf(chapter)
            val best = winners[chapter.chapterNumber]
            if (best == null || rank < best) winners[chapter.chapterNumber] = rank
        }
        return chapters.filter { !it.isRecognizedNumber || rankOf(it) == winners[it.chapterNumber] }
    }

    /**
     * [getChapterSort] for a merged list. `source_order` means nothing across sources, so a list
     * sorted by source sorts by number, in the same direction. [manga] is the main entry.
     */
    fun sort(manga: Manga, sortDescending: Boolean = manga.sortDescending()): (Chapter, Chapter) -> Int = when {
        manga.sorting != Manga.CHAPTER_SORTING_SOURCE -> getChapterSort(manga, sortDescending)
        sortDescending -> { a, b -> b.chapterNumber.compareTo(a.chapterNumber) }
        else -> { a, b -> a.chapterNumber.compareTo(b.chapterNumber) }
    }

    /**
     * [manga] with the sort its merged list really has, for the sheet that shows and changes it: a
     * stored sort by source is the sort by number [sort] answers, in the same direction.
     */
    fun sorted(manga: Manga): Manga = when (manga.sorting) {
        Manga.CHAPTER_SORTING_SOURCE -> manga.copy(
            chapterFlags = (manga.chapterFlags and Manga.CHAPTER_SORTING_MASK.inv()) or Manga.CHAPTER_SORTING_NUMBER,
        )
        else -> manga
    }

    /**
     * The rows of [shown] whose number has a copy [shown] left out with its files on disk, of
     * [all] the group's chapters. A row badged here can be read offline through Show every copy.
     */
    fun withHiddenDownload(all: List<Chapter>, shown: List<Chapter>, downloaded: Set<Long>): Set<Long> {
        val shownIds = shown.mapTo(HashSet()) { it.id }
        val numbers = all
            .filter { it.isRecognizedNumber && it.id in downloaded && it.id !in shownIds }
            .mapTo(HashSet()) { it.chapterNumber }
        return shown.filter { it.isRecognizedNumber && it.chapterNumber in numbers }.mapTo(HashSet()) { it.id }
    }
}
