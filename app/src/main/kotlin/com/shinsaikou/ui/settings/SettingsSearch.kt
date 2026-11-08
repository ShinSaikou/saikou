package com.shinsaikou.ui.settings

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.HeadingItem
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Upstream's settings search (`SettingsSearchScreen`): every row of every section, found by its
 * title or the line under it, and a result opens the section scrolled to the row, which flashes.
 *
 * Upstream builds its index from the preference lists its screens are made of. Saikou's sections
 * are composables, so the index is [settingsSearchIndex], written out, and each row it names is
 * wrapped in a [SettingsSearchTarget] that scrolls to it and flashes it when a result asks.
 * `SettingsSearchIndexTest` reads the sections' source and fails when a row title has no entry, or
 * an entry no row.
 */

/**
 * Where a result leads: the row titled [titleRes] in [section], inside the group headed [groupRes]
 * when it has one. The group tells the two Reader rows of the same name apart, Crop borders under
 * Paged and under Long strip; a group's own heading has none.
 */
internal data class SettingsSearchKey(
    val section: SettingsSection,
    @StringRes val titleRes: Int,
    @StringRes val groupRes: Int?,
) {
    /** The section's ordinal, the title and the group (0 for none), as a saved state holds it. */
    val ids: IntArray
        get() = intArrayOf(section.ordinal, titleRes, groupRes ?: 0)

    /** The settings screen opened on the section, scrolled to the row. */
    val route: Route.Settings
        get() = Route.Settings(section = section, highlightTitle = titleRes, highlightGroup = groupRes ?: 0)

    companion object {
        /** [ids] read back; anything else, the empty array included, is no key. */
        fun of(ids: IntArray): SettingsSearchKey? {
            if (ids.size != 3) return null
            val (section, title, group) = ids
            return SettingsSearchKey(SettingsSection.entries[section], title, group.takeIf { it != 0 })
        }
    }
}

/** The row [Route.Settings] asks to be shown, or null. */
internal val Route.Settings.highlightKey: SettingsSearchKey?
    get() = section?.takeIf { highlightTitle != 0 }?.let {
        SettingsSearchKey(it, highlightTitle, highlightGroup.takeIf { group -> group != 0 })
    }

/**
 * A row the search can show only on some devices, or only while a switch is on; otherwise the
 * section does not draw it.
 */
internal enum class SettingsSearchCondition {
    /** Reader's draw under the cutout, as upstream offers it. */
    DISPLAY_CUTOUT,

    /** Security's app lock, offered only while the device can authenticate. */
    AUTHENTICATION,

    /** Downloads' sources at once, drawn only while its Experimental switch is on. */
    PARALLEL_SOURCE_DOWNLOADS,
}

/**
 * One searchable row. [summaryRes] is the fixed line drawn under it, matched as upstream matches a
 * preference's subtitle; a line built from arguments has none here.
 */
internal data class SettingsSearchEntry(
    val section: SettingsSection,
    @StringRes val titleRes: Int,
    @StringRes val groupRes: Int? = null,
    @StringRes val summaryRes: Int? = null,
    val condition: SettingsSearchCondition? = null,
) {
    val key: SettingsSearchKey
        get() = SettingsSearchKey(section, titleRes, groupRes)
}

/**
 * Every row the search finds, section by section in the list's order and each section's rows in
 * the order it draws them, so results read top to bottom as the sections do. A group's heading is
 * an entry of its own, as upstream's search finds a group's title, and its rows follow it.
 *
 * Left out: a row shown only while another setting is on (Flash on page change's three, Lock when
 * idle, the navigator's height, the auto-scroll speed, the two-page layout), whose result would open
 * the section with nothing to scroll to, unless a [SettingsSearchCondition] hides its result while
 * the row is not drawn (Downloads' sources at once, behind its Experimental switch); a row only some
 * states have (a move left over, the last adoption, an earlier sync report); and a fact rather than a
 * setting (the signed-in account). Sync's signed-in rows are kept: signed out, the section opens on the sign-in
 * they wait for. Its service picker is drawn only signed out, so signed in its result lands on the
 * account in use. `SettingsSearchIndexTest` holds the list.
 */
internal val settingsSearchIndex: List<SettingsSearchEntry> = buildList {
    section(SettingsSection.APPEARANCE) {
        row(R.string.settings_theme)
        row(R.string.settings_theme_mode)
        row(R.string.pref_amoled, R.string.pref_amoled_detail)
        row(R.string.settings_tablet_ui)
        row(R.string.pref_date_format)
        row(R.string.pref_relative_format)
    }
    section(SettingsSection.LIBRARY) {
        row(R.string.pref_unread_badge, R.string.pref_unread_badge_detail)
        row(R.string.pref_download_badge, R.string.pref_download_badge_detail)
        row(R.string.pref_manual_sort, R.string.pref_manual_sort_detail)
        row(R.string.default_category)
        row(R.string.categorized_display_settings)
        row(R.string.pref_library_update_interval)
        row(R.string.pref_library_update_restriction)
        row(R.string.categories_title)
        row(R.string.pref_library_update_metadata, R.string.pref_library_update_metadata_detail)
        row(R.string.pref_library_update_smart_update)
        row(R.string.pref_chapter_swipe_start)
        row(R.string.pref_chapter_swipe_end)
        row(R.string.pref_mark_duplicate_read_chapter_read)
    }
    section(SettingsSection.READER) {
        row(R.string.pref_viewer_type)
        row(R.string.pref_double_tap_anim_speed)
        row(R.string.pref_show_reading_mode, R.string.pref_show_reading_mode_summary)
        row(R.string.pref_show_navigation_mode, R.string.pref_show_navigation_mode_summary)
        row(R.string.pref_page_transitions)
        group(R.string.pref_category_display) {
            row(R.string.pref_rotation_type)
            row(R.string.pref_reader_theme)
            row(R.string.pref_fullscreen, R.string.pref_fullscreen_detail)
            row(R.string.pref_cutout_short, condition = SettingsSearchCondition.DISPLAY_CUTOUT)
            row(R.string.pref_keep_screen_on, R.string.pref_keep_screen_on_detail)
            row(R.string.pref_show_page_number, R.string.pref_show_page_number_detail)
        }
        group(R.string.pref_category_eink) {
            row(R.string.pref_flash_page, R.string.pref_flash_page_summ)
        }
        group(R.string.pref_category_reading) {
            row(R.string.pref_skip_read_chapters)
            row(R.string.pref_skip_filtered_chapters)
            row(R.string.pref_skip_dupe_chapters)
            row(R.string.pref_always_show_chapter_transition)
        }
        group(R.string.pager_viewer) {
            row(R.string.pref_viewer_nav)
            row(R.string.pref_read_with_tapping_inverted)
            row(R.string.pref_image_scale_type)
            row(R.string.pref_zoom_start)
            row(R.string.pref_crop_borders)
            row(R.string.pref_landscape_zoom)
            row(R.string.pref_navigate_pan)
            row(R.string.pref_dual_page_split)
            row(R.string.pref_dual_page_invert, R.string.pref_dual_page_invert_summary)
            row(R.string.pref_page_rotate)
            row(R.string.pref_page_rotate_invert)
        }
        group(R.string.webtoon_viewer) {
            row(R.string.pref_viewer_nav)
            row(R.string.pref_read_with_tapping_inverted)
            row(R.string.pref_webtoon_side_padding)
            row(R.string.pref_hide_threshold)
            row(R.string.pref_crop_borders)
            row(R.string.pref_dual_page_split)
            row(R.string.pref_dual_page_invert, R.string.pref_dual_page_invert_summary)
            row(R.string.pref_page_rotate)
            row(R.string.pref_page_rotate_invert)
            row(R.string.pref_double_tap_zoom)
            row(R.string.pref_webtoon_disable_zoom_out)
        }
        group(R.string.pref_reader_navigation) {
            row(R.string.pref_read_with_volume_keys)
            row(R.string.pref_read_with_volume_keys_inverted)
            row(R.string.pref_vertical_navigator)
            row(R.string.pref_webtoon_vertical_navigator_on_left)
        }
        group(R.string.pref_reader_actions) {
            row(R.string.pref_read_with_long_tap)
            row(R.string.pref_create_folder_per_manga, R.string.pref_create_folder_per_manga_summary)
        }
    }
    section(SettingsSection.DOWNLOADS) {
        row(R.string.pref_download_only_over_wifi, R.string.pref_download_only_over_wifi_detail)
        row(R.string.pref_download_parallel_sources, condition = SettingsSearchCondition.PARALLEL_SOURCE_DOWNLOADS)
        row(R.string.pref_download_parallel_pages)
        group(R.string.pref_category_delete_chapters) {
            row(R.string.pref_remove_after_marked_as_read)
            row(R.string.pref_remove_after_read)
            row(R.string.pref_remove_bookmarked_chapters)
            row(R.string.pref_remove_exclude_categories)
        }
        row(R.string.pref_download_new, R.string.pref_download_new_detail)
        row(R.string.pref_download_new_unread_chapters_only, R.string.pref_download_new_unread_chapters_only_detail)
        row(R.string.categories_title)
        row(R.string.auto_download_while_reading)
        row(R.string.pref_download_orphans)
        row(R.string.pref_export_downloads, R.string.pref_export_downloads_detail)
        row(R.string.pref_adopt_downloads, R.string.pref_adopt_downloads_detail)
    }
    section(SettingsSection.TRACKING) {
        row(R.string.tracking_trackers)
        row(R.string.tracking_behaviour)
        row(R.string.pref_auto_update_track, R.string.pref_auto_update_track_detail)
        row(R.string.pref_auto_update_track_on_mark_read)
    }
    section(SettingsSection.BROWSE) {
        row(R.string.pref_enabled_languages)
        row(R.string.pref_show_nsfw, R.string.pref_show_nsfw_detail)
        row(R.string.pref_hide_in_library, R.string.pref_hide_in_library_detail)
    }
    section(SettingsSection.DATA) {
        row(R.string.pref_storage_location, R.string.pref_storage_location_info)
        row(R.string.pref_backup_interval)
        row(R.string.pref_backup_slots)
        row(R.string.backup_create, R.string.more_backup_detail)
        row(R.string.library_export, R.string.library_export_detail)
        row(R.string.pref_storage_usage)
        row(R.string.storage_downloads_title)
        row(R.string.pref_auto_clear_chapter_cache, R.string.pref_auto_clear_chapter_cache_detail)
        row(R.string.pref_clear_chapter_cache)
    }
    section(SettingsSection.SYNC) {
        row(R.string.sync_backend)
        row(R.string.sync_last_sync)
        row(R.string.sync_now, R.string.sync_now_detail)
        row(R.string.sync_interval)
        row(R.string.sync_wifi_only, R.string.sync_wifi_only_detail)
        row(R.string.sync_fingerprint, R.string.sync_fingerprint_detail)
        row(R.string.sync_sign_out, R.string.sync_sign_out_detail)
        row(R.string.sync_report_last)
    }
    section(SettingsSection.SECURITY) {
        row(R.string.lock_with_biometrics, condition = SettingsSearchCondition.AUTHENTICATION)
        row(R.string.hide_notification_content)
        row(R.string.secure_screen, R.string.secure_screen_summary)
    }
    section(SettingsSection.ADVANCED) {
        row(R.string.settings_version)
        row(R.string.pref_app_update_check, R.string.pref_app_update_check_detail)
        row(R.string.pref_app_update_auto_check, R.string.pref_app_update_auto_check_detail)
        row(R.string.pref_auto_check_extensions, R.string.pref_auto_check_extensions_detail)
        row(R.string.pref_extension_lib_override)
        row(R.string.pref_battery_exemption, R.string.pref_battery_exemption_detail)
        row(R.string.crash_title, R.string.more_crash_detail)
        row(R.string.pref_verbose_logging, R.string.pref_verbose_logging_summary)
        row(R.string.pref_clear_database, R.string.pref_clear_database_summary)
        row(R.string.pref_rebuild_library_counts, R.string.pref_rebuild_library_counts_summary)
        row(R.string.pref_clear_webview_data)
        row(R.string.pref_dns_over_https)
        row(R.string.pref_user_agent_string)
        row(R.string.pref_reset_user_agent_string)
        row(R.string.pref_refresh_library_covers, R.string.pref_refresh_library_covers_summary)
        row(R.string.pref_reset_viewer_flags, R.string.pref_reset_viewer_flags_summary_synced)
        row(R.string.pref_update_library_manga_titles, R.string.pref_update_library_manga_titles_summary)
        row(R.string.pref_disallow_non_ascii_filenames, R.string.pref_disallow_non_ascii_filenames_details)
        row(R.string.pref_hardware_bitmap_threshold)
        row(R.string.ext_revoke_trust)
    }
    section(SettingsSection.EXPERIMENTAL) {
        row(R.string.settings_experimental_parallel_pages, R.string.settings_experimental_parallel_pages_summary)
        row(R.string.settings_experimental_custom_interval, R.string.settings_experimental_custom_interval_summary)
        row(R.string.settings_experimental_merged_entries, R.string.settings_experimental_merged_entries_summary)
        row(R.string.settings_experimental_auto_scroll, R.string.settings_experimental_auto_scroll_summary)
        row(R.string.settings_experimental_two_pages, R.string.settings_experimental_two_pages_summary)
        row(R.string.settings_experimental_search_links, R.string.settings_experimental_search_links_summary)
        row(R.string.settings_experimental_extension_auto_update, R.string.settings_experimental_extension_auto_update_summary)
        row(R.string.settings_experimental_parallel_sources, R.string.settings_experimental_parallel_sources_summary)
        row(R.string.settings_experimental_user_certificates, R.string.settings_experimental_user_certificates_summary)
        row(R.string.settings_experimental_save_as_cbz, R.string.settings_experimental_save_as_cbz_summary)
    }
}

private class SettingsSearchIndexScope(
    private val section: SettingsSection,
    @StringRes private val group: Int?,
    private val into: MutableList<SettingsSearchEntry>,
) {
    fun row(@StringRes title: Int, @StringRes summary: Int? = null, condition: SettingsSearchCondition? = null) {
        into += SettingsSearchEntry(section, title, group, summary, condition)
    }

    /** The group's heading, then its rows. */
    fun group(@StringRes heading: Int, rows: SettingsSearchIndexScope.() -> Unit) {
        row(heading)
        SettingsSearchIndexScope(section, heading, into).rows()
    }
}

private fun MutableList<SettingsSearchEntry>.section(
    section: SettingsSection,
    rows: SettingsSearchIndexScope.() -> Unit,
) = SettingsSearchIndexScope(section, null, this).rows()

/** An entry with its strings read, what the results list shows and [matchSettings] compares. */
internal class SettingsSearchItem(
    val entry: SettingsSearchEntry,
    val title: String,
    val summary: String?,
    /** The section, then the group when there is one: where the row is. */
    val breadcrumb: String,
)

internal fun SettingsSearchEntry.resolve(resources: Resources): SettingsSearchItem {
    val sectionLabel = resources.getString(section.labelRes)
    return SettingsSearchItem(
        entry = this,
        title = resources.getString(titleRes),
        summary = summaryRes?.let(resources::getString),
        breadcrumb = groupRes?.let {
            resources.getString(R.string.settings_search_breadcrumb_format, sectionLabel, resources.getString(it))
        } ?: sectionLabel,
    )
}

/**
 * The items [query] finds, case aside: those whose title holds every word of it, then those whose
 * title and summary hold them between them, each in [items]' order. Upstream matches the query as one
 * string; by words, "black dark" still finds "Pure black in dark mode". A blank query finds nothing.
 */
internal fun matchSettings(items: List<SettingsSearchItem>, query: String): List<SettingsSearchItem> {
    val words = query.split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val (inTitle, rest) = items.partition { item -> words.all { item.title.contains(it, ignoreCase = true) } }
    val withSummary = rest.filter { item ->
        words.all { word -> item.title.contains(word, ignoreCase = true) || item.summary?.contains(word, ignoreCase = true) == true }
    }
    return inTitle + withSummary
}

private val WHITESPACE = Regex("""\s+""")

/**
 * The row a result asked the section to show, and what to call once it has: [onShown] clears the
 * request, so the section shown again (after a rotation, or back from a screen a row opened) keeps
 * the scroll it was left at rather than jumping to the row a second time.
 */
internal class SettingsHighlight(val key: SettingsSearchKey, val onShown: () -> Unit)

/** The request for the section [SettingsSectionBody] is drawing, or null. */
internal val LocalSettingsHighlight = compositionLocalOf<SettingsHighlight?> { null }

/** The heading of the group the rows being drawn are in, which [SettingsSearchGroup] provides. */
private val LocalSettingsSearchGroup = staticCompositionLocalOf<Int?> { null }

/**
 * A searchable row, [titleRes] its title as the index has it. While [LocalSettingsHighlight] names
 * it, it is scrolled to the top of the section, as upstream scrolls its list to the item, and flashes
 * with upstream's `highlightBackground`; then the request is cleared. [content] is laid out as a
 * column, so a row of more than one part stays in its order.
 */
@Composable
internal fun SettingsSearchTarget(@StringRes titleRes: Int, content: @Composable ColumnScope.() -> Unit) {
    val group = LocalSettingsSearchGroup.current
    val highlight = LocalSettingsHighlight.current?.takeIf { it.key.titleRes == titleRes && it.key.groupRes == group }
    val latest by rememberUpdatedState(highlight)
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var flashing by remember { mutableStateOf(false) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    LaunchedEffect(highlight?.key) {
        val shown = latest ?: return@LaunchedEffect
        try {
            // Upstream's wait, for the screen's own entrance to finish first.
            delay(SCROLL_DELAY)
            // A rectangle from the row's top as tall as the window: brought into view, the column
            // scrolls the row to its top rather than just over its bottom edge, unless it runs out.
            requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), maxOf(size.height, windowHeight).toFloat()))
            flashing = true
            delay(FLASH_TIME)
        } finally {
            flashing = false
        }
        shown.onShown()
    }
    val background by animateColorAsState(
        targetValue = if (flashing) MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = if (flashing) {
            repeatable(
                iterations = 5,
                animation = tween(durationMillis = 200),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(offsetMillis = 600, offsetType = StartOffsetType.Delay),
            )
        } else {
            tween(200)
        },
        label = "settings_search_highlight",
    )
    Column(
        modifier = Modifier
            .bringIntoViewRequester(requester)
            .onSizeChanged { size = it }
            .background(background),
        content = content,
    )
}

/** A heading a search finds, such as Appearance's Theme over the theme picker. */
@Composable
internal fun SettingsSearchHeading(@StringRes headingRes: Int) {
    SettingsSearchTarget(headingRes) { HeadingItem(headingRes) }
}

/**
 * A group of rows under its heading, upstream's `PreferenceGroup`: the heading, then [content],
 * whose rows the search names by this group as well as by their titles. No layout of its own, so the
 * rows keep the section column's spacing.
 */
@Composable
internal fun SettingsSearchGroup(@StringRes headingRes: Int, content: @Composable () -> Unit) {
    SettingsSearchHeading(headingRes)
    CompositionLocalProvider(LocalSettingsSearchGroup provides headingRes, content = content)
}

private val SCROLL_DELAY = 500.milliseconds
private val FLASH_TIME = 3.seconds
