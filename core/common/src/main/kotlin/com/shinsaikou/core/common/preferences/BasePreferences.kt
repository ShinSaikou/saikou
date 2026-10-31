package com.shinsaikou.core.common.preferences

import com.shinsaikou.image.GLUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * App-wide preferences that belong to no single screen. Keys without the app state prefix are
 * upstream's, so a backup restores them byte for byte.
 */
@SingleIn(AppScope::class)
@Inject
class BasePreferences(preferenceStore: PreferenceStore) {

    val downloadedOnly: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("pref_downloaded_only"), false)

    val incognitoMode: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false)

    /** Replaced at first launch by the texture limit the device reports. */
    val hardwareBitmapThreshold: Preference<Int> =
        preferenceStore.getInt("pref_hardware_bitmap_threshold", GLUtil.SAFE_TEXTURE_LIMIT)

    val alwaysDecodeLongStripWithSSIV: Preference<Boolean> =
        preferenceStore.getBoolean("pref_always_decode_long_strip_with_ssiv", false)

    val displayProfile: Preference<String> = preferenceStore.getString("pref_display_profile_key", "")

    val batteryExemptionRequested: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("battery_exemption_requested"), false)

    val lastCrashLogExport: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("last_crash_log_export"), 0L)
}
