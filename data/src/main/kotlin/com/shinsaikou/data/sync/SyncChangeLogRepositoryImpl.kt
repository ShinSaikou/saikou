package com.shinsaikou.data.sync

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.sync.model.SyncChange
import com.shinsaikou.domain.sync.model.SyncEntity
import com.shinsaikou.domain.sync.repository.SyncChangeLogRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncChangeLogRepositoryImpl(
    private val handler: DatabaseHandler,
) : SyncChangeLogRepository {

    /**
     * The log records one row per change, so a long-idle device can have far more pending than a
     * caller expects to hold in memory. The cap is enforced here rather than trusted to the caller.
     */
    override suspend fun getChangesAfter(afterSeq: Long, limit: Long): List<SyncChange> =
        handler.awaitList {
            sync_change_logQueries.getAfter(afterSeq, limit.coerceAtMost(MAX_LIMIT), ::mapSyncChange)
        }

    override suspend fun getLatestSeq(): Long =
        handler.awaitOne { sync_change_logQueries.getLatestSeq() }

    override suspend fun getChangedIds(entity: SyncEntity, afterSeq: Long): List<Long> =
        handler.awaitList { sync_change_logQueries.getChangedIds(entity.key, afterSeq) }

    override suspend fun prune(upToSeq: Long) {
        handler.await { sync_change_logQueries.pruneUpTo(upToSeq) }
    }

    private companion object {
        const val MAX_LIMIT = 5000L
    }
}
