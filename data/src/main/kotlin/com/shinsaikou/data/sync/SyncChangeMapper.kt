package com.shinsaikou.data.sync

import com.shinsaikou.domain.sync.model.SyncChange
import com.shinsaikou.domain.sync.model.SyncChangeType
import com.shinsaikou.domain.sync.model.SyncDeletedKey
import com.shinsaikou.domain.sync.model.SyncEntity

/**
 * The `sync_change_log` columns in declaration order. The two text columns are written only by the
 * triggers in the other `.sq` files, so an unknown key means the schema and these enums have drifted
 * apart; [SyncEntity.fromKey] and [SyncChangeType.fromKey] throw rather than hide that.
 *
 * The `key_` columns are set only by the hard-delete triggers. A row whose key is incomplete maps to
 * no key, and the push skips a delete it cannot name.
 */
fun mapSyncChange(
    seq: Long,
    entityType: String,
    entityId: Long,
    changeType: String,
    changedAt: Long,
    keySource: Long?,
    keyMangaUrl: String?,
    keyChapterUrl: String?,
    keyCategory: String?,
): SyncChange = SyncChange(
    seq = seq,
    entity = SyncEntity.fromKey(entityType),
    entityId = entityId,
    changeType = SyncChangeType.fromKey(changeType),
    changedAt = changedAt,
    deletedKey = when {
        keyCategory != null -> SyncDeletedKey.Category(keyCategory)
        keySource == null || keyMangaUrl == null -> null
        keyChapterUrl != null -> SyncDeletedKey.Chapter(keySource, keyMangaUrl, keyChapterUrl)
        else -> SyncDeletedKey.Manga(keySource, keyMangaUrl)
    },
)
