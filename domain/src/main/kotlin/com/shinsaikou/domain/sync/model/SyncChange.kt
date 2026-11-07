package com.shinsaikou.domain.sync.model

enum class SyncEntity(val key: String) {
    MANGA("manga"),
    CHAPTER("chapter"),
    CATEGORY("category"),
    MANGA_CATEGORY("manga_category"),
    HISTORY("history"),
    TRACK("track"),
    EXCLUDED_SCANLATOR("excluded_scanlator"),
    SCANLATOR_PRIORITY("scanlator_priority"),
    BOOKMARK_NOTE("bookmark_note"),
    MERGED_GROUP("merged_group"),
    ;

    companion object {
        fun fromKey(key: String): SyncEntity = entries.first { it.key == key }
    }
}

enum class SyncChangeType(val key: String) {
    UPSERT("upsert"),
    DELETE("delete"),
    ;

    companion object {
        fun fromKey(key: String): SyncChangeType = entries.first { it.key == key }
    }
}

/**
 * One log row. [entityId] is the row's own id for a manga, chapter, category or history row (the
 * chapter's id for history), and the parent's id for a child collection: the manga's for its
 * categories, excluded scanlators, scanlator priority and tracks, the chapter's for its bookmark
 * notes, and the group's for a merged group's members. A child collection travels whole, so a child
 * delete is logged as an upsert of its parent.
 */
data class SyncChange(
    val seq: Long,
    val entity: SyncEntity,
    val entityId: Long,
    val changeType: SyncChangeType,
    val changedAt: Long,
    /** Set only on the delete of a hard-deleted row, whose key cannot be read afterwards. */
    val deletedKey: SyncDeletedKey? = null,
)

/**
 * The natural key a hard delete recorded in the log (phase 3 Q5). A history row is keyed by its
 * chapter, so its delete carries a [Chapter] key.
 */
sealed interface SyncDeletedKey {
    data class Manga(val source: Long, val url: String) : SyncDeletedKey

    data class Chapter(val source: Long, val mangaUrl: String, val url: String) : SyncDeletedKey

    data class Category(val syncKey: String) : SyncDeletedKey
}
