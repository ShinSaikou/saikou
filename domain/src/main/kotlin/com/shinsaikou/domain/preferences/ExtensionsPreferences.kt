package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** The one extensions preference class; the extension host receives it through its constructor. */
@SingleIn(AppScope::class)
@Inject
class ExtensionsPreferences(preferenceStore: PreferenceStore) {

    /** Manual per-package trust overrides, each `"<pkg>:<versionCode>:<signerSha256>"`. */
    val trustedExtensions: Preference<Set<String>> =
        preferenceStore.getStringSet(Preference.appStateKey("trusted_extensions"), emptySet())

    val extensionUpdatesCount: Preference<Int> = preferenceStore.getInt("ext_updates_count", 0)

    /**
     * Fresh. Empty = use the supported lib versions built into the source contract; otherwise a
     * comma separated list of `major.minor` values parsed by the extension host, which falls back
     * to the build constant when the text does not parse.
     */
    val libVersionOverride: Preference<String> = preferenceStore.getString("pref_extension_lib_version_override", "")

    /** Fresh. Epoch millis of the last index refresh. */
    val lastIndexRefresh: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("extension_index_last_refresh"), 0L)

    val autoCheckUpdates: Preference<Boolean> = preferenceStore.getBoolean("automatic_ext_updates", true)

    /**
     * Fresh, Settings > Experimental, off by default (R162 item 2). On, a daily job installs the
     * updates the configured indexes list. App state, so it stays on this device and out of
     * backups: a restore never starts unattended installs on another device.
     */
    val autoInstallUpdates: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("extension_auto_install_updates"), false)
}
