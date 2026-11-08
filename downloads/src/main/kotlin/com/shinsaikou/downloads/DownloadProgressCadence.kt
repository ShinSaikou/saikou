package com.shinsaikou.downloads

import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Which of one run's progress reports reach the notification. The engine reports every page, and a
 * notification post per page is more than the platform shows: it sheds updates past a few a second.
 * A report goes out when it is the run's first or a new chapter's first, when the number of
 * chapters running at once or the chapters left changed (several sources at once, the Experimental
 * switch, where another source's chapter can end while this one runs), or when [INTERVAL] has passed
 * since the last one sent. One chapter at a time, the chapters left change only with the chapter. A chapter's last pages may go unshown; the next
 * chapter's first report, or the end of the run, replaces them anyway.
 *
 * One per run. Not thread safe: the engine reports from one coroutine at a time, and with several
 * chapters running it sends their reports through one synchronized board.
 */
internal class DownloadProgressCadence(private val timeSource: TimeSource) {

    private var lastChapterId: Long? = null
    private var lastRunning = 1
    private var lastLeft: Int? = null
    private var lastSentAt: TimeMark? = null

    fun shouldSend(progress: DownloadEngine.Progress): Boolean {
        val lastAt = lastSentAt
        val send = lastAt == null ||
            progress.chapterId != lastChapterId ||
            progress.chaptersRunning != lastRunning ||
            progress.chaptersLeft != lastLeft ||
            lastAt.elapsedNow() >= INTERVAL
        if (send) {
            lastChapterId = progress.chapterId
            lastRunning = progress.chaptersRunning
            lastLeft = progress.chaptersLeft
            lastSentAt = timeSource.markNow()
        }
        return send
    }

    companion object {
        val INTERVAL = 1.seconds
    }
}
