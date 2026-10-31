package com.shinsaikou.source

import androidx.preference.PreferenceScreen

/**
 * A source with user settings. The settings screen hosts an androidx.preference
 * [PreferenceScreen] (inside a PreferenceFragmentCompat wrapped in AndroidView) and hands it to
 * the source; extension sources populate it with their own Preference objects backed by the
 * SharedPreferences file "source_<id>" (the extension ABI's fixed key, see 03-extensions.md 3.4).
 */
interface ConfigurableSource : Source {
    /** Fills [screen] with the source's own preferences. Called on the main thread. */
    fun setupPreferenceScreen(screen: PreferenceScreen)
}
