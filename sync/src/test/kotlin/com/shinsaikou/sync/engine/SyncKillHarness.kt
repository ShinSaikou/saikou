package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncStore

/*
 * The killed-run harness's parts (phase 3 unit E2's suite, shared with unit I's per-part resume):
 * a run dies right after a numbered event, as process death would, and the same install is started
 * again.
 */

/** What a run dies of: thrown by [KillEvents] right after the event it was told to kill at. */
internal class Killed : RuntimeException("killed")

/** Counts events and throws [Killed] right after the one numbered [killAfter]; 0 never throws. */
internal class KillEvents(private val killAfter: Int) {
    var count = 0
        private set

    fun happened() {
        count++
        if (count == killAfter) throw Killed()
    }
}

/** [real], with each transaction it commits (an apply, an export's start) counted by [events]. */
internal class CountingStore(private val real: SyncStore, private val events: KillEvents) : SyncStore by real {
    override suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T = real.apply(block).also { events.happened() }

    override suspend fun beginExport(pushedSeq: Long): Long = real.beginExport(pushedSeq).also { events.happened() }
}
