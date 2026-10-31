package com.shinsaikou.domain.download

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Upstream's two automatic downloads (phase 3 unit AD): new chapters after an update, and download
 * ahead while reading. The library update, the manga screen's manual refresh, the sync pull and the
 * reader report here, and none of `:library-update`, `:reader` or `:sync` may depend on
 * `:downloads`, which owns the queue and knows what is on disk. So the seam is this one interface,
 * bound once by `:downloads` with `@ContributesBinding`, the way [DownloadedChapters] is. It is not a
 * multibinding set: a set may be empty and still satisfy the graph (R26).
 *
 * `:downloads` applies upstream's rules and enqueues through its queue, which skips a chapter already
 * downloaded or queued. Every call reads the database, so call it off the main thread; none waits on
 * the network.
 */
interface AutoDownload {

    /**
     * Chapters new to [manga] that a library update or the manga screen's manual refresh inserted.
     * Upstream's `FilterChaptersForDownload` decides which are downloaded: `downloadNewChapters` on, a
     * favourite in an allowed category, and with `downloadNewUnreadChaptersOnly` none whose number a
     * read chapter already has. Those are queued, and the queue is started only when [autoStart]
     * asks. Returns how many were handed to the queue.
     */
    suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Int

    /**
     * Chapter rows a sync pull inserted, by id, reported once after the pull's transactions
     * committed. A row already read (on the device that pushed it) is left out; each entry's other
     * rows go through the rule [queueNewChapters] applies, without starting the queue, and the queue
     * is started once at the end when anything was queued. Returns how many were queued.
     */
    suspend fun queuePulledChapters(chapterIds: List<Long>): Int

    /** Starts the queue: the one start at the end of an update that queued new chapters. */
    suspend fun startQueue()

    /**
     * The reader shows page [pageIndex] (from 0) of [pageCount] of a chapter of [mangaId], with
     * [nextChapterId] the chapter the reader goes to next. Download ahead: past a quarter of the
     * chapter, with `autoDownloadWhileReading` above 0, a next chapter and the entry in the library,
     * the unread chapters from the next one on are queued, that many, leaving out those already
     * queued or downloaded, and the queue is started only when it was empty. Whether the chapter is
     * read online or from its download does not matter, nor whether the next one is downloaded.
     *
     * A named divergence from upstream's `ReaderViewModel.downloadNextChapters`, which goes on only when
     * the current chapter is read from its download and the next one is downloaded, "to avoid jank":
     * it keeps a queue from competing with the pages being read online. Here the owner opted in with
     * the setting, the queue runs as a download started by hand while reading does (upstream allows
     * that), and Only download over Wi-Fi still gates it through the scheduler's constraint. The
     * library gate is Saikou's own (upstream has none): an entry read online from a source's listing
     * starts no downloads that would then sit under Downloads not in library. Returns how many
     * were handed to the queue.
     */
    suspend fun downloadAhead(
        mangaId: Long,
        pageIndex: Int,
        pageCount: Int,
        nextChapterId: Long?,
    ): Int
}
