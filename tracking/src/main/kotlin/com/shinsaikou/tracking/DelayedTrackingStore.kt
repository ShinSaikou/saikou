package com.shinsaikou.tracking

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * How [DelayedTrackingStore] writes: a queued push is on disk before [write] returns. A
 * `Preference`'s own `set` is `SharedPreferences.apply()`, which a process killed right after a
 * finished chapter can lose, and a push lost that way never reaches the tracker. The application
 * binds a committing writer over the file the app's [PreferenceStore] reads (R91 item 9's
 * `CommittingSecretWriter`), so reads go on through the store; a JVM test writes into its own map.
 */
fun interface DelayedTrackingWriter {

    /**
     * Stores [value] under the preference [key], or removes [key] when [value] is null, and returns
     * once it is on disk.
     *
     * @throws IOException when the write did not reach the disk.
     */
    fun write(key: String, value: String?)
}

/**
 * The tracker pushes waiting to be sent (phase 3 unit T1), modeled on upstream's `DelayedTrackingStore`:
 * a queue holding the highest chapter number to push for each manga and tracker, in preferences and
 * not in a table (the plan's section 2: no schema change). Upstream writes it only when a push fails;
 * here every push is recorded first and sent by the retry worker, so a process killed between a
 * finished chapter and the answer loses nothing (challenge 5 T1 item 4).
 *
 * An entry is keyed by the manga and the tracker, the pair `manga_sync` holds unique, never by the
 * track row's id as upstream keys it (phase 3 unit TQ). `manga_sync`'s insert lands on
 * `UNIQUE (manga_id, sync_id) ON CONFLICT REPLACE`, so every store of a bound pair (the worker's own
 * store of the service's answer, a bind, a sync pull) gives the row a new id, and an entry keyed by
 * the old one would be dropped as "its row is gone".
 *
 * The pair outlives the binding, though: a bind to another search result, or a sync pull carrying
 * one from another device, stores a row for the same pair naming another entry on the service. So
 * an entry also holds the remote id of the row it was recorded against (phase 3 unit TQ, verifier
 * finding 1), and means "this chapter of that entry": the worker drops it once the pair's row names
 * another remote id, and a push recorded against the new entry replaces it whatever its number.
 *
 * Each key is an app-state key: a backup never exports it, even with private settings on, and a
 * restore never overwrites it. The value is `<remoteId>:<chapterNumber>`, the number as a decimal
 * string, not upstream's float, so a chapter 10.1 stays 10.1. A key or value in any other form under
 * the prefix is not an entry: [removeOtherKeys] deletes it.
 *
 * Every call reads or commits the preference file; call it off the main thread. One lock orders
 * the reader's [add] against the worker's [remove], so a push raised while the worker sends the
 * old number survives the worker's removal.
 *
 * A second lock, [whileSending], orders the calls to the service that act on the queue or the row
 * (phase 4 unit TR, R100 carry-over 3): the retry worker holds it for each entry, from rereading
 * the entry to storing the answer and removing it, the owner's edits from the track sheet from
 * reading the row to the service's answer (and the clear, for a chapter), and the sheet's refresh
 * on open from its call to its store. So a hand edit sends and clears either before the worker
 * reads the entry or after the worker has stored its answer, never across its call: a chapter set
 * by hand is not overwritten by a worker call already on the wire, and an edit sends the row with
 * the worker's number, not the one before it. It is app-wide, not per pair: an edit can wait for
 * one worker call on another manga. The sheet's remove takes it too, and "Compare with
 * MyAnimeList" holds it for each row it applies (phase 4 unit TC); the reader's [add] does not.
 */
@SingleIn(AppScope::class)
@Inject
class DelayedTrackingStore(
    private val preferenceStore: PreferenceStore,
    private val writer: DelayedTrackingWriter,
) {

    private val lock = Any()
    private val sending = Mutex()

    /**
     * Runs [block] while no other caller of this method runs: the calls to the service that act on
     * the queue or the pair's row wait for each other. Not reentrant; [block] must not call it.
     */
    suspend fun <T> whileSending(block: suspend () -> T): T = sending.withLock { block() }

    /**
     * Queues [chapterNumber] for [mangaId] on [trackerId], recorded against the service's entry
     * [remoteId], unless an equal or higher number is already queued for that entry. An entry
     * queued for another remote id is replaced: the pair has been bound to another entry since.
     */
    fun add(mangaId: Long, trackerId: Long, remoteId: Long, chapterNumber: Double) {
        synchronized(lock) {
            val queued = get(mangaId, trackerId)
            if (queued != null && queued.remoteId == remoteId && queued.chapterNumber >= chapterNumber) return
            logcat(LogPriority.DEBUG) {
                "Queuing track item: manga $mangaId, tracker $trackerId, entry $remoteId, last chapter read: $chapterNumber"
            }
            writer.write(key(mangaId, trackerId), value(remoteId, chapterNumber))
        }
    }

    /**
     * Removes the entry for [mangaId] on [trackerId] if it is still for [remoteId] and holds no more
     * than [chapterNumber], the entry and number the caller acted on. A higher number queued
     * meanwhile, or a push recorded against another entry, stays for the next run.
     */
    fun remove(mangaId: Long, trackerId: Long, remoteId: Long, chapterNumber: Double) {
        synchronized(lock) {
            val queued = get(mangaId, trackerId) ?: return
            if (queued.remoteId != remoteId || queued.chapterNumber > chapterNumber) return
            writer.write(key(mangaId, trackerId), null)
        }
    }

    /**
     * Removes the entry for [mangaId] on [trackerId], whatever entry and number it holds (phase 3
     * unit T3). The track sheet calls it once the owner's own chapter has reached the service, so a
     * higher number queued from reading does not overwrite a number the owner lowered by hand, and
     * once the owner removed the pair's row.
     */
    fun clear(mangaId: Long, trackerId: Long) {
        synchronized(lock) {
            if (get(mangaId, trackerId) == null) return
            logcat(LogPriority.DEBUG) { "Clearing the queued push of manga $mangaId on tracker $trackerId" }
            writer.write(key(mangaId, trackerId), null)
        }
    }

    fun getItems(): List<DelayedTrackingItem> = synchronized(lock) {
        preferenceStore.getAll().mapNotNull { (key, value) -> itemOf(key, value) }
            .sortedWith(compareBy({ it.mangaId }, { it.trackerId }))
    }

    /**
     * Deletes every key under the queue's prefix that is not an entry, and returns the keys it
     * deleted: a key not in the form `<mangaId>_<trackerId>` (T1's, keyed by a track row id) or a
     * value not in the form `<remoteId>:<chapterNumber>` (TQ's first form, the number alone). Only a
     * T1, T2 or unmerged TQ build wrote either, on an emulator; their numbers are not carried over.
     */
    fun removeOtherKeys(): List<String> = synchronized(lock) {
        val others = preferenceStore.getAll()
            .filter { (key, value) -> key.startsWith(PREFIX) && itemOf(key, value) == null }
            .keys
            .toList()
        others.forEach { writer.write(it, null) }
        others
    }

    /**
     * The entry queued for [mangaId] on [trackerId] now, or null. A caller holding an entry it read
     * earlier asks again before acting on it, to see a clear, a raise or a new entry since.
     */
    fun current(mangaId: Long, trackerId: Long): DelayedTrackingItem? = synchronized(lock) { get(mangaId, trackerId) }

    private fun get(mangaId: Long, trackerId: Long): DelayedTrackingItem? {
        val key = key(mangaId, trackerId)
        return itemOf(key, preferenceStore.getString(key, "").get())
    }

    data class DelayedTrackingItem(
        val mangaId: Long,
        val trackerId: Long,
        val remoteId: Long,
        val chapterNumber: Double,
    )

    companion object {
        private val PREFIX = Preference.appStateKey("tracking_queue_")

        fun key(mangaId: Long, trackerId: Long): String = "$PREFIX${mangaId}_$trackerId"

        /** The value an entry for [remoteId] at [chapterNumber] is stored as. */
        fun value(remoteId: Long, chapterNumber: Double): String = "$remoteId:$chapterNumber"

        /** The entry a key and value in the queue's form hold, else null. */
        private fun itemOf(key: String, value: Any?): DelayedTrackingItem? {
            if (!key.startsWith(PREFIX)) return null
            val ids = key.removePrefix(PREFIX).split('_')
            if (ids.size != 2) return null
            val mangaId = ids[0].toLongOrNull() ?: return null
            val trackerId = ids[1].toLongOrNull() ?: return null
            val parts = (value as? String)?.split(':') ?: return null
            if (parts.size != 2) return null
            val remoteId = parts[0].toLongOrNull() ?: return null
            val chapterNumber = parts[1].toDoubleOrNull() ?: return null
            return DelayedTrackingItem(mangaId, trackerId, remoteId, chapterNumber)
        }
    }
}
