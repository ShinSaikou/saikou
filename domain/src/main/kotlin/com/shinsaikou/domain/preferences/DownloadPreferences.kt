package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Declared in full because every key travels in an upstream backup, even though nothing reads them
 * until the download engine ships.
 */
@SingleIn(AppScope::class)
@Inject
class DownloadPreferences(preferenceStore: PreferenceStore) {

    val downloadOnlyOverWifi: Preference<Boolean> =
        preferenceStore.getBoolean("pref_download_only_over_wifi_key", true)

    val saveChaptersAsCBZ: Preference<Boolean> = preferenceStore.getBoolean("save_chapter_as_cbz", true)

    val splitTallImages: Preference<Boolean> = preferenceStore.getBoolean("split_tall_images", true)

    val autoDownloadWhileReading: Preference<Int> = preferenceStore.getInt("auto_download_while_reading", 0)

    val removeAfterReadSlots: Preference<Int> = preferenceStore.getInt("remove_after_read_slots", -1)

    val removeAfterMarkedAsRead: Preference<Boolean> =
        preferenceStore.getBoolean("pref_remove_after_marked_as_read_key", false)

    val removeBookmarkedChapters: Preference<Boolean> = preferenceStore.getBoolean("pref_remove_bookmarked", false)

    val removeExcludeCategories: Preference<Set<String>> =
        preferenceStore.getStringSet(REMOVE_EXCLUDE_CATEGORIES_PREF_KEY, emptySet())

    val downloadNewChapters: Preference<Boolean> = preferenceStore.getBoolean("download_new", false)

    val downloadNewChapterCategories: Preference<Set<String>> =
        preferenceStore.getStringSet(DOWNLOAD_NEW_CATEGORIES_PREF_KEY, emptySet())

    val downloadNewChapterCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet(DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    val downloadNewUnreadChaptersOnly: Preference<Boolean> =
        preferenceStore.getBoolean("download_new_unread_chapters_only", false)

    /**
     * Sources the engine downloads from at once, one chapter each. Read only while
     * [parallelSourceDownloads] is on (amends R65, which left it unread).
     */
    val parallelSourceLimit: Preference<Int> = preferenceStore.getInt("download_parallel_source_limit", 5)

    /**
     * Experimental: the switch that has the engine run up to [parallelSourceLimit] sources at once
     * instead of one chapter at a time (amends R65). Off by default (R162 item 2). App state, so it
     * stays on the device and out of backups.
     */
    val parallelSourceDownloads: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("download_parallel_sources"), false)

    val parallelPageLimit: Preference<Int> = preferenceStore.getInt("download_parallel_page_limit", 5)

    companion object {
        private const val REMOVE_EXCLUDE_CATEGORIES_PREF_KEY = "remove_exclude_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_PREF_KEY = "download_new_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_categories_exclude"

        val categoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
