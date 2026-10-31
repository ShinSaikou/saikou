package com.shinsaikou.ui.chapter

import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.downloads.DownloadQueue
import kotlinx.coroutines.flow.first
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * One chapter's download state, as a chapter row draws it: the entry screen's rows and the Updates
 * rows read it the same way, from the downloaded set and the queue's rows.
 */
sealed interface ChapterDownload {
    data object None : ChapterDownload
    data object Queued : ChapterDownload

    /** [total] is zero until the engine has the page list. */
    data class Downloading(val pages: Long, val total: Long) : ChapterDownload
    data object Downloaded : ChapterDownload
    data object Failed : ChapterDownload
}

/**
 * What a chapter's state is from the two things a screen collects. A downloaded chapter is
 * downloaded whatever the queue says. A DONE row is never left in the queue (the engine deletes it
 * in the commit that records the chapter), so one is drawn as nothing rather than as a download.
 * The Local source is the caller's to decide: its chapters are on disk by definition.
 */
fun chapterDownloadOf(
    chapterId: Long,
    downloaded: Set<Long>,
    queued: Map<Long, DownloadQueueEntry>,
): ChapterDownload {
    if (chapterId in downloaded) return ChapterDownload.Downloaded
    val row = queued[chapterId] ?: return ChapterDownload.None
    return when (row.state) {
        DownloadState.QUEUED, DownloadState.PAUSED -> ChapterDownload.Queued
        DownloadState.DOWNLOADING -> ChapterDownload.Downloading(row.downloadedPages, row.totalPages)
        DownloadState.ERROR -> ChapterDownload.Failed
        DownloadState.DONE -> ChapterDownload.None
    }
}

/** Upstream's `ChapterDownloadAction`: what a tap on the row's download control asks for. */
enum class ChapterDownloadAction {
    START,
    START_NOW,
    CANCEL,
    DELETE,
}

/** What one tap or long press on the download control does: an action at once, or a menu of them. */
sealed interface ChapterDownloadTap {
    data class Run(val action: ChapterDownloadAction) : ChapterDownloadTap
    data class Menu(val actions: List<ChapterDownloadAction>) : ChapterDownloadTap
}

/**
 * Upstream's `ChapterDownloadIndicator` taps, per state. Not downloaded: a tap downloads, a long press
 * starts it now. Queued or downloading: a tap opens Start downloading now and Cancel, a long press
 * cancels. Downloaded: either opens Delete. Failed: either retries, which queues it again.
 */
fun ChapterDownload.tap(longPress: Boolean): ChapterDownloadTap = when (this) {
    ChapterDownload.None -> ChapterDownloadTap.Run(
        if (longPress) ChapterDownloadAction.START_NOW else ChapterDownloadAction.START,
    )
    ChapterDownload.Queued, is ChapterDownload.Downloading -> if (longPress) {
        ChapterDownloadTap.Run(ChapterDownloadAction.CANCEL)
    } else {
        ChapterDownloadTap.Menu(listOf(ChapterDownloadAction.START_NOW, ChapterDownloadAction.CANCEL))
    }
    ChapterDownload.Downloaded -> ChapterDownloadTap.Menu(listOf(ChapterDownloadAction.DELETE))
    ChapterDownload.Failed -> ChapterDownloadTap.Run(ChapterDownloadAction.START)
}

/**
 * Upstream's start now (`DownloadManager.startDownloadNow`): the chapter queued, moved to the front of
 * the queue, and the queue started. A chapter that failed is queued again in place first, as
 * [DownloadQueue.enqueue] does for any row not queued or downloading. The entry screen's swipe and
 * both screens' download controls start a chapter now through this one path.
 */
suspend fun DownloadQueue.startDownloadNow(manga: Manga, chapter: Chapter) {
    enqueue(manga, listOf(chapter), autoStart = false)
    val order = subscribe().first().map { it.chapterId }
    if (chapter.id in order) reorder(listOf(chapter.id) + (order - chapter.id))
    start()
}
