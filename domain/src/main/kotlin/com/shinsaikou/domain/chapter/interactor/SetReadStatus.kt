package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.preferences.DownloadPreferences
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Marks chapters read or unread. Marking read clears page progress; rows already in the target
 * state are skipped so the change log does not gain a no-op entry.
 *
 * [Result.Success] carries the chapters this call actually wrote, never the argument: a tracker
 * push after "mark previous as read" is the highest of those, not the chapter the owner tapped
 * (phase 3 unit T1, challenge 5 T1 item 1).
 *
 * With `pref_remove_after_marked_as_read_key` on, the chapters a mark read wrote are handed to
 * [ReadChapterCleanup] after the write commits, as upstream's `SetReadStatus` hands them to
 * `DeleteDownload` (phase 3 unit DR). That hand-off returns at once and cannot change the result:
 * the delete runs on the application's scope, so a slow or failing one neither holds the caller nor
 * keeps the tracker push that follows it from reading the rows written. Marking unread deletes
 * nothing.
 *
 * In a merged entry, read state is written to every copy (`design/plans/merged-entries.md` section
 * 4): a mark, read or unread, expands to the other live copies of the same recognised numbers,
 * which count as written. [GetMergedGroup] returns nothing while the Experimental switch is
 * off, so nothing expands.
 */
@Inject
class SetReadStatus(
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val downloadPreferences: DownloadPreferences,
    private val readChapterCleanup: ReadChapterCleanup,
    private val getMergedGroup: GetMergedGroup,
) {

    private fun toUpdate(chapter: Chapter, read: Boolean) = ChapterUpdate(
        id = chapter.id,
        read = read,
        lastPageRead = if (read) 0L else null,
    )

    /**
     * [removeDownloads] false leaves the files of the chapters marked read alone whatever the
     * setting says. The reader passes it for the duplicates of the chapter it just finished, whose
     * files must not go at once: a duplicate can be the reader's next chapter, open or preloading
     * from its download. Delete after reading stages them with that chapter and deletes them when
     * the reader closes.
     */
    suspend fun await(
        read: Boolean,
        vararg chapters: Chapter,
        removeDownloads: Boolean = true,
    ): Result = withNonCancellableContext {
        val chaptersToUpdate = try {
            (chapters.toList() + copiesOf(chapters.asList())).filter {
                if (read) !it.read else it.read || it.lastPageRead > 0
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }
        if (chaptersToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoChapters
        }

        try {
            chapterRepository.updateAll(chaptersToUpdate.map { toUpdate(it, read) })
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        if (read && removeDownloads) removeDownloadsOf(chaptersToUpdate)

        Result.Success(chaptersToUpdate)
    }

    /**
     * The other live copies of [chapters]' recognised numbers across each live group they belong to,
     * one read of each member's list however many chapters were marked.
     */
    private suspend fun copiesOf(chapters: List<Chapter>): List<Chapter> {
        val marked = chapters.groupBy { it.mangaId }
        val named = chapters.mapTo(HashSet()) { it.id }
        return marked.keys.mapNotNull { getMergedGroup.await(it) }.distinctBy { it.id }.flatMap { group ->
            val numbers = group.members.flatMap { marked[it.mangaId].orEmpty() }
                .filter { it.isRecognizedNumber }
                .mapTo(HashSet()) { it.chapterNumber }
            if (numbers.isEmpty()) return@flatMap emptyList()
            group.members.flatMap { chapterRepository.getChapterByMangaId(it.mangaId) }
                .filter { it.chapterNumber in numbers && it.id !in named }
        }
    }

    /** Upstream's `SetReadStatus` lines 49-57, fire and forget; a throw is logged, never returned. */
    private fun removeDownloadsOf(chapters: List<Chapter>) {
        try {
            if (!downloadPreferences.removeAfterMarkedAsRead.get()) return
            readChapterCleanup.markedRead(chapters.map { it.id })
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not hand ${chapters.size} chapters marked read to the download cleanup" }
        }
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = await(
        read = read,
        chapters = chapterRepository.getChapterByMangaId(mangaId).toTypedArray(),
    )

    /** Marks every chapter that sorts before [chapter] in the manga's own order. */
    suspend fun awaitAllBefore(chapter: Chapter, read: Boolean): Result = withNonCancellableContext {
        val manga = mangaRepository.getMangaById(chapter.mangaId)
        val sort = getChapterSort(manga, sortDescending = false)
        val sorted = chapterRepository.getChapterByMangaId(manga.id).sortedWith(sort)
        val index = sorted.indexOfFirst { it.id == chapter.id }
        if (index <= 0) {
            return@withNonCancellableContext Result.NoChapters
        }
        await(read = read, chapters = sorted.subList(0, index).toTypedArray())
    }

    sealed interface Result {
        /** [chapters] are the rows written, as they were before the write. */
        data class Success(val chapters: List<Chapter>) : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
