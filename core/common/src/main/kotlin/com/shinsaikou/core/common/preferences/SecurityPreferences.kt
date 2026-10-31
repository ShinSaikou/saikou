package com.shinsaikou.core.common.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * App lock and screen privacy. Shaped after upstream's class of the same name, with its keys, so a
 * backup restores them; the label resources the enum carried there belong to the app module.
 */
@SingleIn(AppScope::class)
@Inject
class SecurityPreferences(preferenceStore: PreferenceStore) {

    val useAuthenticator: Preference<Boolean> = preferenceStore.getBoolean("use_biometric_lock", false)

    /** Minutes of background time before the lock is asked for again. */
    val lockAppAfter: Preference<Int> = preferenceStore.getInt("lock_app_after", 0)

    val secureScreen: Preference<SecureScreenMode> =
        preferenceStore.getEnum("secure_screen_v2", SecureScreenMode.INCOGNITO)

    val hideNotificationContent: Preference<Boolean> =
        preferenceStore.getBoolean("hide_notification_content", false)

    val lastAppClosed: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("last_app_closed"), 0L)

    enum class SecureScreenMode {
        ALWAYS,
        INCOGNITO,
        NEVER,
    }
}
