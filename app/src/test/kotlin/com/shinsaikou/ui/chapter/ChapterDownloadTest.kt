package com.shinsaikou.ui.chapter

import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA

/**
 * The chapter row's download control (plan 2.6 part D, unit ID3): upstream's `ChapterDownloadIndicator`
 * taps per state, and the state each screen draws from its downloaded set and queue rows. The
 * composable only hands [tap]'s answer on, so these are the control's behaviour.
 */
class ChapterDownloadTest {

    @Test
    fun `a chapter not downloaded downloads on a tap and starts now on a long press`() {
        ChapterDownload.None.tap(longPress = false) shouldBe ChapterDownloadTap.Run(ChapterDownloadAction.START)
        ChapterDownload.None.tap(longPress = true) shouldBe ChapterDownloadTap.Run(ChapterDownloadAction.START_NOW)
    }

    @Test
    fun `a queued or downloading chapter opens start now and cancel on a tap and cancels on a long press`() {
        val menu = ChapterDownloadTap.Menu(listOf(ChapterDownloadAction.START_NOW, ChapterDownloadAction.CANCEL))
        for (state in listOf(ChapterDownload.Queued, ChapterDownload.Downloading(pages = 2, total = 9))) {
            state.tap(longPress = false) shouldBe menu
            state.tap(longPress = true) shouldBe ChapterDownloadTap.Run(ChapterDownloadAction.CANCEL)
        }
    }

    @Test
    fun `a downloaded chapter opens delete on a tap and on a long press`() {
        val menu = ChapterDownloadTap.Menu(listOf(ChapterDownloadAction.DELETE))
        ChapterDownload.Downloaded.tap(longPress = false) shouldBe menu
        ChapterDownload.Downloaded.tap(longPress = true) shouldBe menu
    }

    @Test
    fun `a failed chapter retries on a tap and on a long press`() {
        ChapterDownload.Failed.tap(longPress = false) shouldBe ChapterDownloadTap.Run(ChapterDownloadAction.START)
        ChapterDownload.Failed.tap(longPress = true) shouldBe ChapterDownloadTap.Run(ChapterDownloadAction.START)
    }

    @Test
    fun `the state is downloaded whatever the queue says, else the queue row's, else none`() {
        val queued = listOf(
            row(100L, DownloadState.QUEUED),
            row(101L, DownloadState.PAUSED),
            row(102L, DownloadState.DOWNLOADING, pages = 3, total = 12),
            row(103L, DownloadState.ERROR),
            row(104L, DownloadState.DONE),
            row(105L, DownloadState.QUEUED),
        ).associateBy { it.chapterId }
        val downloaded = setOf(105L)

        (100L..106L).map { chapterDownloadOf(it, downloaded, queued) } shouldBe listOf(
            ChapterDownload.Queued,
            ChapterDownload.Queued,
            ChapterDownload.Downloading(pages = 3, total = 12),
            ChapterDownload.Failed,
            ChapterDownload.None,
            ChapterDownload.Downloaded,
            ChapterDownload.None,
        )
    }

    @Test
    fun `a read row is drawn at upstream's disabled alpha and an unread one at full`() {
        chapterTextAlpha(read = true) shouldBe DISABLED_ALPHA
        chapterTextAlpha(read = false) shouldBe 1f
    }

    private fun row(chapterId: Long, state: DownloadState, pages: Long = 0, total: Long = 0) = DownloadQueueEntry(
        id = chapterId,
        chapterId = chapterId,
        mangaId = 1L,
        state = state,
        queueOrder = chapterId,
        totalPages = total,
        downloadedPages = pages,
        errorMessage = null,
        dateAdded = 0L,
        dateStarted = null,
        dateCompleted = null,
    )
}
