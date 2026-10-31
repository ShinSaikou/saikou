package com.shinsaikou.sources.local

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Where the Local source reads from. App state, so it never enters a backup.
 *
 * Contract 06 decision F6 puts this key on `StoragePreferences` in `:domain`, but that class ships
 * only the two keys of 04-domain 4.13 and `:sources:local` may not edit `:domain`. The key string
 * is the one F6 names, so moving it later is a rename with no migration.
 */
@SingleIn(AppScope::class)
@Inject
class LocalSourcePreferences(preferenceStore: PreferenceStore) {

    /** Tree URI picked with ACTION_OPEN_DOCUMENT_TREE. Empty = the app's own external directory. */
    val localSourceDirectory: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("storage_local_dir"), "")
}
