package com.shinsaikou.reader.pipeline

import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.flow.Flow

/**
 * Produces the pages of one chapter and puts their bytes where a viewer can read them.
 * Contract 07 section 4.1.
 *
 * One loader belongs to one [eu.kanade.tachiyomi.ui.reader.model.ReaderChapter] and is recycled
 * when that chapter leaves the window.
 */
interface PageLoader {

    /** True for directory, archive, epub and downloaded chapters: pages are Ready at once. */
    val isLocal: Boolean

    val isRecycled: Boolean

    /** Called once per chapter. Pages carry their own 0-based index; sources are not trusted. */
    suspend fun getPages(): List<ReaderPage>

    /**
     * Requests [page] plus the preload window. Suspends until cancelled (the holder detaches or
     * rebinds); progress is observed through `page.statusFlow`, never through the return.
     */
    suspend fun loadPage(page: ReaderPage)

    /** Re-queues an errored page at retry priority. */
    fun retryPage(page: ReaderPage)

    /** Emits once per tall page after its parts are in the cache. */
    val splits: Flow<PageSplit>

    /** Cancels work, drops the queue, writes the page list back to the cache. Idempotent. */
    fun recycle()
}

/** The parts of one tall page, in band order. [parts] holds bands 1 upward; band 0 is [page]. */
data class PageSplit(val page: ReaderPage, val parts: List<InsertPage>)
