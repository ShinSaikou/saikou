package com.shinsaikou.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The sync worker's own settings and state (phase 3 unit G), beside the engine's `SyncPreferences`.
 * Every key is app state, so no backup carries it and no restore overwrites it (Q20: sync settings
 * do not travel).
 */
@SingleIn(AppScope::class)
@Inject
class SyncWorkPreferences(preferenceStore: PreferenceStore) {

    /** Hours between periodic runs, one of [INTERVALS]; 0 is off (Q10). */
    val intervalHours: Preference<Int> =
        preferenceStore.getInt(Preference.appStateKey("sync_interval_hours"), DEFAULT_INTERVAL_HOURS)

    /** Whether the automatic runs wait for an unmetered network; Sync now runs on any connection. */
    val wifiOnly: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("sync_wifi_only"), false)

    /**
     * The backend id whose first pull the safety backup guarded, or empty. A configured backend that
     * is not this one has its backup written before its first pull; sign-out empties it, so signing in
     * again, to the same backend or another, writes a new one.
     */
    val safetyBackupBackend: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("sync_safety_backup_backend"), "")

    /** The safety backup's file name, for Sync settings to show; empty until one is written. */
    val safetyBackupFile: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("sync_safety_backup_file"), "")

    companion object {
        /** The choices Sync settings offers, in hours; 0 is off. */
        val INTERVALS: List<Int> = listOf(0, 1, 6, 12, 24)

        const val DEFAULT_INTERVAL_HOURS = 6
    }
}
