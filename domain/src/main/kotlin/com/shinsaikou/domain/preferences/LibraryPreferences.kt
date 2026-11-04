package com.shinsaikou.domain.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

/**
 * Library screen and library update settings. Every key without the app state prefix is upstream's, so
 * an upstream backup restores byte for byte.
 */
@SingleIn(AppScope::class)
@Inject
class LibraryPreferences(private val preferenceStore: PreferenceStore) {

    val displayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_library",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val sortingMode: Preference<LibrarySort> = preferenceStore.getObjectFromString(
        "library_sorting_mode",
        LibrarySort.default,
        LibrarySort.Serializer::serialize,
        LibrarySort.Serializer::deserialize,
    )

    val randomSortSeed: Preference<Int> = preferenceStore.getInt("library_random_sort_seed", 0)

    val portraitColumns: Preference<Int> = preferenceStore.getInt("pref_library_columns_portrait_key", 0)

    val landscapeColumns: Preference<Int> = preferenceStore.getInt("pref_library_columns_landscape_key", 0)

    val lastUpdatedTimestamp: Preference<Long> =
        preferenceStore.getLong(Preference.appStateKey("library_update_last_timestamp"), 0L)

    /**
     * How many entries the last automatic run skipped, by reason, each as `reason:count`: a
     * [LibraryUpdateRestrictions] key, or `fetch_once`. Updates shows them under the last update
     * line, since an automatic run otherwise only logged them. App state, never backed up.
     */
    val lastAutoUpdateSkipped: Preference<Set<String>> =
        preferenceStore.getStringSet(Preference.appStateKey("library_update_last_auto_skipped"), emptySet())

    /** Hours between automatic runs; 0 = off. */
    val autoUpdateInterval: Preference<Int> = preferenceStore.getInt("pref_library_update_interval_key", 0)

    val autoUpdateDeviceRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_restriction",
        setOf(LibraryUpdateRestrictions.DEVICE_ONLY_ON_WIFI),
    )

    val autoUpdateMangaRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_manga_restriction",
        setOf(
            LibraryUpdateRestrictions.MANGA_HAS_UNREAD,
            LibraryUpdateRestrictions.MANGA_NON_COMPLETED,
            LibraryUpdateRestrictions.MANGA_NON_READ,
            LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    /**
     * Experimental: an entry's update interval dialog offers a chosen number of days, and the
     * library offers the filter that finds those entries. App state, so a backup neither carries it
     * nor turns it off: the switch going off is what clears the chosen intervals, and neither the
     * intervals nor the switch travel with a backup.
     */
    val customUpdateInterval: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("library_custom_update_interval"), false)

    val autoUpdateMetadata: Preference<Boolean> = preferenceStore.getBoolean("auto_update_metadata", false)

    val showContinueReadingButton: Preference<Boolean> =
        preferenceStore.getBoolean("display_continue_reading_button", false)

    val markDuplicateReadChapterAsRead: Preference<Set<String>> =
        preferenceStore.getStringSet("mark_duplicate_read_chapter_read", emptySet())

    // region Filter

    val filterDownloaded: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_library_downloaded_v2", TriState.DISABLED)

    val filterUnread: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_library_unread_v2", TriState.DISABLED)

    val filterStarted: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_library_started_v2", TriState.DISABLED)

    val filterBookmarked: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_library_bookmarked_v2", TriState.DISABLED)

    val filterCompleted: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_library_completed_v2", TriState.DISABLED)

    val filterIntervalCustom: Preference<TriState> =
        preferenceStore.getEnum("pref_filter_library_interval_custom_v2", TriState.DISABLED)

    fun filterTracking(id: Int): Preference<TriState> =
        preferenceStore.getEnum("pref_filter_library_tracked_${id}_v2", TriState.DISABLED)

    // endregion

    // region Badges

    val downloadBadge: Preference<Boolean> = preferenceStore.getBoolean("display_download_badge", false)

    val unreadBadge: Preference<Boolean> = preferenceStore.getBoolean("display_unread_badge", true)

    val localBadge: Preference<Boolean> = preferenceStore.getBoolean("display_local_badge", true)

    val languageBadge: Preference<Boolean> = preferenceStore.getBoolean("display_language_badge", false)

    val newShowUpdatesCount: Preference<Boolean> = preferenceStore.getBoolean("library_show_updates_count", true)

    val newUpdatesCount: Preference<Int> =
        preferenceStore.getInt(Preference.appStateKey("library_unseen_updates_count"), 0)

    // endregion

    // region Category

    val defaultCategory: Preference<Int> = preferenceStore.getInt(DEFAULT_CATEGORY_PREF_KEY, -1)

    val lastUsedCategory: Preference<Int> = preferenceStore.getInt(Preference.appStateKey("last_used_category"), 0)

    val categoryTabs: Preference<Boolean> = preferenceStore.getBoolean("display_category_tabs", true)

    val categoryNumberOfItems: Preference<Boolean> = preferenceStore.getBoolean("display_number_of_items", false)

    val categorizedDisplaySettings: Preference<Boolean> = preferenceStore.getBoolean("categorized_display", false)

    val updateCategories: Preference<Set<String>> =
        preferenceStore.getStringSet(LIBRARY_UPDATE_CATEGORIES_PREF_KEY, emptySet())

    val updateCategoriesExclude: Preference<Set<String>> =
        preferenceStore.getStringSet(LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    // endregion

    // region Chapter

    val filterChapterByRead: Preference<Long> =
        preferenceStore.getLong("default_chapter_filter_by_read", Manga.SHOW_ALL)

    val filterChapterByDownloaded: Preference<Long> =
        preferenceStore.getLong("default_chapter_filter_by_downloaded", Manga.SHOW_ALL)

    val filterChapterByBookmarked: Preference<Long> =
        preferenceStore.getLong("default_chapter_filter_by_bookmarked", Manga.SHOW_ALL)

    /** Source order, chapter number or upload date. */
    val sortChapterBySourceOrNumber: Preference<Long> =
        preferenceStore.getLong("default_chapter_sort_by_source_or_number", Manga.CHAPTER_SORTING_SOURCE)

    val displayChapterByNameOrNumber: Preference<Long> =
        preferenceStore.getLong("default_chapter_display_by_name_or_number", Manga.CHAPTER_DISPLAY_NAME)

    val sortChapterByAscendingOrDescending: Preference<Long> =
        preferenceStore.getLong("default_chapter_sort_by_ascending_or_descending", Manga.CHAPTER_SORT_DESC)

    fun setChapterSettingsDefault(manga: Manga) {
        filterChapterByRead.set(manga.unreadFilterRaw)
        filterChapterByDownloaded.set(manga.downloadedFilterRaw)
        filterChapterByBookmarked.set(manga.bookmarkedFilterRaw)
        sortChapterBySourceOrNumber.set(manga.sorting)
        displayChapterByNameOrNumber.set(manga.displayMode)
        sortChapterByAscendingOrDescending.set(
            if (manga.sortDescending()) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
        )
    }

    val autoClearChapterCache: Preference<Boolean> = preferenceStore.getBoolean("auto_clear_chapter_cache", false)

    val hideMissingChapters: Preference<Boolean> =
        preferenceStore.getBoolean("pref_hide_missing_chapter_indicators", false)

    // endregion

    // region Swipe actions

    /**
     * Upstream's "Chapter on swipe to left", the chapter row's end actions. Saikou reads the start key
     * where upstream reads the end key (ruling R19), so the default moved with the value: an upstream user
     * who never set it bookmarks on a swipe to the left in both apps (phase 4 unit CS).
     */
    val swipeToStartAction: Preference<ChapterSwipeAction> =
        preferenceStore.getEnum("pref_chapter_swipe_start_action", ChapterSwipeAction.ToggleBookmark)

    /** Upstream's "Chapter on swipe to right", the row's start actions; marks read by default, as upstream's does. */
    val swipeToEndAction: Preference<ChapterSwipeAction> =
        preferenceStore.getEnum("pref_chapter_swipe_end_action", ChapterSwipeAction.ToggleRead)

    // endregion

    val updateMangaTitles: Preference<Boolean> = preferenceStore.getBoolean("pref_update_library_manga_titles", false)

    val disallowNonAsciiFilenames: Preference<Boolean> =
        preferenceStore.getBoolean("disallow_non_ascii_filenames", false)

    /** Fresh: when on, manga with a manual position sort ahead of everything else. */
    val manualSortEnabled: Preference<Boolean> = preferenceStore.getBoolean("library_manual_sort", false)

    enum class ChapterSwipeAction {
        ToggleRead,
        ToggleBookmark,
        Download,
        Disabled,
    }

    companion object {
        const val MARK_DUPLICATE_CHAPTER_READ_NEW = "new"
        const val MARK_DUPLICATE_CHAPTER_READ_EXISTING = "existing"

        const val DEFAULT_CATEGORY_PREF_KEY = "default_category"
        private const val LIBRARY_UPDATE_CATEGORIES_PREF_KEY = "library_update_categories"
        private const val LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY = "library_update_categories_exclude"

        val categoryPreferenceKeys = setOf(
            DEFAULT_CATEGORY_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}

/**
 * Values stored in [LibraryPreferences.autoUpdateDeviceRestrictions] and
 * [LibraryPreferences.autoUpdateMangaRestrictions]. The constant names and their string values are
 * upstream's, including the two that read backwards; they are persisted values and are not renamed.
 */
object LibraryUpdateRestrictions {
    const val DEVICE_ONLY_ON_WIFI = "wifi"
    const val DEVICE_NETWORK_NOT_METERED = "network_not_metered"
    const val DEVICE_CHARGING = "ac"

    const val MANGA_NON_COMPLETED = "manga_ongoing"
    const val MANGA_HAS_UNREAD = "manga_fully_read"
    const val MANGA_NON_READ = "manga_started"
    const val MANGA_OUTSIDE_RELEASE_PERIOD = "manga_outside_release_period"
}

/**
 * [LibraryPreferences.lastAutoUpdateSkipped]'s form: one `reason:count` string per reason, since a
 * preference holds strings. The update worker writes it and the Updates screen reads it.
 */
object LibraryUpdateSkipCounts {
    fun encode(counts: Map<String, Int>): Set<String> =
        counts.filterValues { it > 0 }.mapTo(mutableSetOf()) { (reason, count) -> "$reason:$count" }

    /** A string that does not parse is left out rather than failing the screen. */
    fun decode(stored: Set<String>): Map<String, Int> = stored.mapNotNull { entry ->
        val reason = entry.substringBeforeLast(':', missingDelimiterValue = "")
        val count = entry.substringAfterLast(':').toIntOrNull()
        if (reason.isEmpty() || count == null || count <= 0) null else reason to count
    }.toMap()
}
