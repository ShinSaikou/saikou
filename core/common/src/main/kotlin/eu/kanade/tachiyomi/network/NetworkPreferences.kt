// Vendored from upstream v0.20.4: core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkPreferences.kt (modified: yes) adds the custom DNS over HTTPS server's URL
package eu.kanade.tachiyomi.network

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class NetworkPreferences(
    preferenceStore: PreferenceStore,
    verboseLoggingDefault: Boolean = false,
) {

    val verboseLogging: Preference<Boolean> = preferenceStore.getBoolean("verbose_logging", verboseLoggingDefault)

    val dohProvider: Preference<Int> = preferenceStore.getInt("doh_provider", -1)

    /** The server [dohProvider] uses when it is `PREF_DOH_CUSTOM`; blank until one is chosen. */
    val dohProviderCustom: Preference<String> = preferenceStore.getString("doh_provider_custom", "")

    val defaultUserAgent: Preference<String> = preferenceStore.getString(
        "default_user_agent",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36",
    )
}
