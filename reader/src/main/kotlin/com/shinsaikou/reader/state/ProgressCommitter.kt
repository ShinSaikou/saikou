package com.shinsaikou.reader.state

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.track.TrackProgress
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import java.util.Date

/**
 * When the database learns where the reader is. Contract 07 section 3.7.
 *
 * Upstream writes one row per page turn. This coalesces, and keeps the durability contract: progress
 * survives a sudden kill within [DEBOUNCE_MS] of the last page turn, and always survives pause, a
 * chapter change and finish.
 *
 * Incognito is not this class's business: the reducer never emits a commit while incognito
 * (section 3.7 rule 5), so an incognito chapter never reaches [TrackProgress] either, nor
 * [AutoDownload]'s download ahead, nor [ReadChapterCleanup]'s delete after reading.
 *
 * Writes run one at a time, in the order they were asked for, under [writeLock], taken on the
 * caller's thread before the write moves to the IO dispatcher. That order is what lets
 * [deletePendingChapters] run after the flush a finish asked for first: the flush's write is what
 * stages the chapter just finished.
 */
class ProgressCommitter @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val setReadStatus: SetReadStatus,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val libraryPreferences: LibraryPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackProgress: TrackProgress,
    private val autoDownload: AutoDownload,
    private val readChapterCleanup: ReadChapterCleanup,
    private val dispatchers: Dispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): ProgressCommitter
    }

    private var pending: Pending? = null
    private var pendingJob: Job? = null
    private var timerStart: Long? = null
    private val writeLock = Mutex()

    /**
     * Advances the in-memory page index at once, then schedules the write. A newer index replaces a
     * pending one, so a burst of page turns costs one row. [immediate] writes now.
     *
     * Every page is also reported to [AutoDownload] for download ahead, with [nextChapterId] the
     * chapter the reader moves to after this one, or null at the end of the list.
     *
     * [readBack] is the session's chapters from this one backwards, which a finished chapter hands
     * to [ReadChapterCleanup] once its write is done ([ReaderSession.readBack]).
     */
    fun onPageSelected(
        chapter: ReaderChapter,
        pageIndex: Int,
        chapterFinished: Boolean,
        immediate: Boolean,
        nextChapterId: Long? = null,
        readBack: List<Long> = emptyList(),
    ) {
        chapter.chapter = chapter.chapter.copy(lastPageRead = pageIndex.toLong())
        chapter.requestedPage = pageIndex
        val next = Pending(chapter, pageIndex, chapterFinished, readBack)
        pending = next
        pendingJob?.cancel()
        pendingJob = scope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            write(next)
        }
        reportPage(chapter, pageIndex, nextChapterId)
    }

    /**
     * Upstream's `onPageSelected` calls `downloadNextChapters` on every page; the rule is
     * [AutoDownload]'s (phase 3 unit AD), and what it needs from here is the page, the page count
     * and the next chapter. Which loader the chapter came from is not asked: download ahead runs
     * for a chapter read online too (wave 2 unit download-ahead-online). Its own coroutine on the IO
     * dispatcher, apart from the debounced write: a report reads the database, so it never runs on
     * the main thread, and a failure is logged and touches no progress.
     */
    private fun reportPage(chapter: ReaderChapter, pageIndex: Int, nextChapterId: Long?) {
        val pageCount = chapter.pages?.size ?: return
        val mangaId = chapter.chapter.mangaId
        scope.launch(dispatchers.io) {
            try {
                autoDownload.downloadAhead(mangaId, pageIndex, pageCount, nextChapterId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not download ahead of chapter ${chapter.chapter.id}" }
            }
        }
    }

    /** Writes the pending page index now. Safe with nothing pending. Runs non-cancellable. */
    suspend fun flush(chapter: ReaderChapter?) {
        val next = pending ?: return
        if (chapter != null && next.chapter !== chapter) return
        pendingJob?.cancel()
        pendingJob = null
        write(next)
    }

    /**
     * Drops the pending page index without writing it, for a session that failed (phase 4 unit BF):
     * its entry can have left the database, and its chapters with it. A write already under way
     * finishes; one still waiting on its debounce finds nothing pending and writes nothing.
     */
    fun discard() {
        pending = null
        pendingJob?.cancel()
        pendingJob = null
    }

    fun restartReadTimer(chapter: ReaderChapter) {
        timerStart = clock()
    }

    /**
     * Closes out the History row for [chapter] and resets the timer. A duration of zero is still
     * written: the `last_read` update is what the History screen orders by.
     *
     * A failure is logged (phase 4 unit BF). The row is an insert whose `chapter_id` must name a
     * chapter, and a chapter change launches this apart from the pending page write [discard]
     * drops, so an entry deleted from a second window between the launch and the insert fails the
     * foreign key here; uncaught, it would end the process.
     */
    suspend fun commitHistory(chapter: ReaderChapter) {
        val start = timerStart
        val now = clock()
        timerStart = null
        withContext(dispatchers.io + NonCancellable) {
            try {
                upsertHistory.await(
                    HistoryUpdate(
                        chapterId = chapter.chapter.id,
                        readAt = Date(now),
                        sessionReadDuration = start?.let { now - it } ?: 0L,
                    ),
                )
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not write the history row of chapter ${chapter.chapter.id}" }
            }
        }
    }

    /**
     * Upstream's `deletePendingChapters` from `onActivityFinish`: the reader is closing, so the chapters
     * staged for deletion after reading go now. It waits for every write asked for before it, the
     * finish's own flush included, and runs non-cancellable, so it outlives the view model's scope.
     * A failure is logged.
     */
    suspend fun deletePendingChapters() {
        withContext(NonCancellable) {
            writeLock.withLock {
                withContext(dispatchers.io) {
                    try {
                        readChapterCleanup.readerClosed()
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Could not delete the chapters staged after reading" }
                    }
                }
            }
        }
    }

    private suspend fun write(next: Pending) {
        if (pending !== next) return
        pending = null
        // NonCancellable first and on the caller's dispatcher, so the lock is taken here, in the
        // order the writes were asked for, before anything moves to the IO dispatcher.
        withContext(NonCancellable) {
            writeLock.withLock {
                withContext(dispatchers.io) { writeNow(next) }
            }
        }
    }

    private suspend fun writeNow(next: Pending) {
        val chapter = next.chapter
        if (next.chapterFinished) {
            // The reader never writes read = false; a chapter re-read keeps its flag.
            chapter.chapter = chapter.chapter.copy(read = true)
            updateChapter.await(
                ChapterUpdate(id = chapter.chapter.id, read = true, lastPageRead = next.pageIndex.toLong()),
            )
            recordTrackProgress(chapter)
            stageReadChapter(chapter, next.readBack)
            markDuplicatesRead(chapter)
        } else {
            updateChapter.await(
                ChapterUpdate(id = chapter.chapter.id, lastPageRead = next.pageIndex.toLong()),
            )
        }
    }

    /**
     * Upstream's `updateTrackChapterRead`, as a record rather than a push (phase 3 unit T1): with
     * `autoUpdateTrack` on, [TrackProgress] records the push for each signed-in tracker bound below
     * this chapter and enqueues the worker that sends it. That is a database read and a committed
     * preference write, never the network, so the duplicates below wait on nothing slower. A
     * failure is logged and the progress write stands.
     */
    private suspend fun recordTrackProgress(chapter: ReaderChapter) {
        if (!trackPreferences.autoUpdateTrack.get()) return
        val finished = chapter.chapter
        try {
            trackProgress.record(finished.mangaId, finished.chapterNumber)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not record the tracker push for chapter ${finished.id}" }
        }
    }

    /**
     * Upstream's `deleteChapterIfNeeded` (phase 3 unit DR): [ReadChapterCleanup] reads the slot and
     * stages the chapter at it, deleted when the reader closes. After the progress write and never
     * instead of it: a failure is logged and the chapter stays read.
     */
    private suspend fun stageReadChapter(chapter: ReaderChapter, readBack: List<Long>) {
        if (readBack.isEmpty()) return
        try {
            readChapterCleanup.chapterFinished(readBack)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not stage a chapter for deletion after ${chapter.chapter.id}" }
        }
    }

    /**
     * Upstream's "mark duplicate chapters as read" behaviour, in one `updateAll` through
     * [SetReadStatus], which already skips rows that are in the target state. Not as a manual mark
     * read, whose delete runs at once whatever `pref_remove_after_marked_as_read_key` says: a
     * duplicate can be the chapter the reader shows next. Delete after reading has staged them with
     * the chapter at its slot instead ([ReadChapterCleanup.chapterFinished]), deleted when the reader
     * closes and only if read by then, as upstream does since `36ec043a5`.
     */
    private suspend fun markDuplicatesRead(chapter: ReaderChapter) {
        val enabled = LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING in
            libraryPreferences.markDuplicateReadChapterAsRead.get()
        if (!enabled) return

        val finished = chapter.chapter
        if (!finished.isRecognizedNumber) return
        val duplicates = getChaptersByMangaId.await(finished.mangaId)
            .filter { it.id != finished.id && !it.read && it.isRecognizedNumber && it.chapterNumber == finished.chapterNumber }
        if (duplicates.isEmpty()) return
        setReadStatus.await(read = true, chapters = duplicates.toTypedArray(), removeDownloads = false)
    }

    private data class Pending(
        val chapter: ReaderChapter,
        val pageIndex: Int,
        val chapterFinished: Boolean,
        val readBack: List<Long>,
    )

    companion object {
        const val DEBOUNCE_MS = 750L
    }
}
