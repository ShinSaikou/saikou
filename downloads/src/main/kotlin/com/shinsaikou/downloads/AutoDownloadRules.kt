package com.shinsaikou.downloads

import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.source.SourceIds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import kotlinx.coroutines.flow.first
import tachiyomi.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * [AutoDownload] over the queue, with upstream's two rules (phase 3 unit AD):
 * [FilterChaptersForDownload] for new chapters as its code has it, and
 * `ReaderViewModel.downloadNextChapters` for download ahead, without its downloaded-only guard and
 * with a library gate (wave 2 unit download-ahead-online). Chapters go into the queue oldest
 * source position first, the order upstream's `Downloader.queueChapters` gives them, and
 * [DownloadQueue.enqueue] skips what is already downloaded, so a chapter reported twice is queued
 * once.
 *
 * In a merged entry a new chapter is queued only while it is the copy the group shows, and a read
 * ahead follows the merged list across sources, each chapter queued under the entry it belongs to.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AutoDownloadRules(
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val getNextChapters: GetNextChapters,
    private val getScanlatorPriority: GetScanlatorPriority,
    private val getManga: GetManga,
    private val chapterRepository: ChapterRepository,
    private val manifest: DownloadManifest,
    private val queue: DownloadQueue,
    private val downloadPreferences: DownloadPreferences,
    private val readerPreferences: ReaderPreferences,
    private val getMergedGroup: GetMergedGroup,
    private val getMergedChapters: GetMergedChapters,
) : AutoDownload {

    /** A Local-source entry is on disk already; the queue refuses it, so nothing counts as queued. */
    override suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Int {
        if (manga.source == SourceIds.LOCAL) return 0
        val chosen = filterChaptersForDownload.await(manga, visibleCopies(manga, chapters))
        if (chosen.isEmpty()) return 0
        queue.enqueue(manga, chosen.sortedByDescending { it.sourceOrder }, autoStart)
        return chosen.size
    }

    /** [chapters] less the copies [manga]'s merged group hides; all of them for an entry on its own. */
    private suspend fun visibleCopies(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        val group = getMergedGroup.await(manga.id) ?: return chapters
        val visible = getMergedChapters.await(group).mapTo(HashSet()) { it.id }
        return chapters.filter { it.id in visible }
    }

    /**
     * A row whose entry is gone is dropped with it, and so is a row that is read as it stands after
     * the pull. Upstream's rule never meets a new chapter someone already read, because on one device a
     * chapter is new before it is read; a pulled row can have been read on the device that pushed it,
     * and with `downloadNewUnreadChaptersOnly` off (its default) the rule would queue it.
     */
    override suspend fun queuePulledChapters(chapterIds: List<Long>): Int {
        if (chapterIds.isEmpty()) return 0
        var queued = 0
        val unread = chapterRepository.getChaptersByIds(chapterIds).filterNot { it.read }
        unread.groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
            val manga = getManga.await(mangaId) ?: return@forEach
            queued += queueNewChapters(manga, chapters, autoStart = false)
        }
        if (queued > 0) queue.start()
        return queued
    }

    override suspend fun startQueue() {
        queue.start()
    }

    /**
     * Upstream's `onPageSelected` asks for a page whose number, counted from 1, is past a quarter of
     * the chapter, and `downloadNextChapters` goes on with an amount set and a next chapter. Upstream
     * also wants the chapter read from its download and the next one downloaded; Saikou does not
     * (the divergence [AutoDownload.downloadAhead] names), and wants the entry in the library
     * instead. Then the unread chapters from the next one, that one included, one per number under
     * `skipDupe`, that many; one already downloaded, the next one included, counts towards the
     * amount and is skipped, as in upstream. The copy kept for each number is the one the reader's
     * session keeps, the title's scanlator ranking included (phase 4 unit SC). A Local-source entry
     * is on disk already, so it queues nothing, as in [queueNewChapters].
     *
     * The reader reports every page, so this runs on every page past the quarter. As upstream's
     * `Downloader.queueChapters` does, a chapter already queued (failed ones included) is left as
     * it is, nothing is enqueued when nothing is new, and the queue is started only when it was
     * empty: a queue the owner paused stays paused, and a failed download is not retried at each
     * page turn, which [DownloadQueue.enqueue] followed by [DownloadQueue.start] would do.
     */
    override suspend fun downloadAhead(
        mangaId: Long,
        pageIndex: Int,
        pageCount: Int,
        nextChapterId: Long?,
    ): Int {
        if (pageCount <= 0 || (pageIndex + 1).toDouble() / pageCount <= DOWNLOAD_RANGE) return 0
        val amount = downloadPreferences.autoDownloadWhileReading.get()
        if (amount <= 0 || nextChapterId == null) return 0
        val manga = getManga.await(mangaId) ?: return 0
        if (!manga.favorite || manga.source == SourceIds.LOCAL) return 0
        val next = chapterRepository.getChapterById(nextChapterId) ?: return 0

        val chapters = getNextChapters.await(manga.id, next.id).run {
            if (readerPreferences.skipDupe.get()) removeDuplicates(next, getScanlatorPriority.await(manga.id)) else this
        }.take(amount)
        val queued = queue.subscribe().first().mapTo(HashSet()) { it.chapterId }
        val fresh = chapters.filter { it.id !in queued && !manifest.isDownloaded(it.id) }
        if (fresh.isEmpty()) return 0
        return fresh.groupBy { it.mangaId }.entries.sumOf { (ownerId, own) ->
            val owner = (if (ownerId == manga.id) manga else getManga.await(ownerId))
                ?.takeUnless { it.source == SourceIds.LOCAL }
                ?: return@sumOf 0
            queue.enqueue(owner, own.sortedByDescending { it.sourceOrder }, autoStart = queued.isEmpty())
            own.size
        }
    }

    private companion object {
        /** Upstream's `inDownloadRange`: the page must be past this share of the chapter. */
        const val DOWNLOAD_RANGE = 0.25
    }
}
