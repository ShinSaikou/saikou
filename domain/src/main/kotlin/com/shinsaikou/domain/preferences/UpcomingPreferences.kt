package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getLongArray

/**
 * The Upcoming filter's category lists, from upstream's `UpcomingPreferences` (mihon#3607). The keys
 * are upstream's, so a backup from upstream brings them along.
 */
@SingleIn(AppScope::class)
@Inject
class UpcomingPreferences(preferenceStore: PreferenceStore) {

    val filterIncludedCategories: Preference<List<Long>> =
        preferenceStore.getLongArray(INCLUDED_CATEGORIES_PREF_KEY, emptyList())

    val filterExcludedCategories: Preference<List<Long>> =
        preferenceStore.getLongArray(EXCLUDED_CATEGORIES_PREF_KEY, emptyList())

    companion object {
        private const val INCLUDED_CATEGORIES_PREF_KEY = "pref_filter_upcoming_included_categories"
        private const val EXCLUDED_CATEGORIES_PREF_KEY = "pref_filter_upcoming_excluded_categories"

        /** The keys holding category ids, each as one comma-joined string, which a restore remaps. */
        val categoryPreferenceKeys = setOf(
            INCLUDED_CATEGORIES_PREF_KEY,
            EXCLUDED_CATEGORIES_PREF_KEY,
        )
    }
}
