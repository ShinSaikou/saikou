package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.IOException

/**
 * How [SyncRestorePreferences.markRestored] writes: the flag is on disk before [write] returns. A
 * `Preference`'s own `set` is `SharedPreferences.apply()`, and a process that dies within about
 * 100 ms of a restore finishing loses an applied write, so the next sync would skip the full export
 * that carries the restored rows (found by phase 5 unit TG3). The application binds a committing
 * writer over the file the app's [PreferenceStore] reads, as it does for the secret store, so reads
 * go on through the store; a JVM test writes into its own store.
 */
fun interface SyncRestoreFlagWriter {

    /**
     * Stores [value] under the preference [key] and returns once it is on disk.
     *
     * @throws IOException when the write did not reach the disk.
     */
    fun write(key: String, value: Boolean)
}

/**
 * The one flag a backup restore leaves for sync (phase 3 unit E2). It lives here, not in `:sync`'s
 * `SyncPreferences`, because `:backup` sets it and may not depend on `:sync`.
 *
 * A restore merges into existing rows without logging them, and this device's pull cursors are
 * already past whatever the other devices sent before it, so the sync engine's next run resets
 * those cursors, pulls again from the start and makes a full export. The key is app state: no
 * backup carries it and no restore overwrites it.
 */
@SingleIn(AppScope::class)
@Inject
class SyncRestorePreferences(
    preferenceStore: PreferenceStore,
    private val writer: SyncRestoreFlagWriter,
) {

    /** Set at the end of every restore, finished or cancelled, by [markRestored]; cleared by the sync engine. */
    val restoredSinceSync: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("sync_restored_since_sync"), false)

    /**
     * Sets [restoredSinceSync], committed before it returns (phase 5 unit SC3). Only this write is
     * committed: the engine's clear keeps `apply()`, since losing it costs one extra full export.
     *
     * @throws IOException when the write did not reach the disk.
     */
    fun markRestored() {
        writer.write(restoredSinceSync.key(), true)
    }
}
