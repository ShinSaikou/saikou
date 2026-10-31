package com.shinsaikou.downloads

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.IOException

/**
 * How [PendingChapterDeleteStore] writes: a staged chapter is on disk before [write] returns. A
 * `Preference`'s own `set` is `SharedPreferences.apply()`, which a process killed right after the
 * last page can lose. The application binds a committing writer over the file the app's
 * [PreferenceStore] reads, as it does for the tracker retry store (R95 item 4), so reads go on
 * through the store; a JVM test writes into its own map.
 */
fun interface PendingChapterDeleteWriter {

    /**
     * Stores [value] under the preference [key], or removes [key] when [value] is null, and returns
     * once it is on disk.
     *
     * @throws IOException when the write did not reach the disk.
     */
    fun write(key: String, value: String?)
}

/**
 * The chapters the reader staged for deletion after reading and has not deleted yet (phase 3 unit
 * DR), upstream's `DownloadPendingDeleter`. Upstream keeps them in a `SharedPreferences` file of their
 * own, `chapters_to_delete`; here each is an app-state key in the app's preference file, one per
 * chapter, holding its entry's id. An app-state key is never exported by a backup, private settings
 * or not, never overwritten by a restore, and sync carries no preference, so a staged chapter never
 * leaves this device; no table is added (no schema change).
 *
 * [takeAll] is upstream's `getPendingChapters`: it answers every staged chapter and removes them in the
 * same call, so a chapter is carried out once, by the reader close that took it.
 *
 * Every call reads or commits the preference file; call it off the main thread.
 */
@SingleIn(AppScope::class)
@Inject
class PendingChapterDeleteStore(
    private val preferenceStore: PreferenceStore,
    private val writer: PendingChapterDeleteWriter,
) {

    private val lock = Any()

    /** Stages [chapterId] of [mangaId]; staging it again changes nothing. */
    fun add(mangaId: Long, chapterId: Long) {
        synchronized(lock) {
            writer.write(key(chapterId), mangaId.toString())
        }
    }

    /** Every staged chapter id to its entry's id, each removed from the store as it is taken. */
    fun takeAll(): Map<Long, Long> = synchronized(lock) {
        val staged = pending()
        staged.keys.forEach { writer.write(key(it), null) }
        staged
    }

    /** Every staged chapter id to its entry's id, left in the store. */
    fun pending(): Map<Long, Long> = synchronized(lock) {
        preferenceStore.getAll().entries.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX)) return@mapNotNull null
            val chapterId = key.removePrefix(PREFIX).toLongOrNull() ?: return@mapNotNull null
            val mangaId = (value as? String)?.toLongOrNull() ?: return@mapNotNull null
            chapterId to mangaId
        }
            .toMap()
    }

    companion object {
        private val PREFIX = Preference.appStateKey("delete_after_read_")

        fun key(chapterId: Long): String = "$PREFIX$chapterId"
    }
}
