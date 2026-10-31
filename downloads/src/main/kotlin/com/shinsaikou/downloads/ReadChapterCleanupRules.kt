package com.shinsaikou.downloads

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.SourceIds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * [ReadChapterCleanup] over the manifest and the queue, with upstream's rules as its code has them
 * (phase 3 unit DR): `ReaderViewModel.deleteChapterIfNeeded` picks the chapter after reading,
 * `DownloadManager.enqueueChaptersToDelete` and `deletePendingChapters` stage it and carry it out when
 * the reader closes, and `SetReadStatus` deletes what a manual mark read wrote. Every path runs
 * [removable], upstream's `getChaptersToDelete`, and deletes through [DownloadManifest.delete] after
 * taking the chapters out of the queue, as upstream's `deleteChapters` does.
 *
 * The guard is here and not inside [DownloadManifest.delete], which the owner's own confirmed delete
 * and the orphan cleanup also call; upstream guards its manual deletes too, and Saikou leaves those
 * unguarded, so a bookmarked download can still be deleted by hand.
 *
 * Two places are stricter than upstream, and both can only keep files. A staged chapter is read again
 * when the reader closes and deleted only if it is still read and still passes the guard, where
 * upstream deletes what it staged, guard applied again. And an entry in an excluded category keeps
 * every chapter on both paths, where upstream's guard drops only chapters whose row says read, which on
 * its mark read path are the rows as they were before the write, all unread, so its exclusion never
 * applied there.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadChapterCleanupRules(
    private val chapterRepository: ChapterRepository,
    private val getManga: GetManga,
    private val getCategories: GetCategories,
    private val manifest: DownloadManifest,
    private val queue: DownloadQueue,
    private val downloadPreferences: DownloadPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val store: PendingChapterDeleteStore,
    private val dispatchers: Dispatchers,
    private val appScope: CoroutineScope,
) : ReadChapterCleanup {

    /**
     * Upstream's `chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)`, counted on the
     * list the reader handed over, which starts at the finished chapter. Upstream returns on -1 alone;
     * every negative value is off here, since a restored -2 would otherwise pick a chapter after the
     * one finished. Whether the chosen chapter is read is the database's answer, which the reader
     * wrote before calling, where upstream asks its in-memory chapter.
     *
     * With "mark duplicate chapters read: existing" on, the chosen chapter's duplicates (another
     * chapter of the entry with the same recognised number) are staged with it, as upstream does since
     * `36ec043a5`: the reader marks them read with the chapter it finished, and they kept their files.
     * Each passes the same guard on its own, so a bookmarked chosen chapter does not keep its
     * duplicates, and [readerClosed] deletes only those that are read by then, so one the reader did
     * not mark stays.
     */
    override suspend fun chapterFinished(sessionChapterIds: List<Long>) {
        val slot = downloadPreferences.removeAfterReadSlots.get()
        if (slot < 0) return
        val chapterId = sessionChapterIds.getOrNull(slot) ?: return
        val chapter = chapterRepository.getChapterById(chapterId) ?: return
        if (!chapter.read) return
        val manga = getManga.await(chapter.mangaId) ?: return
        val staged = removable(manga, listOf(chapter) + duplicatesOf(chapter))
        if (staged.isEmpty()) return
        withContext(dispatchers.io) { staged.forEach { store.add(manga.id, it.id) } }
    }

    private suspend fun duplicatesOf(chapter: Chapter): List<Chapter> {
        val marked = LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING in
            libraryPreferences.markDuplicateReadChapterAsRead.get()
        if (!marked || !chapter.isRecognizedNumber) return emptyList()
        return chapterRepository.getChapterByMangaId(chapter.mangaId).filter {
            it.id != chapter.id && it.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber
        }
    }

    override suspend fun readerClosed() {
        val staged = try {
            withContext(dispatchers.io) { store.takeAll() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not read the chapters staged for deletion" }
            return
        }
        if (staged.isEmpty()) return
        deleteRead(staged.keys.toList())
    }

    override fun markedRead(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        appScope.launch(dispatchers.io) { deleteRead(chapterIds) }
    }

    /**
     * Reads [chapterIds] again and deletes, entry by entry, those still read that pass [removable].
     * A failure is logged per entry and the next goes on.
     */
    private suspend fun deleteRead(chapterIds: List<Long>) {
        val chapters = try {
            chapterRepository.getChaptersByIds(chapterIds).filter { it.read }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not read ${chapterIds.size} chapters to delete" }
            return
        }
        chapters.groupBy { it.mangaId }.forEach { (mangaId, mangaChapters) ->
            try {
                val manga = getManga.await(mangaId) ?: return@forEach
                val ids = removable(manga, mangaChapters).map { it.id }
                if (ids.isEmpty()) return@forEach
                val queued = queue.subscribe().first().mapTo(HashSet()) { it.chapterId }
                val inQueue = ids.filter { it in queued }
                if (inQueue.isNotEmpty()) queue.remove(inQueue)
                manifest.delete(ids)
                logcat(LogPriority.INFO) { "Deleted ${ids.size} read chapters of entry $mangaId" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not delete the read chapters of entry $mangaId" }
            }
        }
    }

    /**
     * Upstream's `getChaptersToDelete`: an entry with any category in `remove_exclude_categories` keeps
     * every chapter, an entry in no category counting as the default category 0; a bookmarked
     * chapter is kept unless `pref_remove_bookmarked` is on. A Local source chapter is the owner's
     * only copy and is never deleted; it has no manifest row either, so this is a second lock on a
     * door that is already shut.
     */
    private suspend fun removable(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        if (manga.source == SourceIds.LOCAL) return emptyList()
        val excluded = downloadPreferences.removeExcludeCategories.get().mapNotNullTo(HashSet(), String::toLongOrNull)
        if (excluded.isNotEmpty()) {
            val categories = getCategories.await(manga.id).map { it.id }.ifEmpty { listOf(DEFAULT_CATEGORY_ID) }
            if (categories.any { it in excluded }) return emptyList()
        }
        return if (downloadPreferences.removeBookmarkedChapters.get()) chapters else chapters.filterNot { it.bookmark }
    }

    private companion object {
        /** The system category, which upstream's guard names for an entry in no category. */
        const val DEFAULT_CATEGORY_ID = 0L
    }
}
