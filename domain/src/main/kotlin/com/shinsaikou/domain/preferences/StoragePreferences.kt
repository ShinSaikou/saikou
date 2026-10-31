package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Downloads live in app-private storage. Automatic backups and the Local source live there too until
 * the owner picks a storage location, upstream's one folder ([storageLocation]); then they live in its
 * `autobackup` and `local` folders, and an export of the downloads goes to its `downloads` folder
 * ([com.shinsaikou.domain.storage.StorageLocation]). The storage access framework is used for that
 * folder, for export, and for the one-time adoption of upstream's downloads (contract 04 section 4.13).
 * Every key is app state, so none appears in a backup.
 */
@SingleIn(AppScope::class)
@Inject
class StoragePreferences(preferenceStore: PreferenceStore) {

    /** Tree URI. Empty = ask each time. */
    val exportDirectory: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("storage_export_dir"), "")

    /**
     * Tree URI of the storage location, picked with ACTION_OPEN_DOCUMENT_TREE and held with a
     * persisted read and write grant. Empty = none, and everything stays in app-private storage as
     * before. Upstream's key name, as app state, as upstream keeps it.
     */
    val storageLocation: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("storage_dir"), "")

    /**
     * Tree URI of the storage location before the last change, kept with its grant while its
     * `autobackup`, `local` or `downloads` folder still holds something the app put there, so the
     * move into the new location can finish on a later run. Empty = none. Unit
     * storage-move-between-folders.
     */
    val previousStorageLocation: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("storage_previous_dir"), "")

    /**
     * Tree URI. Empty = filesDir/backups. Nothing in the app sets it any more: a storage location
     * puts automatic backups in its `autobackup` folder, ahead of this key, which is kept so a value
     * written before stays readable.
     */
    val backupDirectory: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("storage_backup_dir"), "")

    /**
     * Tree URI of the adoption of upstream's downloads that has not finished yet. Empty = none. The
     * adoption worker reads it when it starts, including when WorkManager restarts it after the
     * process died, and clears it when the run ends; a run started again over the same tree skips
     * every chapter already in the manifest (phase 2 unit J).
     */
    val adoptionTree: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("storage_adoption_tree"), "")
}
