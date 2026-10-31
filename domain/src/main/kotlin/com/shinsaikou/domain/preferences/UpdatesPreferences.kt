package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray

/** Filter state of the Updates feed. Every key is upstream's. */
@SingleIn(AppScope::class)
@Inject
class UpdatesPreferences(preferenceStore: PreferenceStore) {

    val filterDownloaded: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_updates_downloaded", TriState.DISABLED)

    val filterUnread: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_updates_unread", TriState.DISABLED)

    val filterStarted: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_updates_started", TriState.DISABLED)

    val filterBookmarked: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_updates_bookmarked", TriState.DISABLED)

    val filterExcludedScanlators: Preference<Boolean> =
        preferenceStore.getBoolean("pref_filter_updates_hide_excluded_scanlators", false)

    val filterIncludedCategories: Preference<List<Long>> =
        preferenceStore.getLongArray(INCLUDED_CATEGORIES_PREF_KEY, emptyList())

    val filterExcludedCategories: Preference<List<Long>> =
        preferenceStore.getLongArray(EXCLUDED_CATEGORIES_PREF_KEY, emptyList())

    companion object {
        private const val INCLUDED_CATEGORIES_PREF_KEY = "pref_filter_updates_included_categories"
        private const val EXCLUDED_CATEGORIES_PREF_KEY = "pref_filter_updates_excluded_categories"

        /** The keys holding category ids, each as one comma-joined string, which a restore remaps. */
        val categoryPreferenceKeys = setOf(
            INCLUDED_CATEGORIES_PREF_KEY,
            EXCLUDED_CATEGORIES_PREF_KEY,
        )
    }
}
