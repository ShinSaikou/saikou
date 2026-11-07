package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.model.MergedGroup
import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * The Updates list with its merged titles folded: each row stands under the main entry, and each
 * number has the one row the entry's list would show ([GetMergedChapters.visible], Show every copy
 * included). A row keeps its own chapter, entry and source, so a download, a delete or a mark acts
 * on that copy.
 *
 * The pass reads every copy of a number, listed or not: a copy fetched months ago still beats a newer
 * one from a lower source, and that newer one is no new chapter of the title. The list's filters
 * run first, as on the entry screen, so a number whose winning copy fails one keeps the copy that
 * passes. With no group there is nothing to do and nothing is read; otherwise the cost is one read
 * of the listed chapters, then two reads for each title with a row, whatever the library's size.
 */
@Inject
class FoldMergedUpdates(
    private val chapterRepository: ChapterRepository,
    private val getManga: GetManga,
    private val getMergedChapters: GetMergedChapters,
) {

    /**
     * [rows] with [groups] folded, in the order given. [memberShown] and [copyShown] are the filters
     * behind [rows], put to a copy that is not among them; [hideExcludedScanlators] is the one the
     * chapter read applies itself.
     */
    suspend fun await(
        rows: List<UpdatesWithRelations>,
        groups: List<MergedGroup>,
        hideExcludedScanlators: Boolean,
        memberShown: suspend (Long) -> Boolean,
        copyShown: (Chapter) -> Boolean,
    ): List<UpdatesWithRelations> {
        if (groups.isEmpty()) return rows
        val groupOf = HashMap<Long, MergedGroup>()
        groups.forEach { group -> group.members.forEach { groupOf[it.mangaId] = group } }
        val merged = rows.filter { it.mangaId in groupOf }
        if (merged.isEmpty()) return rows

        val chapters = chapterRepository.getChaptersByIds(merged.map { it.chapterId }).associateBy { it.id }
        val mains = HashMap<Long, Manga>()
        val hidden = HashSet<Long>()
        merged.groupBy { groupOf.getValue(it.mangaId) }.forEach { (group, own) ->
            // A main entry that is not here yet is a tick behind the group: its rows wait, unfolded.
            mains[group.id] = getManga.await(group.mainMangaId) ?: return@forEach
            if (group.showAll) return@forEach
            val listed = own.mapNotNull { chapters[it.chapterId] }.filter { it.isRecognizedNumber }
            if (listed.isEmpty()) return@forEach
            val copies = chapterRepository
                .getChaptersByNumbers(
                    mangaIds = group.members.map { it.mangaId }.filter { memberShown(it) },
                    numbers = listed.map { it.chapterNumber }.distinct(),
                    applyScanlatorFilter = hideExcludedScanlators,
                )
                .filter(copyShown)
            val shown = getMergedChapters.visible(copies, group).mapTo(HashSet()) { it.id }
            listed.filter { it.id !in shown }.mapTo(hidden) { it.id }
        }
        return rows.filterNot { it.chapterId in hidden }.map { row ->
            val main = groupOf[row.mangaId]?.let { mains[it.id] } ?: return@map row
            row.copy(
                mergedMainId = main.id,
                mangaTitle = main.title,
                displayTitle = main.displayTitle,
                coverData = main.asMangaCover(),
            )
        }
    }
}
