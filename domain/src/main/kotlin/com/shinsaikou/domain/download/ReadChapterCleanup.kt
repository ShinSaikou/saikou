package com.shinsaikou.domain.download

/**
 * Upstream's "Delete chapters" rules (phase 3 unit DR): after reading, and after a manual mark as
 * read. The reader and `SetReadStatus` report here, and neither `:reader` nor `:domain` may depend
 * on `:downloads`, which owns the files, so the seam is this one interface, bound once by
 * `:downloads` with `@ContributesBinding` beside [AutoDownload] (R26: not a multibinding set, which
 * may be empty and still satisfy the graph).
 *
 * `:downloads` reads the four remove-after keys and applies upstream's guard,
 * `DownloadManager.getChaptersToDelete`: an entry in an excluded category keeps every chapter (an
 * entry in no category counts as the default category, 0), a bookmarked chapter is kept unless
 * bookmarked chapters may be deleted, and a Local source chapter is never deleted. A chapter still
 * in the download queue leaves it before its files go.
 *
 * Only a local finish or a local mark reports here. A sync pull and a backup restore write the read
 * flag straight into the row and never reach this interface (phase3-start.md Q20).
 */
interface ReadChapterCleanup {

    /**
     * The reader committed a chapter as finished. [sessionChapterIds] are the reader session's own
     * chapters from the finished one backwards: the finished chapter first, then up to
     * [MAX_SLOT] chapters before it in the session's order. The chapter at the
     * `remove_after_read_slots` position (0 is the chapter just finished) is staged for deletion if
     * it is read and passes the guard, with its same-number duplicates when "mark duplicate chapters
     * read: existing" is on; nothing is deleted until [readerClosed]. The staged entry is on disk
     * before this returns. Reads the database; call it off the main thread.
     */
    suspend fun chapterFinished(sessionChapterIds: List<Long>)

    /**
     * The reader is closing: every staged chapter, including any a killed process left behind, is
     * read again and deleted only if it is still read and still passes the guard. Call it off the
     * main thread; a failure is logged and never thrown.
     */
    suspend fun readerClosed()

    /**
     * `SetReadStatus` marked [chapterIds] read by hand with `pref_remove_after_marked_as_read_key`
     * on, after its write committed. Returns at once: the guard and the delete run on the
     * application's scope, so a slow or failing delete never holds or fails the caller.
     */
    fun markedRead(chapterIds: List<Long>)

    companion object {
        /** Upstream's last choice, "Fifth to last read chapter": the finished chapter and four before it. */
        const val MAX_SLOT = 4
    }
}
