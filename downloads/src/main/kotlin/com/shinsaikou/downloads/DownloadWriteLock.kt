package com.shinsaikou.downloads

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One chapter written under [DownloadRoot.root] at a time. [DownloadEngine] holds it for each
 * chapter it downloads and `AdoptionCopier` for each chapter it copies out of upstream's tree.
 *
 * The two run in separate workers and can reach one chapter at once. They name its folder alike, so
 * without this both would write into one `_tmp` folder; and when the names differ, whichever
 * committed second would record its own folder over the other's manifest row and leave the other's
 * files behind with no row. Held across a whole chapter, each side's first look at the queue and the
 * manifest stays true for the other side until it commits. A chapter queued meanwhile is still
 * caught by the queue re-read in each commit's transaction.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadWriteLock {

    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
