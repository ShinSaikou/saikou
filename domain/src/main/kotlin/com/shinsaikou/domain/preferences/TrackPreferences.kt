package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/** What to do with a tracker when a chapter is marked read. */
enum class AutoTrackState {
    ALWAYS,
    ASK,
    NEVER,
}

/**
 * Tracker credentials and behaviour. The per-tracker keys are private, so they leave the device
 * only when the private-settings backup option is on. The key strings are upstream's.
 */
@SingleIn(AppScope::class)
@Inject
class TrackPreferences(private val preferenceStore: PreferenceStore) {

    fun trackUsername(trackerId: Long): Preference<String> =
        preferenceStore.getString(Preference.privateKey("pref_mangasync_username_$trackerId"), "")

    fun trackDisplayUsername(trackerId: Long): Preference<String> =
        preferenceStore.getString(Preference.privateKey("pref_mangasync_displayname_$trackerId"), "")

    fun trackPassword(trackerId: Long): Preference<String> =
        preferenceStore.getString(Preference.privateKey("pref_mangasync_password_$trackerId"), "")

    fun trackAuthExpired(trackerId: Long): Preference<Boolean> =
        preferenceStore.getBoolean(Preference.privateKey("pref_tracker_auth_expired_$trackerId"), false)

    fun trackToken(trackerId: Long): Preference<String> =
        preferenceStore.getString(Preference.privateKey("track_token_$trackerId"), "")

    fun setCredentials(trackerId: Long, username: String, password: String) {
        trackUsername(trackerId).set(username)
        trackPassword(trackerId).set(password)
        trackAuthExpired(trackerId).set(false)
    }

    val anilistScoreType: Preference<String> = preferenceStore.getString("anilist_score_type", "POINT_10")

    val mangabakaScoreType: Preference<String> = preferenceStore.getString("mangabaka_score_type", "STEP_1")

    val autoUpdateTrack: Preference<Boolean> = preferenceStore.getBoolean("pref_auto_update_manga_sync_key", true)

    val autoUpdateTrackOnMarkRead: Preference<AutoTrackState> =
        preferenceStore.getEnum("pref_auto_update_manga_on_mark_read", AutoTrackState.ALWAYS)
}
