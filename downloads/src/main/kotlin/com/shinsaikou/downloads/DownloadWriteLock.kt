package com.shinsaikou.downloads

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
 *
 * [withFolderLock] is the engine's hold while it downloads from several sources at once (the
 * Experimental switch, which amends R65). It still keeps every [withLock] holder out for the whole
 * chapter, as R71 asks, and it keeps out another engine chapter only when the two could share a
 * folder name, so chapters of different folders run side by side.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadWriteLock {

    private val mutex = Mutex()

    /** One permit per [withFolderLock] holder; [withLock] takes them all. */
    private val shared = Semaphore(SHARED_PERMITS)

    private class FolderMutex(val mutex: Mutex = Mutex(), var users: Int = 0)

    /** Guarded by itself; an entry lives while a holder or a waiter uses it. */
    private val folders = HashMap<String, FolderMutex>()

    /**
     * The whole root to itself: no other [withLock] holder and no [withFolderLock] holder runs
     * meanwhile. The semaphore queues in order, so a holder waiting here is not passed by a
     * [withFolderLock] that asks after it.
     */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock {
        var held = 0
        try {
            repeat(SHARED_PERMITS) {
                shared.acquire()
                held++
            }
            block()
        } finally {
            repeat(held) { shared.release() }
        }
    }

    /**
     * A chapter whose folder may be named any of [folderNames]: no [withLock] holder runs meanwhile,
     * and neither does another [withFolderLock] holder that names one of them. The names are compared
     * as given, so the caller folds their case. They are locked in sorted order, so two holders that
     * share several names cannot wait on each other.
     */
    suspend fun <T> withFolderLock(folderNames: Collection<String>, block: suspend () -> T): T =
        shared.withPermit {
            val names = folderNames.distinct().sorted()
            val entries = synchronized(folders) {
                names.map { name -> folders.getOrPut(name, ::FolderMutex).also { it.users++ } }
            }
            try {
                lockAll(entries.map { it.mutex }, block)
            } finally {
                synchronized(folders) {
                    names.forEachIndexed { i, name -> if (--entries[i].users == 0) folders.remove(name) }
                }
            }
        }

    private suspend fun <T> lockAll(mutexes: List<Mutex>, block: suspend () -> T): T =
        if (mutexes.isEmpty()) block() else mutexes.first().withLock { lockAll(mutexes.drop(1), block) }

    companion object {
        /** The most [withFolderLock] holders at once: the top of the engine's sources-at-once range. */
        const val SHARED_PERMITS = DownloadEngine.MAX_PARALLEL_SOURCES
    }
}
