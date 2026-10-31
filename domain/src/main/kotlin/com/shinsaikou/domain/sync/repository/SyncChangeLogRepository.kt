package com.shinsaikou.domain.sync.repository

import com.shinsaikou.domain.sync.model.SyncChange
import com.shinsaikou.domain.sync.model.SyncEntity

/**
 * There is no write function: the table is written by triggers, and a library add's chapters without
 * progress by `MangaRepositoryImpl`'s own statement (phase 4 unit NB).
 */
interface SyncChangeLogRepository {
    /** Rows with seq > afterSeq, ORDER BY seq, at most limit (cap 5000). */
    suspend fun getChangesAfter(afterSeq: Long, limit: Long): List<SyncChange>

    suspend fun getLatestSeq(): Long

    /** Distinct entity ids of the given entity changed after afterSeq. */
    suspend fun getChangedIds(entity: SyncEntity, afterSeq: Long): List<Long>

    /** Deletes rows with seq <= upToSeq. Called by nobody in Phase 1. */
    suspend fun prune(upToSeq: Long)
}
