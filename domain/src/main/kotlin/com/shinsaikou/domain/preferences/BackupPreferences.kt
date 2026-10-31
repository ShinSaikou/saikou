package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@SingleIn(AppScope::class)
@Inject
class BackupPreferences(preferenceStore: PreferenceStore) {

    /** Hours between automatic backups; 0 = off. */
    val backupInterval: Preference<Int> = preferenceStore.getInt("backup_interval", 12)

    /**
     * How many automatic backups are kept, within [KEPT_BACKUPS_RANGE]. App state, as the folder they
     * are kept in is: how much of this device's storage they take is no other install's choice.
     */
    val keptBackups: Preference<Int> =
        preferenceStore.getInt(Preference.appStateKey("backup_kept_count"), DEFAULT_KEPT_BACKUPS)

    val lastAutoBackupTimestamp: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("last_auto_backup_timestamp"), 0L)

    /** Fresh: JSON checkpoint of an interrupted import, written and read by the backup module. */
    val lastImportCheckpoint: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("backup_import_checkpoint"), "")

    companion object {
        const val DEFAULT_KEPT_BACKUPS = 4
        val KEPT_BACKUPS_RANGE: IntRange = 2..10
    }
}
