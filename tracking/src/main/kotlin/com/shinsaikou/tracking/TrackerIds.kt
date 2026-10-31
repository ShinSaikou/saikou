package com.shinsaikou.tracking

/**
 * The wire numbers of `manga_sync.sync_id` and `BackupTracking.syncId`, taken from upstream's
 * TrackerManager. They are persisted values and a backup's only handle on which service a row
 * belongs to, so they are never renumbered and never reused.
 */
object TrackerIds {
    const val MYANIMELIST: Long = 1L
    const val ANILIST: Long = 2L
    const val KITSU: Long = 3L
    const val SHIKIMORI: Long = 4L
    const val BANGUMI: Long = 5L

    /** Self-hosted server tracker paired with an extension of the same name; the name is omitted. */
    const val SERVER_TRACKER_6: Long = 6L

    const val MANGAUPDATES: Long = 7L

    /** Self-hosted server tracker paired with an extension of the same name; the name is omitted. */
    const val SERVER_TRACKER_8: Long = 8L

    /** Self-hosted server tracker paired with an extension of the same name; the name is omitted. */
    const val SERVER_TRACKER_9: Long = 9L

    const val HIKKA: Long = 10L
    const val MANGABAKA: Long = 11L

    /**
     * Upstream v0.21.0's tracker for a reading site's own lists; the site's name is omitted. Until it
     * was known here, a restore skipped its rows as an unknown tracker's and never wrote them back out.
     */
    const val SITE_TRACKER_12: Long = 12L

    val ALL: Set<Long> = setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L)

    fun isKnown(id: Long): Boolean = id in ALL
}
