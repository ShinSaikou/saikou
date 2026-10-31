package com.shinsaikou.appupdate

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The in-app updater's setting and state. The setting is the owner's and travels in a backup, like
 * the extension check beside it; the last check and the skipped version are app state, never
 * exported, so a restore on another device does not hold back its own check or its own prompt.
 */
@SingleIn(AppScope::class)
@Inject
class AppUpdatePreferences(preferenceStore: PreferenceStore) {

    /** Settings > Advanced, "Check for updates automatically". Off stops the check on arrival, not the manual one. */
    val autoCheck: Preference<Boolean> = preferenceStore.getBoolean("pref_app_update_auto_check", true)

    /** Epoch millis of the last request for the latest release, automatic or asked for; 0 when never. */
    val lastCheckAt: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("app_update_last_check"), 0L)

    /** The version "Skip this version" was pressed on, `0.0.2`; empty when none. */
    val skippedVersion: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("app_update_skipped_version"), "")
}
