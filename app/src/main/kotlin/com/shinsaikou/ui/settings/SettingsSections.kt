package com.shinsaikou.ui.settings

import android.content.Context
import android.text.format.Formatter
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.appupdate.AppUpdateChecker
import com.shinsaikou.core.common.network.PREF_DOH_CUSTOM
import com.shinsaikou.core.common.network.customDohUrl
import com.shinsaikou.core.common.preferences.SecurityPreferences.SecureScreenMode
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.BackupPreferences
import com.shinsaikou.domain.preferences.FlashColor
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.domain.preferences.ReaderHideThreshold
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.TabletUiMode
import com.shinsaikou.domain.preferences.TappingInvertMode
import com.shinsaikou.domain.preferences.ThemeMode
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.image.GLUtil
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.theme.titleRes
import com.shinsaikou.reader.settings.ReaderSettingsOptions
import com.shinsaikou.reader.settings.titleRes
import com.shinsaikou.reader.ui.stringRes
import com.shinsaikou.ui.appupdate.AppUpdateViewModel
import com.shinsaikou.ui.components.CategoryFilterDialog
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.SettingsColumn
import com.shinsaikou.ui.components.categoryFilterLabel
import com.shinsaikou.ui.library.visualName
import com.shinsaikou.ui.main.AppRestart
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.storage.StorageLocationRows
import com.shinsaikou.ui.storage.StorageLocationViewModel
import com.shinsaikou.ui.storage.rememberStorageLocationPicker
import dev.zacsweers.metrox.viewmodel.metroViewModel
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.downloads.export.DownloadExportScheduler
import com.shinsaikou.ui.util.copyToClipboard
import com.shinsaikou.ui.util.relativeTimeSpanString
import eu.kanade.presentation.more.settings.widget.AppThemePreferenceWidget
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import java.text.NumberFormat
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.time.LocalDate

/**
 * One body per [SettingsSection]. Each reads what it needs off [SettingsViewModel] and owns no
 * state of its own, so the same body serves the phone screen and the tablet end pane. Sync and
 * Tracking are the exceptions: each body takes its own view model (`SyncSection.kt`,
 * `TrackingSection.kt`).
 */

private val libraryUpdateIntervals = listOf(0, 12, 24, 48)

/** Off, 6, 12, 24 and 48 hours, and weekly, as upstream offers them. */
internal val backupIntervals = listOf(0, 6, 12, 24, 48, WEEK_HOURS)

/** Upstream's range for the same key, so a restored value lands on the slider. */
private val parallelPageLimits = 1..15

/** Upstream's download ahead choices, next N unread chapters; 0 is Disabled (phase 3 unit AD). */
internal val autoDownloadWhileReadingChoices = listOf(0, 2, 3, 5, 10)

/**
 * Upstream's "After reading automatically delete" choices (phase 3 unit DR), each
 * `remove_after_read_slots` value with its label: -1 is Disabled, 0 the chapter just finished, and
 * each step one chapter further back.
 */
internal val removeAfterReadChoices = listOf(
    -1 to R.string.disabled,
    0 to R.string.last_read_chapter,
    1 to R.string.second_to_last,
    2 to R.string.third_to_last,
    3 to R.string.fourth_to_last,
    4 to R.string.fifth_to_last,
)

/**
 * The categories [excluded] names, in [categories]' order. An id no category has any more, left by
 * a restore from another library, is not shown; deleting a category takes its own id out.
 */
internal fun excludedCategories(categories: List<Category>, excluded: Set<String>): List<Category> =
    categories.filter { it.id.toString() in excluded }

/** Upstream's device restrictions for the automatic update, in its order, each with its label. */
internal val libraryUpdateDeviceRestrictionChoices = listOf(
    LibraryUpdateRestrictions.DEVICE_ONLY_ON_WIFI to R.string.connected_to_wifi,
    LibraryUpdateRestrictions.DEVICE_NETWORK_NOT_METERED to R.string.network_not_metered,
    LibraryUpdateRestrictions.DEVICE_CHARGING to R.string.charging,
)

/** Upstream's smart update, the entries an update skips, in its order, each with its label. */
internal val libraryUpdateSmartUpdateChoices = listOf(
    LibraryUpdateRestrictions.MANGA_HAS_UNREAD to R.string.pref_update_only_completely_read,
    LibraryUpdateRestrictions.MANGA_NON_READ to R.string.pref_update_only_started,
    LibraryUpdateRestrictions.MANGA_NON_COMPLETED to R.string.pref_update_only_non_completed,
    LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD to R.string.pref_update_only_in_release_period,
)

/** Upstream's default reading mode choices: every mode but the per-entry Default, in its order. */
internal val defaultReadingModeChoices: List<ReadingMode> = ReadingMode.entries - ReadingMode.DEFAULT

/** Upstream's default rotation choices: every orientation but the per-entry Default, in its order. */
internal val defaultRotationChoices: List<ReaderOrientation> = ReaderOrientation.entries - ReaderOrientation.DEFAULT

/** Upstream's double tap animation speeds, `pref_double_tap_anim_speed` values in its order. */
internal val doubleTapAnimSpeedChoices = listOf(
    1 to R.string.double_tap_anim_speed_0,
    500 to R.string.double_tap_anim_speed_normal,
    250 to R.string.double_tap_anim_speed_fast,
)

/** Upstream's reader backgrounds, `pref_reader_theme` values with their labels, in its order. */
internal val readerBackgroundChoices = listOf(
    1 to R.string.black_background,
    2 to R.string.gray_background,
    0 to R.string.white_background,
    3 to R.string.automatic_background,
)

/** The tap zones value that turns them off: the last of `ReaderSettingsOptions.TapZones`. */
internal const val DISABLED_TAP_ZONES = 5

/** Upstream's sensitivities for hiding the long strip's menu on scroll, in its order. */
internal val readerHideThresholdChoices = listOf(
    ReaderHideThreshold.HIGHEST,
    ReaderHideThreshold.HIGH,
    ReaderHideThreshold.LOW,
    ReaderHideThreshold.LOWEST,
)

/**
 * Upstream's default category choices, `default_category` values: always ask (the key's -1 default)
 * first, then every category in [categories]' order, the system one included.
 */
internal fun defaultCategoryChoices(categories: List<Category>): List<Int> =
    listOf(ALWAYS_ASK_CATEGORY) + categories.map { it.id.toInt() }

/** `default_category`'s "always ask", the key's default; no category has it. */
private const val ALWAYS_ASK_CATEGORY = -1

/** Upstream's chapter swipe choices for either side, in its order, each with its label (phase 4 unit CS). */
internal val chapterSwipeActionChoices = listOf(
    LibraryPreferences.ChapterSwipeAction.Disabled to R.string.disabled,
    LibraryPreferences.ChapterSwipeAction.ToggleBookmark to R.string.action_bookmark,
    LibraryPreferences.ChapterSwipeAction.ToggleRead to R.string.action_mark_as_read,
    LibraryPreferences.ChapterSwipeAction.Download to R.string.action_download,
)

/** Upstream's mark duplicate read chapter as read, the moments it applies, in its order. */
internal val markDuplicateReadChapterChoices = listOf(
    LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING to R.string.pref_mark_duplicate_read_chapter_read_existing,
    LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW to R.string.pref_mark_duplicate_read_chapter_read_new,
)

/** Upstream's date formats, `app_date_format` values in its order; empty is the locale's own. */
internal val dateFormatChoices = listOf(
    "",
    "MM/dd/yy",
    "dd/MM/yy",
    "yyyy-MM-dd",
    "dd MMM yyyy",
    "MMM dd, yyyy",
)

/**
 * Upstream's DNS over HTTPS choices, `doh_provider` values in its order, each with the provider's name
 * as upstream writes it; -1, the key's default, is Disabled and has no name. Saikou's Custom comes
 * last, also with no name: the row labels it and asks for the server's URL.
 */
internal val dohProviderChoices: List<Pair<Int, String?>> = listOf(
    -1 to null,
    PREF_DOH_CLOUDFLARE to "Cloudflare",
    PREF_DOH_GOOGLE to "Google",
    PREF_DOH_ADGUARD to "AdGuard",
    PREF_DOH_QUAD9 to "Quad9",
    PREF_DOH_ALIDNS to "AliDNS",
    PREF_DOH_DNSPOD to "DNSPod",
    PREF_DOH_360 to "360",
    PREF_DOH_QUAD101 to "Quad 101",
    PREF_DOH_MULLVAD to "Mullvad",
    PREF_DOH_CONTROLD to "Control D",
    PREF_DOH_NJALLA to "Njalla",
    PREF_DOH_SHECAN to "Shecan",
    PREF_DOH_CUSTOM to null,
)

/** Upstream's Lock when idle choices, `lock_app_after` minutes in its order: 0 is Always, -1 Never. */
internal val lockAppAfterChoices = listOf(0, 1, 2, 5, 10, -1)

/**
 * Which of the Security section's two lock rows are on screen: Require unlock while the device can
 * authenticate, Lock when idle while it can and the lock is on. Upstream's `SettingsSecurityScreen`
 * sets `enabled` on these two rows and its `StatusWrapper` hides a row that is not, where Saikou's
 * other conditional rows are drawn dimmed (phase 4 unit MP1).
 */
internal data class LockRows(val requireUnlock: Boolean, val lockWhenIdle: Boolean)

internal fun lockRows(authSupported: Boolean, lockOn: Boolean): LockRows =
    LockRows(requireUnlock = authSupported, lockWhenIdle = authSupported && lockOn)

/** Upstream's Secure screen choices, `secure_screen_v2` values in its order. */
internal val secureScreenChoices: List<SecureScreenMode> = SecureScreenMode.entries

/** A Secure screen choice's label, upstream's `titleRes` for the value. */
@get:StringRes
internal val SecureScreenMode.labelRes: Int
    get() = when (this) {
        SecureScreenMode.ALWAYS -> R.string.lock_always
        SecureScreenMode.INCOGNITO -> R.string.pref_incognito_mode
        SecureScreenMode.NEVER -> R.string.lock_never
    }

/**
 * Upstream's confirmation before a lock setting or the NSFW row changes: the lock prompt titled
 * [titleRes], over the activity the screen is composed in, answering true once the owner has
 * authenticated, or at once on a device with no lock set.
 */
@Composable
private fun rememberAuthenticate(@StringRes titleRes: Int): suspend () -> Boolean {
    val activity = LocalActivity.current
    val title = stringResource(titleRes)
    return remember(activity, title) { { (activity as FragmentActivity).authenticate(title = title) } }
}

/**
 * A hardware bitmap threshold as upstream's list names it: the first of [thresholds], the device's own
 * limit, as its default, and every other as the number.
 */
@Composable
private fun hardwareBitmapThresholdLabel(thresholds: List<Int>, threshold: Int): String =
    if (thresholds.indexOf(threshold) == 0) {
        stringResource(R.string.pref_hardware_bitmap_threshold_default, threshold)
    } else {
        threshold.toString()
    }

/** Upstream offers the device restrictions only while an automatic interval is set. */
internal fun deviceRestrictionsEnabled(intervalHours: Int): Boolean = intervalHours > 0

/** [this] with [key] added, or removed when it was there: one chip of a multiple choice row. */
internal fun Set<String>.toggled(key: String): Set<String> = if (key in this) this - key else this + key

/** Material's alpha for a control that is shown but cannot be used. */
internal const val DISABLED_ALPHA = 0.38f

/**
 * `SettingsSection` is a navigation argument, so `:app`'s navigation package owns it while the
 * label belongs to the screen, the same split [com.shinsaikou.presentation.core.theme.titleRes] makes
 * for the theme enums. A new case without a label here is a compile error, not a blank row.
 */
@get:StringRes
internal val SettingsSection.labelRes: Int
    get() = when (this) {
        SettingsSection.APPEARANCE -> R.string.settings_section_appearance
        SettingsSection.LIBRARY -> R.string.settings_section_library
        SettingsSection.READER -> R.string.settings_section_reader
        SettingsSection.DOWNLOADS -> R.string.settings_section_downloads
        SettingsSection.TRACKING -> R.string.settings_section_tracking
        SettingsSection.BROWSE -> R.string.settings_section_browse
        SettingsSection.DATA -> R.string.settings_section_data
        SettingsSection.SYNC -> R.string.settings_section_sync
        SettingsSection.SECURITY -> R.string.settings_section_security
        SettingsSection.ADVANCED -> R.string.settings_section_advanced
        SettingsSection.EXPERIMENTAL -> R.string.settings_section_experimental
    }

/** The line under [labelRes] in the settings list: a few of the things the section holds. */
@get:StringRes
internal val SettingsSection.summaryRes: Int
    get() = when (this) {
        SettingsSection.APPEARANCE -> R.string.settings_section_appearance_summary
        SettingsSection.LIBRARY -> R.string.settings_section_library_summary
        SettingsSection.READER -> R.string.settings_section_reader_summary
        SettingsSection.DOWNLOADS -> R.string.settings_section_downloads_summary
        SettingsSection.TRACKING -> R.string.settings_section_tracking_summary
        SettingsSection.BROWSE -> R.string.settings_section_browse_summary
        SettingsSection.DATA -> R.string.settings_section_data_summary
        SettingsSection.SYNC -> R.string.settings_section_sync_summary
        SettingsSection.SECURITY -> R.string.settings_section_security_summary
        SettingsSection.ADVANCED -> R.string.settings_section_advanced_summary
        SettingsSection.EXPERIMENTAL -> R.string.settings_section_experimental_summary
    }

/** The row's icon in the settings list, upstream's for each section it has; Sync and Experimental are Saikou's own. */
internal val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.APPEARANCE -> Icons.Outlined.Palette
        SettingsSection.LIBRARY -> Icons.Outlined.CollectionsBookmark
        SettingsSection.READER -> Icons.AutoMirrored.Outlined.ChromeReaderMode
        SettingsSection.DOWNLOADS -> Icons.Outlined.GetApp
        SettingsSection.TRACKING -> Icons.Outlined.Sync
        SettingsSection.BROWSE -> Icons.Outlined.Explore
        SettingsSection.DATA -> Icons.Outlined.Storage
        SettingsSection.SYNC -> Icons.Outlined.CloudSync
        SettingsSection.SECURITY -> Icons.Outlined.Security
        SettingsSection.ADVANCED -> Icons.Outlined.Code
        SettingsSection.EXPERIMENTAL -> Icons.Outlined.Science
    }

/**
 * The sections the settings list shows, in their order: every one, except Tracking while no
 * configured tracker is in the graph ([trackingOffered] false). A tracker whose build-time key is
 * blank stays in the graph for restored rows and the backup validator, but has no sign-in to offer,
 * so it does not count (phase 3 unit T1). A new section is a compile error here until it says when
 * it shows.
 */
internal fun shownSettingsSections(trackingOffered: Boolean): List<SettingsSection> =
    SettingsSection.entries.filter { section ->
        when (section) {
            SettingsSection.TRACKING -> trackingOffered
            SettingsSection.APPEARANCE,
            SettingsSection.LIBRARY,
            SettingsSection.READER,
            SettingsSection.DOWNLOADS,
            SettingsSection.BROWSE,
            SettingsSection.DATA,
            SettingsSection.SYNC,
            SettingsSection.SECURITY,
            SettingsSection.ADVANCED,
            SettingsSection.EXPERIMENTAL,
            -> true
        }
    }

/**
 * The tag [SettingsSectionBody] puts on its root. A device test needs it to tell which section the
 * tablet's end pane is holding: the list stays beside the pane showing every section's label, so
 * matching a label proves the row exists and nothing about what the pane swapped to.
 */
internal fun settingsSectionBodyTag(section: SettingsSection): String = "settings_section_body_${section.name}"

/**
 * [showMessage] puts a line on the Settings screen's snackbar (plan 3.1 item 12), for the feedback a
 * row gives where upstream toasts it.
 */
@Composable
internal fun SettingsSectionBody(
    section: SettingsSection,
    viewModel: SettingsViewModel,
    showMessage: (String, SnackbarAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag(settingsSectionBodyTag(section))
            .verticalScroll(rememberScrollState()),
    ) {
        when (section) {
            SettingsSection.APPEARANCE -> AppearanceSection(viewModel)
            SettingsSection.LIBRARY -> LibrarySection(viewModel)
            SettingsSection.READER -> ReaderSection(viewModel)
            SettingsSection.DOWNLOADS -> DownloadsSection(viewModel)
            // Its own view model too, so this one takes on none of the trackers' sign-in.
            SettingsSection.TRACKING -> TrackingSection()
            SettingsSection.BROWSE -> BrowseSection(viewModel)
            SettingsSection.DATA -> DataSection(viewModel, showMessage)
            // Its own view model, so this one takes on none of sync's classes.
            SettingsSection.SYNC -> SyncSection()
            SettingsSection.SECURITY -> SecuritySection(viewModel)
            SettingsSection.ADVANCED -> AdvancedSection(viewModel, showMessage)
            SettingsSection.EXPERIMENTAL -> ExperimentalSection(viewModel)
        }
    }
}

@Composable
private fun AppearanceSection(viewModel: SettingsViewModel) {
    val preferences = viewModel.uiPreferences
    val appTheme by preferences.appTheme.collectAsState()
    val themeMode by preferences.themeMode.collectAsState()
    val amoled by preferences.themeDarkAmoled.collectAsState()
    val tabletUiMode by preferences.tabletUiMode.collectAsState()
    val dateFormat by preferences.dateFormat.collectAsState()
    // Each choice shows today's date in it, as upstream's list does.
    val today = remember { LocalDate.now() }

    // Each preview phone is drawn in the mode the app is in, resolved as SaikouTheme resolves it.
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    SettingsColumn {
        // Upstream's theme picker, a row of preview phones each drawn in its own theme. The app
        // takes the new theme from MainViewModel and recomposes, so upstream's recreate is not needed.
        HeadingItem(R.string.settings_theme)
        AppThemePreferenceWidget(
            value = appTheme,
            amoled = amoled,
            isDark = isDark,
            onItemClick = preferences.appTheme::set,
        )
        SettingsChipRow(R.string.settings_theme_mode) {
            ThemeMode.entries.forEach { choice ->
                FilterChip(
                    selected = choice == themeMode,
                    onClick = { preferences.themeMode.set(choice) },
                    label = { Text(stringResource(choice.titleRes)) },
                )
            }
        }
        CheckboxRow(
            labelRes = R.string.pref_amoled,
            detailRes = R.string.pref_amoled_detail,
            pref = preferences.themeDarkAmoled,
        )
        SettingsChipRow(R.string.settings_tablet_ui) {
            TabletUiMode.entries.forEach { choice ->
                FilterChip(
                    selected = choice == tabletUiMode,
                    onClick = { preferences.tabletUiMode.set(choice) },
                    label = { Text(stringResource(choice.titleRes)) },
                )
            }
        }
        // Phase 4 unit SR: upstream's date format row, after the tablet layout row as in its display group.
        ChoiceRow(
            labelRes = R.string.pref_date_format,
            choices = dateFormatChoices,
            selected = { it == dateFormat },
            label = { format ->
                stringResource(
                    R.string.pref_date_format_choice_format,
                    format.ifEmpty { stringResource(R.string.label_default) },
                    UiPreferences.dateFormat(format).format(today),
                )
            },
            onSelect = preferences.dateFormat::set,
        )
        // Phase 4 unit UG: upstream's Relative timestamps row, after the date format as in its display
        // group, its summary showing today's date in the chosen format. It changes the Updates day
        // headers, the one place Saikou shows upstream's relative dates.
        CheckboxRow(
            labelRes = R.string.pref_relative_format,
            pref = preferences.relativeTime,
            detail = stringResource(
                R.string.pref_relative_format_summary,
                stringResource(R.string.relative_time_today),
                UiPreferences.dateFormat(dateFormat).format(today),
            ),
        )
    }
}

@Composable
private fun LibrarySection(viewModel: SettingsViewModel) {
    val preferences = viewModel.libraryPreferences
    val updateInterval by preferences.autoUpdateInterval.collectAsState()
    val lastUpdated by preferences.lastUpdatedTimestamp.collectAsState()
    val deviceRestrictions by preferences.autoUpdateDeviceRestrictions.collectAsState()
    val smartUpdate by preferences.autoUpdateMangaRestrictions.collectAsState()
    val updateIncluded by preferences.updateCategories.collectAsState()
    val updateExcluded by preferences.updateCategoriesExclude.collectAsState()
    val defaultCategory by preferences.defaultCategory.collectAsState()
    val categorizedSort by preferences.categorizedDisplaySettings.collectAsState()
    val markDuplicateRead by preferences.markDuplicateReadChapterAsRead.collectAsState()
    val swipeToStart by preferences.swipeToStartAction.collectAsState()
    val swipeToEnd by preferences.swipeToEndAction.collectAsState()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingCategories by remember { mutableStateOf(false) }

    SettingsColumn {
        CheckboxRow(
            labelRes = R.string.pref_unread_badge,
            detailRes = R.string.pref_unread_badge_detail,
            pref = preferences.unreadBadge,
        )
        CheckboxRow(
            labelRes = R.string.pref_download_badge,
            detailRes = R.string.pref_download_badge_detail,
            pref = preferences.downloadBadge,
        )
        CheckboxRow(
            labelRes = R.string.pref_manual_sort,
            detailRes = R.string.pref_manual_sort_detail,
            pref = preferences.manualSortEnabled,
        )
        // Phase 4 unit SR: upstream's two category rows, then its update group, then its duplicate row.
        ChoiceRow(
            labelRes = R.string.default_category,
            choices = defaultCategoryChoices(state.categories),
            selected = { it == defaultCategory },
            label = { id ->
                state.categories.firstOrNull { it.id.toInt() == id }?.visualName
                    ?: stringResource(R.string.default_category_summary)
            },
            onSelect = preferences.defaultCategory::set,
        )
        CheckboxItem(
            label = stringResource(R.string.categorized_display_settings),
            checked = categorizedSort,
            onClick = { viewModel.setCategorizedDisplaySettings(!categorizedSort) },
        )
        IntervalRow(
            labelRes = R.string.pref_library_update_interval,
            options = libraryUpdateIntervals,
            selectedHours = updateInterval,
            onSelect = viewModel::setAutoUpdateInterval,
        )
        // Upstream's Updates line, here too, so a schedule that stopped running shows where it is set.
        InlineNotice(stringResource(R.string.updates_last_update_info, relativeTimeSpanString(lastUpdated)))
        // Phase 3 unit AD: upstream's global update rows, in its order around the metadata row.
        ChoiceSetRow(
            labelRes = R.string.pref_library_update_restriction,
            choices = libraryUpdateDeviceRestrictionChoices,
            selected = deviceRestrictions,
            enabled = deviceRestrictionsEnabled(updateInterval),
            onChange = viewModel::setAutoUpdateDeviceRestrictions,
        )
        ListRow(
            title = stringResource(R.string.categories_title),
            detail = categoryFilterLabel(state.categories, updateIncluded, updateExcluded),
            onClick = { editingCategories = true },
        )
        CheckboxRow(
            labelRes = R.string.pref_library_update_metadata,
            detailRes = R.string.pref_library_update_metadata_detail,
            pref = preferences.autoUpdateMetadata,
        )
        ChoiceSetRow(
            labelRes = R.string.pref_library_update_smart_update,
            choices = libraryUpdateSmartUpdateChoices,
            selected = smartUpdate,
            onChange = preferences.autoUpdateMangaRestrictions::set,
        )
        // Phase 4 unit CS: upstream's two swipe rows, first in its behaviour group, before the duplicate row.
        ChoiceRow(
            labelRes = R.string.pref_chapter_swipe_start,
            choices = chapterSwipeActionChoices,
            selected = { (action, _) -> action == swipeToStart },
            label = { (_, label) -> stringResource(label) },
            onSelect = { (action, _) -> preferences.swipeToStartAction.set(action) },
        )
        ChoiceRow(
            labelRes = R.string.pref_chapter_swipe_end,
            choices = chapterSwipeActionChoices,
            selected = { (action, _) -> action == swipeToEnd },
            label = { (_, label) -> stringResource(label) },
            onSelect = { (action, _) -> preferences.swipeToEndAction.set(action) },
        )
        ChoiceSetRow(
            labelRes = R.string.pref_mark_duplicate_read_chapter_read,
            choices = markDuplicateReadChapterChoices,
            selected = markDuplicateRead,
            onChange = preferences.markDuplicateReadChapterAsRead::set,
        )
    }

    if (editingCategories) {
        CategoryFilterDialog(
            message = stringResource(R.string.pref_library_update_categories_details),
            categories = state.categories,
            included = updateIncluded,
            excluded = updateExcluded,
            onDismissRequest = { editingCategories = false },
            onConfirm = { included, excluded ->
                viewModel.setUpdateCategories(included, excluded)
                editingCategories = false
            },
        )
    }
}

/**
 * Upstream's reader rows (phase 4 unit SR) around the three Saikou already had, in the order of
 * upstream's reader screen: its top rows, then its display, reading, long strip, navigation and
 * actions groups, each row over the key the reader already reads. The three Saikou had take upstream's
 * display group order too, Fullscreen, Keep screen on, Show page number (phase 4 unit MP1).
 */
/**
 * Upstream's Settings > Reader, in its groups: the general rows, then Display, E-Ink, Reading, Paged,
 * Long strip, Navigation and Actions. Every row here is also in the reader's own settings sheet,
 * which shows only the reading mode it is open in; here they can be set without opening a chapter.
 * A row that depends on another is shown greyed out while that one is off, as volume keys' inverted
 * row already was, where upstream hides it, except the sliders, which have no disabled look and are
 * shown only while they apply.
 */
@Composable
private fun ReaderSection(viewModel: SettingsViewModel) {
    val preferences = viewModel.readerPreferences
    val defaultReadingMode by preferences.defaultReadingMode.collectAsState()
    val doubleTapAnimSpeed by preferences.doubleTapAnimSpeed.collectAsState()
    SettingsColumn {
        ChoiceRow(
            labelRes = R.string.pref_viewer_type,
            choices = defaultReadingModeChoices,
            selected = { it.flagValue == defaultReadingMode },
            label = { stringResource(it.stringRes) },
            onSelect = { preferences.defaultReadingMode.set(it.flagValue) },
        )
        ChoiceRow(
            labelRes = R.string.pref_double_tap_anim_speed,
            choices = doubleTapAnimSpeedChoices,
            selected = { (speed, _) -> speed == doubleTapAnimSpeed },
            label = { (_, label) -> stringResource(label) },
            onSelect = { (speed, _) -> preferences.doubleTapAnimSpeed.set(speed) },
        )
        CheckboxRow(
            labelRes = R.string.pref_show_reading_mode,
            detailRes = R.string.pref_show_reading_mode_summary,
            pref = preferences.showReadingMode,
        )
        CheckboxRow(
            labelRes = R.string.pref_show_navigation_mode,
            detailRes = R.string.pref_show_navigation_mode_summary,
            pref = preferences.showNavigationOverlayOnStart,
        )
        CheckboxRow(labelRes = R.string.pref_page_transitions, pref = preferences.pageTransitions)

        ReaderDisplayRows(preferences)
        ReaderEInkRows(preferences)

        HeadingItem(R.string.pref_category_reading)
        CheckboxRow(labelRes = R.string.pref_skip_read_chapters, pref = preferences.skipRead)
        CheckboxRow(labelRes = R.string.pref_skip_filtered_chapters, pref = preferences.skipFiltered)
        CheckboxRow(labelRes = R.string.pref_skip_dupe_chapters, pref = preferences.skipDupe)
        CheckboxRow(
            labelRes = R.string.pref_always_show_chapter_transition,
            pref = preferences.alwaysShowChapterTransition,
        )

        ReaderPagedRows(preferences)
        ReaderLongStripRows(preferences)
        ReaderNavigationRows(preferences)

        HeadingItem(R.string.pref_reader_actions)
        CheckboxRow(labelRes = R.string.pref_read_with_long_tap, pref = preferences.readWithLongTap)
        CheckboxRow(
            labelRes = R.string.pref_create_folder_per_manga,
            detailRes = R.string.pref_create_folder_per_manga_summary,
            pref = preferences.folderPerManga,
        )
    }
}

@Composable
private fun ReaderDisplayRows(preferences: ReaderPreferences) {
    val defaultRotation by preferences.defaultOrientationType.collectAsState()
    val readerTheme by preferences.readerTheme.collectAsState()
    val fullscreen by preferences.fullscreen.collectAsState()
    HeadingItem(R.string.pref_category_display)
    ChoiceRow(
        labelRes = R.string.pref_rotation_type,
        choices = defaultRotationChoices,
        selected = { it.flagValue == defaultRotation },
        label = { stringResource(it.stringRes) },
        onSelect = { preferences.defaultOrientationType.set(it.flagValue) },
    )
    ChoiceRow(
        labelRes = R.string.pref_reader_theme,
        choices = readerBackgroundChoices,
        selected = { (value, _) -> value == readerTheme },
        label = { (_, label) -> stringResource(label) },
        onSelect = { (value, _) -> preferences.readerTheme.set(value) },
    )
    CheckboxRow(
        labelRes = R.string.pref_fullscreen,
        detailRes = R.string.pref_fullscreen_detail,
        pref = preferences.fullscreen,
    )
    // Upstream shows it only on a display with a cutout; only fullscreen draws under one.
    if (LocalView.current.hasDisplayCutout()) {
        CheckboxRow(labelRes = R.string.pref_cutout_short, pref = preferences.drawUnderCutout, enabled = fullscreen)
    }
    CheckboxRow(
        labelRes = R.string.pref_keep_screen_on,
        detailRes = R.string.pref_keep_screen_on_detail,
        pref = preferences.keepScreenOn,
    )
    CheckboxRow(
        labelRes = R.string.pref_show_page_number,
        detailRes = R.string.pref_show_page_number_detail,
        pref = preferences.showPageNumber,
    )
}

@Composable
private fun ReaderEInkRows(preferences: ReaderPreferences) {
    val flash by preferences.flashOnPageChange.collectAsState()
    val flashMillis by preferences.flashDurationMillis.collectAsState()
    val flashInterval by preferences.flashPageInterval.collectAsState()
    val flashColor by preferences.flashColor.collectAsState()
    HeadingItem(R.string.pref_category_eink)
    CheckboxRow(
        labelRes = R.string.pref_flash_page,
        detailRes = R.string.pref_flash_page_summ,
        pref = preferences.flashOnPageChange,
    )
    ShownWhen(flash) {
        Column {
            SliderItem(
                value = flashMillis / ReaderSettingsOptions.MILLI_CONVERSION,
                valueRange = 1..15,
                label = stringResource(R.string.pref_flash_duration),
                valueString = stringResource(R.string.pref_flash_duration_summary, flashMillis),
                onChange = { preferences.flashDurationMillis.set(it * ReaderSettingsOptions.MILLI_CONVERSION) },
            )
            SliderItem(
                value = flashInterval,
                valueRange = 1..10,
                label = stringResource(R.string.pref_flash_page_interval),
                valueString = pluralStringResource(R.plurals.pref_pages, flashInterval, flashInterval),
                onChange = preferences.flashPageInterval::set,
            )
            ChoiceRow(
                labelRes = R.string.pref_flash_with,
                choices = FlashColor.entries,
                selected = { it == flashColor },
                label = { stringResource(it.titleRes) },
                onSelect = preferences.flashColor::set,
            )
        }
    }
}

@Composable
private fun ReaderPagedRows(preferences: ReaderPreferences) {
    val navigationMode by preferences.navigationModePager.collectAsState()
    val navInverted by preferences.pagerNavInverted.collectAsState()
    val imageScaleType by preferences.imageScaleType.collectAsState()
    val zoomStart by preferences.zoomStart.collectAsState()
    val dualPageSplit by preferences.dualPageSplitPaged.collectAsState()
    val rotateToFit by preferences.dualPageRotateToFit.collectAsState()
    HeadingItem(R.string.pager_viewer)
    TapZoneRows(
        selected = navigationMode,
        onSelect = preferences.navigationModePager::set,
        invertMode = navInverted,
        onSelectInvertMode = preferences.pagerNavInverted::set,
    )
    ChoiceRow(
        labelRes = R.string.pref_image_scale_type,
        // The stored value is the index plus one (ReaderSettingsOptions).
        choices = ReaderSettingsOptions.ImageScaleType.indices.toList(),
        selected = { it + 1 == imageScaleType },
        label = { stringResource(ReaderSettingsOptions.ImageScaleType[it]) },
        onSelect = { preferences.imageScaleType.set(it + 1) },
    )
    ChoiceRow(
        labelRes = R.string.pref_zoom_start,
        choices = ReaderSettingsOptions.ZoomStart.indices.toList(),
        selected = { it + 1 == zoomStart },
        label = { stringResource(ReaderSettingsOptions.ZoomStart[it]) },
        onSelect = { preferences.zoomStart.set(it + 1) },
    )
    CheckboxRow(labelRes = R.string.pref_crop_borders, pref = preferences.cropBorders)
    CheckboxRow(labelRes = R.string.pref_landscape_zoom, pref = preferences.landscapeZoom)
    CheckboxRow(labelRes = R.string.pref_navigate_pan, pref = preferences.navigateToPan)
    CheckboxRow(labelRes = R.string.pref_dual_page_split, pref = preferences.dualPageSplitPaged)
    CheckboxRow(
        labelRes = R.string.pref_dual_page_invert,
        detailRes = R.string.pref_dual_page_invert_summary,
        pref = preferences.dualPageInvertPaged,
        enabled = dualPageSplit,
    )
    CheckboxRow(labelRes = R.string.pref_page_rotate, pref = preferences.dualPageRotateToFit)
    CheckboxRow(
        labelRes = R.string.pref_page_rotate_invert,
        pref = preferences.dualPageRotateToFitInvert,
        enabled = rotateToFit,
    )
}

@Composable
private fun ReaderLongStripRows(preferences: ReaderPreferences) {
    val navigationMode by preferences.navigationModeWebtoon.collectAsState()
    val navInverted by preferences.webtoonNavInverted.collectAsState()
    val sidePadding by preferences.webtoonSidePadding.collectAsState()
    val hideThreshold by preferences.readerHideThreshold.collectAsState()
    val dualPageSplit by preferences.dualPageSplitWebtoon.collectAsState()
    val rotateToFit by preferences.dualPageRotateToFitWebtoon.collectAsState()
    val percent = remember { NumberFormat.getPercentInstance() }
    HeadingItem(R.string.webtoon_viewer)
    TapZoneRows(
        selected = navigationMode,
        onSelect = preferences.navigationModeWebtoon::set,
        invertMode = navInverted,
        onSelectInvertMode = preferences.webtoonNavInverted::set,
    )
    SliderItem(
        value = sidePadding,
        valueRange = ReaderSettingsOptions.WEBTOON_PADDING_MIN..ReaderSettingsOptions.WEBTOON_PADDING_MAX,
        label = stringResource(R.string.pref_webtoon_side_padding),
        valueString = percent.format(sidePadding / 100f),
        onChange = preferences.webtoonSidePadding::set,
    )
    ChoiceRow(
        labelRes = R.string.pref_hide_threshold,
        choices = readerHideThresholdChoices,
        selected = { it == hideThreshold },
        label = { stringResource(it.titleRes) },
        onSelect = preferences.readerHideThreshold::set,
    )
    CheckboxRow(labelRes = R.string.pref_crop_borders, pref = preferences.cropBordersWebtoon)
    CheckboxRow(labelRes = R.string.pref_dual_page_split, pref = preferences.dualPageSplitWebtoon)
    CheckboxRow(
        labelRes = R.string.pref_dual_page_invert,
        detailRes = R.string.pref_dual_page_invert_summary,
        pref = preferences.dualPageInvertWebtoon,
        enabled = dualPageSplit,
    )
    CheckboxRow(labelRes = R.string.pref_page_rotate, pref = preferences.dualPageRotateToFitWebtoon)
    CheckboxRow(
        labelRes = R.string.pref_page_rotate_invert,
        pref = preferences.dualPageRotateToFitInvertWebtoon,
        enabled = rotateToFit,
    )
    CheckboxRow(labelRes = R.string.pref_double_tap_zoom, pref = preferences.webtoonDoubleTapZoomEnabled)
    CheckboxRow(labelRes = R.string.pref_webtoon_disable_zoom_out, pref = preferences.webtoonDisableZoomOut)
}

@Composable
private fun ReaderNavigationRows(preferences: ReaderPreferences) {
    val volumeKeys by preferences.readWithVolumeKeys.collectAsState()
    val verticalModes by preferences.verticalNavigator.collectAsState()
    val navigatorHeight by preferences.verticalNavigatorHeight.collectAsState()
    HeadingItem(R.string.pref_reader_navigation)
    CheckboxRow(labelRes = R.string.pref_read_with_volume_keys, pref = preferences.readWithVolumeKeys)
    CheckboxRow(
        labelRes = R.string.pref_read_with_volume_keys_inverted,
        pref = preferences.readWithVolumeKeysInverted,
        enabled = volumeKeys,
    )
    // By name, as the reader reads the set (ReaderChrome.chapterNavigatorType).
    ChoiceSetRow(
        labelRes = R.string.pref_vertical_navigator,
        choices = defaultReadingModeChoices.map { it.name to it.stringRes },
        selected = verticalModes,
        onChange = preferences.verticalNavigator::set,
    )
    CheckboxRow(
        labelRes = R.string.pref_webtoon_vertical_navigator_on_left,
        pref = preferences.verticalNavigatorOnLeft,
        enabled = verticalModes.isNotEmpty(),
    )
    ShownWhen(verticalModes.isNotEmpty()) {
        SliderItem(
            value = navigatorHeight,
            valueRange = 65..100,
            steps = 6,
            label = stringResource(R.string.pref_vertical_navigator_height),
            onChange = preferences.verticalNavigatorHeight::set,
        )
    }
}

/**
 * One viewer's tap zones and their inversion, as the reader's sheet draws them; the inversion is
 * greyed out while the zones are off ([DISABLED_TAP_ZONES]).
 */
@Composable
private fun TapZoneRows(
    selected: Int,
    onSelect: (Int) -> Unit,
    invertMode: TappingInvertMode,
    onSelectInvertMode: (TappingInvertMode) -> Unit,
) {
    ChoiceRow(
        labelRes = R.string.pref_viewer_nav,
        choices = ReaderSettingsOptions.TapZones.indices.toList(),
        selected = { it == selected },
        label = { stringResource(ReaderSettingsOptions.TapZones[it]) },
        onSelect = onSelect,
    )
    ChoiceRow(
        labelRes = R.string.pref_read_with_tapping_inverted,
        choices = TappingInvertMode.entries,
        selected = { it == invertMode },
        label = { stringResource(it.titleRes) },
        enabled = selected != DISABLED_TAP_ZONES,
        onSelect = onSelectInvertMode,
    )
}

@Composable
private fun BrowseSection(viewModel: SettingsViewModel) {
    val preferences = viewModel.sourcePreferences
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabledLanguages by preferences.enabledLanguages.collectAsState()
    var editingLanguages by remember { mutableStateOf(false) }

    SettingsColumn {
        ListRow(
            title = stringResource(R.string.pref_enabled_languages),
            // The candidates' order, so the summary reads the way the dialog lists them.
            detail = state.languageCandidates.filter { it in enabledLanguages }
                .map { enabledLanguageLabel(it) }
                .ifEmpty { listOf(stringResource(R.string.pref_enabled_languages_none)) }
                .joinToString(),
            onClick = { editingLanguages = true },
        )
        // Phase 4 unit AL: upstream's prompt before the row flips, titled as upstream's is.
        CheckboxRow(
            labelRes = R.string.pref_show_nsfw,
            detailRes = R.string.pref_show_nsfw_detail,
            pref = preferences.showNsfwSource,
            confirm = rememberAuthenticate(R.string.pref_category_nsfw_content),
        )
        CheckboxRow(
            labelRes = R.string.pref_hide_in_library,
            detailRes = R.string.pref_hide_in_library_detail,
            pref = preferences.hideInLibraryItems,
        )
    }

    if (editingLanguages) {
        EnabledLanguagesDialog(
            candidates = state.languageCandidates,
            enabled = enabledLanguages,
            onDismissRequest = { editingLanguages = false },
            onConfirm = {
                preferences.enabledLanguages.set(it)
                editingLanguages = false
            },
        )
    }
}

/**
 * The two download keys the engine reads (phase 2 decision Q5): the network constraint and the
 * pages in flight per chapter. Then upstream's "Delete chapters" group (phase 3 unit DR), where upstream
 * places it, and upstream's auto-download rows (phase 3 unit AD): new chapters, with
 * the two rows that only apply while it is on, and download ahead with upstream's note. Below them, the downloads whose chapter row is
 * gone or soft deleted and that the sweep's re-match could not match to a current chapter (phase 2
 * decision Q7), with a delete behind a confirmation that names them, because the files may not be
 * downloadable again. Last, Export downloads into the storage location (unit storage-location), and
 * the adoption of a downloads folder, upstream's or a Saikou export (phase 2 decision Q6(a): this row is
 * its one entry point), with the summary of the last run.
 */
@Composable
private fun DownloadsSection(viewModel: SettingsViewModel) {
    val parallelPageLimit by viewModel.downloadPreferences.parallelPageLimit.collectAsState()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }
    val downloadNew by viewModel.downloadPreferences.downloadNewChapters.collectAsState()
    val newIncluded by viewModel.downloadPreferences.downloadNewChapterCategories.collectAsState()
    val newExcluded by viewModel.downloadPreferences.downloadNewChapterCategoriesExclude.collectAsState()
    val downloadAhead by viewModel.downloadPreferences.autoDownloadWhileReading.collectAsState()
    var editingNewCategories by remember { mutableStateOf(false) }
    val removeAfterRead by viewModel.downloadPreferences.removeAfterReadSlots.collectAsState()
    val removeExcluded by viewModel.downloadPreferences.removeExcludeCategories.collectAsState()
    var editingRemoveExcluded by remember { mutableStateOf(false) }

    // Counted each time the section is shown, since a library update can orphan a download.
    LaunchedEffect(viewModel) { viewModel.refreshOrphanDownloads() }

    val storageLocation: StorageLocationViewModel = metroViewModel()
    val storageState by storageLocation.state.collectAsStateWithLifecycle()
    val pickThenExport = rememberStorageLocationPicker(storageLocation, StorageLocationViewModel.Then.EXPORT_DOWNLOADS)
    val pendingTree by viewModel.storagePreferences.adoptionTree.collectAsState()
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // The scheduler takes the read grant, and only for a run it starts.
        if (uri != null) viewModel.startAdoption(uri.toString())
    }

    SettingsColumn {
        CheckboxRow(
            labelRes = R.string.pref_download_only_over_wifi,
            detailRes = R.string.pref_download_only_over_wifi_detail,
            pref = viewModel.downloadPreferences.downloadOnlyOverWifi,
        )
        SliderItem(
            value = parallelPageLimit,
            valueRange = parallelPageLimits,
            label = stringResource(R.string.pref_download_parallel_pages),
            onChange = viewModel.downloadPreferences.parallelPageLimit::set,
        )
        DeleteChaptersGroup(
            viewModel = viewModel,
            removeAfterRead = removeAfterRead,
            excluded = excludedCategories(state.categories, removeExcluded),
            onEditExcluded = { editingRemoveExcluded = true },
        )
        CheckboxRow(
            labelRes = R.string.pref_download_new,
            detailRes = R.string.pref_download_new_detail,
            pref = viewModel.downloadPreferences.downloadNewChapters,
        )
        CheckboxRow(
            labelRes = R.string.pref_download_new_unread_chapters_only,
            detailRes = R.string.pref_download_new_unread_chapters_only_detail,
            pref = viewModel.downloadPreferences.downloadNewUnreadChaptersOnly,
            enabled = downloadNew,
        )
        ListRow(
            title = stringResource(R.string.categories_title),
            detail = categoryFilterLabel(state.categories, newIncluded, newExcluded),
            modifier = Modifier.alpha(if (downloadNew) 1f else DISABLED_ALPHA),
            onClick = if (downloadNew) { { editingNewCategories = true } } else null,
        )
        SettingsChipRow(R.string.auto_download_while_reading) {
            autoDownloadWhileReadingChoices.forEach { amount ->
                FilterChip(
                    selected = amount == downloadAhead,
                    onClick = { viewModel.downloadPreferences.autoDownloadWhileReading.set(amount) },
                    label = {
                        Text(
                            if (amount == 0) {
                                stringResource(R.string.disabled)
                            } else {
                                pluralStringResource(R.plurals.next_unread_chapters, amount, amount)
                            },
                        )
                    },
                )
            }
        }
        val orphans = state.orphanDownloads?.size
        ListRow(
            title = stringResource(R.string.pref_download_orphans),
            detail = when {
                orphans == null -> stringResource(R.string.pref_download_orphans_pending)
                orphans == 0 -> stringResource(R.string.pref_download_orphans_none)
                else -> pluralStringResource(R.plurals.pref_download_orphans_format, orphans, orphans)
            },
            trailing = if (orphans != null && orphans > 0) stringResource(R.string.action_delete) else null,
            onTrailing = { confirmingDelete = true },
        )
        val exportState = state.export
        ListRow(
            title = stringResource(R.string.pref_export_downloads),
            detail = exportDetail(exportState, storageState.status),
            modifier = Modifier.testTag(EXPORT_DOWNLOADS_ROW_TAG),
            onClick = when {
                exportState.running -> null
                storageState.status == StorageLocation.Status.REACHABLE -> viewModel::exportDownloads
                else -> pickThenExport
            },
        )
        val adoption = state.adoption
        ListRow(
            title = stringResource(R.string.pref_adopt_downloads),
            detail = when {
                adoption.running && adoption.total == 0 -> stringResource(R.string.pref_adopt_downloads_walking)
                adoption.running -> stringResource(R.string.pref_adopt_downloads_progress_format, adoption.done, adoption.total)
                pendingTree.isNotEmpty() -> stringResource(R.string.pref_adopt_downloads_interrupted)
                else -> stringResource(R.string.pref_adopt_downloads_detail)
            },
            modifier = Modifier.testTag(ADOPT_DOWNLOADS_ROW_TAG),
            onClick = if (adoption.running) null else { { pickTree.launch(STORAGE_ROOT_HINT.toUri()) } },
        )
        state.adoptionSummary?.let { summary ->
            val navigator = LocalNavigator.current
            ListRow(
                title = stringResource(R.string.pref_adopt_summary),
                detail = stringResource(
                    R.string.pref_adopt_summary_format,
                    summary.copied,
                    summary.skipped,
                    summary.unmatched.size,
                    summary.failures.size,
                ),
                onClick = { navigator.open(Route.AdoptionSummary) },
            )
        }
    }

    if (editingRemoveExcluded) {
        MultiSelectDialog(
            title = stringResource(R.string.pref_remove_exclude_categories),
            entries = state.categories.map { it.id.toString() to it.visualName },
            selected = removeExcluded,
            onDismissRequest = { editingRemoveExcluded = false },
            onConfirm = {
                viewModel.downloadPreferences.removeExcludeCategories.set(it)
                editingRemoveExcluded = false
            },
        )
    }

    if (editingNewCategories) {
        CategoryFilterDialog(
            message = stringResource(R.string.pref_download_new_categories_details),
            categories = state.categories,
            included = newIncluded,
            excluded = newExcluded,
            onDismissRequest = { editingNewCategories = false },
            onConfirm = { included, excluded ->
                viewModel.setDownloadNewChapterCategories(included, excluded)
                editingNewCategories = false
            },
        )
    }

    val orphans = state.orphanDownloads?.size
    if (confirmingDelete && orphans != null && orphans > 0) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.pref_download_orphans_delete_title)) },
            text = {
                Column {
                    Text(pluralStringResource(R.plurals.pref_download_orphans_delete_message_format, orphans, orphans))
                    state.orphansNamed.forEach { row ->
                        Text(
                            text = stringResource(
                                R.string.pref_download_orphans_delete_item_format,
                                row.mangaTitle,
                                row.chapterName,
                            ),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (state.orphansMore > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.pref_download_orphans_delete_more_format,
                                state.orphansMore,
                                state.orphansMore,
                            ),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.deleteOrphanDownloads()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Upstream's "Delete chapters" group (phase 3 unit DR), its rows in upstream's order with upstream's labels:
 * after a manual mark read, which chapter to delete after reading, bookmarked chapters, and the
 * excluded categories, summed up by [excluded]'s names. The rows sit between two dividers so the
 * auto-download rows below do not read as part of the group; a chip row's label is a heading of the
 * same style as the group's own.
 */
@Composable
private fun DeleteChaptersGroup(
    viewModel: SettingsViewModel,
    removeAfterRead: Int,
    excluded: List<Category>,
    onEditExcluded: () -> Unit,
) {
    HorizontalDivider()
    HeadingItem(R.string.pref_category_delete_chapters)
    CheckboxItem(
        label = stringResource(R.string.pref_remove_after_marked_as_read),
        pref = viewModel.downloadPreferences.removeAfterMarkedAsRead,
    )
    SettingsChipRow(R.string.pref_remove_after_read) {
        removeAfterReadChoices.forEach { (slot, label) ->
            FilterChip(
                selected = slot == removeAfterRead,
                onClick = { viewModel.downloadPreferences.removeAfterReadSlots.set(slot) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    CheckboxItem(
        label = stringResource(R.string.pref_remove_bookmarked_chapters),
        pref = viewModel.downloadPreferences.removeBookmarkedChapters,
    )
    ListRow(
        title = stringResource(R.string.pref_remove_exclude_categories),
        detail = excluded.map { it.visualName }.joinToString()
            .ifEmpty { stringResource(R.string.pref_remove_exclude_categories_none) },
        onClick = onEditExcluded,
    )
    HorizontalDivider()
}

/**
 * Where the tree picker opens: the root of shared storage, as a document of the platform's external
 * storage provider. The picker only takes the hint; the folder to copy from is picked by browsing to
 * it. Built as a string because a file path, which the storage classes would have supplied, is not
 * something the picker can take (phase 2 decision Q6(d)).
 */
/** An interval of a week, which [IntervalRow] names rather than counting its hours. */
internal const val WEEK_HOURS = 168

/** Where a folder picker opens: the top of the shared storage. */
internal const val STORAGE_ROOT_HINT = "content://com.android.externalstorage.documents/document/primary%3A"

/**
 * What the Export downloads row says: the run in flight, then without a reachable storage location
 * that tapping chooses one, then how the last run ended, else what the row does.
 */
@Composable
private fun exportDetail(export: DownloadExportScheduler.State, status: StorageLocation.Status?): String {
    val last = export.last
    return when {
        export.running && export.total == 0 -> stringResource(R.string.pref_export_downloads_counting)
        export.running -> stringResource(R.string.pref_export_downloads_progress_format, export.done, export.total)
        status != StorageLocation.Status.REACHABLE -> stringResource(R.string.pref_export_downloads_no_location)
        last == null || last.unreachable -> stringResource(R.string.pref_export_downloads_detail)
        last.stopped -> stringResource(R.string.pref_export_downloads_stopped)
        else -> stringResource(R.string.pref_export_downloads_last_format, last.copied, last.skipped, last.failed)
    }
}

/** The tag on the Export downloads row, which a device test reads. */
internal const val EXPORT_DOWNLOADS_ROW_TAG = "settings_export_downloads"

/** The tag on the adoption row, which a device test taps to open the picker. */
internal const val ADOPT_DOWNLOADS_ROW_TAG = "settings_adopt_downloads"

@Composable
private fun DataSection(viewModel: SettingsViewModel, showMessage: (String, SnackbarAction?) -> Unit) {
    val navigator = LocalNavigator.current
    val backupInterval by viewModel.backupPreferences.backupInterval.collectAsState()
    val lastAutoBackup by viewModel.backupPreferences.lastAutoBackupTimestamp.collectAsState()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Saikou keeps everything in its own storage, so the one volume upstream's per-volume bars would
    // show is filesDir's. Read each time the section is composed, as upstream's StorageInfo does.
    val filesDir = context.filesDir
    val availableSpace = remember(filesDir) { DiskUtil.getAvailableStorageSpace(filesDir) }
    val totalSpace = remember(filesDir) { DiskUtil.getTotalStorageSpace(filesDir) }

    // Measured each time the section is shown, since the reader fills the cache between visits and
    // a removal from the library or the storage screen changes the downloads.
    LaunchedEffect(viewModel) {
        viewModel.refreshChapterCacheSize()
        viewModel.refreshStorage()
    }

    val storageLocation: StorageLocationViewModel = metroViewModel()

    SettingsColumn {
        // Upstream's Data and storage screen opens on its Storage location row and info line.
        StorageLocationRows(storageLocation)
        IntervalRow(
            labelRes = R.string.pref_backup_interval,
            options = backupIntervals,
            selectedHours = backupInterval,
            onSelect = viewModel::setBackupInterval,
        )
        val keptBackups by viewModel.backupPreferences.keptBackups.collectAsState()
        // Read when the next automatic backup is written, which deletes any beyond the count.
        SliderItem(
            value = keptBackups,
            valueRange = BackupPreferences.KEPT_BACKUPS_RANGE,
            label = stringResource(R.string.pref_backup_slots),
            onChange = viewModel.backupPreferences.keptBackups::set,
        )
        ListRow(
            title = stringResource(R.string.backup_create),
            detail = stringResource(R.string.more_backup_detail),
            onClick = { navigator.open(Route.BackupCreate) },
        )
        // Upstream's library list export, beside the backup it complements.
        LibraryExportRow(showMessage)
        // Phase 4 unit ST: upstream's line under its backup rows, the time BackupExportWorker last
        // stamped, then upstream's storage usage heading its data group, as one row.
        InlineNotice(stringResource(R.string.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)))
        val downloadsSize = state.downloadsSize
        val chapterCacheSize = state.chapterCacheSize
        ListRow(
            title = stringResource(R.string.pref_storage_usage),
            detail = if (downloadsSize == null || chapterCacheSize == null) {
                stringResource(R.string.pref_storage_usage_pending)
            } else {
                stringResource(
                    R.string.pref_storage_usage_format,
                    Formatter.formatFileSize(context, downloadsSize),
                    chapterCacheSize,
                ) + "\n" + stringResource(
                    R.string.available_disk_space_info,
                    Formatter.formatFileSize(context, availableSpace),
                    Formatter.formatFileSize(context, totalSpace),
                )
            },
            modifier = Modifier.testTag(STORAGE_USAGE_ROW_TAG),
        )
        // Upstream issue #565: the downloads a removal from the library left behind.
        val orphanTitles = state.orphanTitles
        ListRow(
            title = stringResource(R.string.storage_downloads_title),
            detail = when {
                orphanTitles == null -> stringResource(R.string.storage_downloads_pending)
                orphanTitles.isEmpty() -> stringResource(R.string.storage_downloads_none)
                else -> pluralStringResource(
                    R.plurals.storage_downloads_summary_format,
                    orphanTitles.size,
                    orphanTitles.size,
                    Formatter.formatFileSize(context, orphanTitles.sumOf { it.sizeBytes }),
                )
            },
            modifier = Modifier.testTag(STORAGE_DOWNLOADS_ROW_TAG),
            onClick = { navigator.open(Route.StorageDownloads) },
        )
        // Upstream's key, carried by a restored backup (ruling R56). `MainActivity` reads it on a
        // fresh launch; nothing clears the cache when a reading session ends.
        CheckboxRow(
            labelRes = R.string.pref_auto_clear_chapter_cache,
            detailRes = R.string.pref_auto_clear_chapter_cache_detail,
            pref = viewModel.libraryPreferences.autoClearChapterCache,
        )
        ListRow(
            title = stringResource(R.string.pref_clear_chapter_cache),
            detail = state.chapterCacheSize
                ?.let { stringResource(R.string.pref_chapter_cache_size_format, it) }
                ?: stringResource(R.string.pref_chapter_cache_size_pending),
            onClick = viewModel::clearChapterCache,
        )
        InlineNotice(stringResource(R.string.settings_data_local_note))
    }
}

/** The tags on the Data section's storage rows, which a device test reads and taps (phase 4 unit ST). */
internal const val STORAGE_USAGE_ROW_TAG = "settings_storage_usage"
internal const val STORAGE_DOWNLOADS_ROW_TAG = "settings_storage_downloads"

/**
 * Upstream's "Clear WebView data", its body unchanged: the WebView's cache, form data, history and SSL
 * choices, the web storage, and the `app_webview` folder, which holds the cookie store OkHttp's
 * `AndroidCookieJar` sends with every request, so every source's logins and challenge cookies go.
 * True when it all went; the caller says which, where upstream's body toasts it.
 */
private fun Context.clearWebViewData(): Boolean {
    try {
        WebView(this).run {
            setDefaultSettings()
            clearCache(true)
            clearFormData()
            clearHistory()
            clearSslPreferences()
        }
        WebStorage.getInstance().deleteAllData()
        applicationInfo?.dataDir?.let { File("$it/app_webview/").deleteRecursively() }
        return true
    } catch (e: Throwable) {
        logcat(LogPriority.ERROR, e)
        return false
    }
}

/**
 * Upstream's "Security and privacy" (phase 4 unit SR): Hide notification content, which the download,
 * library update and backup notifiers read, and around it upstream's app lock and secure screen rows
 * in upstream's order (phase 4 unit AL), which `AppLock` reads. The lock is offered only while the
 * device can authenticate and Lock when idle only while the lock is on, and a change to either asks
 * for the lock prompt first, as upstream's do. A row not offered is not on screen at all, as in upstream
 * ([lockRows], phase 4 unit MP1).
 */
@Composable
private fun SecuritySection(viewModel: SettingsViewModel) {
    val preferences = viewModel.securityPreferences
    val context = LocalContext.current
    val authSupported = remember { context.isAuthenticationSupported() }
    val useAuth by preferences.useAuthenticator.collectAsState()
    val lockAppAfter by preferences.lockAppAfter.collectAsState()
    val secureScreen by preferences.secureScreen.collectAsState()
    val confirmLock = rememberAuthenticate(R.string.lock_with_biometrics)
    val confirmIdle = rememberAuthenticate(R.string.lock_when_idle)
    val scope = rememberCoroutineScope()

    val shown = lockRows(authSupported = authSupported, lockOn = useAuth)

    SettingsColumn {
        ShownWhen(shown.requireUnlock) {
            CheckboxRow(
                labelRes = R.string.lock_with_biometrics,
                pref = preferences.useAuthenticator,
                confirm = confirmLock,
            )
        }
        ShownWhen(shown.lockWhenIdle) {
            ChoiceRow(
                labelRes = R.string.lock_when_idle,
                choices = lockAppAfterChoices,
                selected = { it == lockAppAfter },
                label = { minutes ->
                    when (minutes) {
                        -1 -> stringResource(R.string.lock_never)
                        0 -> stringResource(R.string.lock_always)
                        else -> pluralStringResource(R.plurals.lock_after_mins, minutes, minutes)
                    }
                },
                onSelect = { minutes -> scope.launch { if (confirmIdle()) preferences.lockAppAfter.set(minutes) } },
            )
        }
        CheckboxRow(
            labelRes = R.string.hide_notification_content,
            pref = preferences.hideNotificationContent,
        )
        ChoiceRow(
            labelRes = R.string.secure_screen,
            choices = secureScreenChoices,
            selected = { it == secureScreen },
            label = { stringResource(it.labelRes) },
            onSelect = preferences.secureScreen::set,
        )
        InlineNotice(stringResource(R.string.secure_screen_summary))
    }
}

/**
 * Every row's feedback goes through [showMessage], the screen's snackbar, where upstream toasts it
 * (plan 3.1 item 12), except the refusals of the user agent dialog and the custom DNS over HTTPS
 * dialog: each dialog stays open on its refusal, and a snackbar would sit under the dialog's scrim,
 * so the first is still a toast and the second is written under its field.
 *
 * The rows read once per process (Verbose logging, DNS over HTTPS, the user agent and its reset,
 * Revoke trusted unknown extensions, and a changed extension list override) say upstream's "Requires
 * app restart to take effect" with a Restart action, which restarts the app on this section
 * ([AppRestart], F13). Upstream's toast has no action; the line would otherwise leave the owner to
 * force-stop the app from Android's settings.
 */
@Composable
private fun AdvancedSection(viewModel: SettingsViewModel, showMessage: (String, SnackbarAction?) -> Unit) {
    val navigator = LocalNavigator.current
    // The exemption request needs a real activity to start the system prompt from, and this
    // section is only ever composed inside one.
    val activity = LocalActivity.current
    val libVersionOverride by viewModel.extensionsPreferences.libVersionOverride.collectAsState()
    var editingLibVersions by remember { mutableStateOf(false) }
    val network = viewModel.networkPreferences
    val dohProvider by network.dohProvider.collectAsState()
    val dohCustomUrl by network.dohProviderCustom.collectAsState()
    // A stored URL the network client refuses leaves it on the system resolver, so Custom is shown
    // chosen only while the client would use the URL.
    val customDohInEffect = remember(dohProvider, dohCustomUrl) {
        dohProvider == PREF_DOH_CUSTOM && customDohUrl(dohCustomUrl) != null
    }
    var editingCustomDoh by remember { mutableStateOf(false) }
    val userAgent by network.defaultUserAgent.collectAsState()
    var editingUserAgent by remember { mutableStateOf(false) }
    val bitmapThreshold by viewModel.basePreferences.hardwareBitmapThreshold.collectAsState()
    // Upstream's list and its condition; the texture limit is the device's, read once per process.
    val bitmapThresholds = remember { GLUtil.CUSTOM_TEXTURE_LIMIT_OPTIONS }
    val bitmapThresholdEnabled = remember {
        !ImageUtil.HARDWARE_BITMAP_UNSUPPORTED && GLUtil.DEVICE_TEXTURE_LIMIT > GLUtil.SAFE_TEXTURE_LIMIT
    }
    val context = LocalContext.current
    val restartNeeded = stringResource(R.string.requires_app_restart)
    val restartLabel = stringResource(R.string.action_restart)
    val offerRestart: () -> Unit = {
        showMessage(
            restartNeeded,
            SnackbarAction(restartLabel) { AppRestart.restart(context, MainDestination.ADVANCED_SETTINGS) },
        )
    }
    val appInfo = viewModel.appInfo
    val version = stringResource(
        R.string.settings_version_format,
        appInfo.name,
        appInfo.versionName,
        appInfo.versionCode,
        appInfo.commitSha,
    )
    val copied = stringResource(R.string.copied_to_clipboard)
    val webViewCleared = stringResource(R.string.webview_data_deleted)
    val webViewFailed = stringResource(R.string.cache_delete_error)
    val resetDone = stringResource(R.string.pref_reset_viewer_flags_success)
    val resetFailed = stringResource(R.string.pref_reset_viewer_flags_error)
    val rebuildDone = stringResource(R.string.pref_rebuild_library_counts_success)
    val rebuildFailed = stringResource(R.string.pref_rebuild_library_counts_error)
    // The refresh answers with one of several strings; read through the resources Compose tracks.
    val resources = LocalResources.current

    SettingsColumn {
        // The build a report is about, first in the section, and a tap copies it (phase 3 unit AD).
        ListRow(
            title = stringResource(R.string.settings_version),
            detail = version,
            onClick = { context.copyToClipboard(version, copied) },
        )
        // The in-app updater, under the version it compares against.
        AppUpdateRows(appName = appInfo.name, showMessage = showMessage)
        CheckboxRow(
            labelRes = R.string.pref_auto_check_extensions,
            detailRes = R.string.pref_auto_check_extensions_detail,
            pref = viewModel.extensionsPreferences.autoCheckUpdates,
        )
        ListRow(
            title = stringResource(R.string.pref_extension_lib_override),
            detail = libVersionOverride.ifBlank { stringResource(R.string.state_off) },
            onClick = { editingLibVersions = true },
        )
        ListRow(
            title = stringResource(R.string.pref_battery_exemption),
            detail = stringResource(R.string.pref_battery_exemption_detail),
            onClick = { activity?.let(viewModel::requestBatteryExemption) },
        )
        ListRow(
            title = stringResource(R.string.crash_title),
            detail = stringResource(R.string.more_crash_detail),
            onClick = { navigator.open(Route.CrashLog) },
        )
        // Phase 4 unit SR: upstream's verbose logging row, which follows its crash log row, then its
        // networking, library and reader rows in its order.
        CheckboxRow(
            labelRes = R.string.pref_verbose_logging,
            detailRes = R.string.pref_verbose_logging_summary,
            pref = network.verboseLogging,
            onToggled = { offerRestart() },
        )
        // Phase 4 unit ST: upstream's data group (clear database), then the head of its networking
        // group (clear WebView data), before the networking rows unit SR placed.
        ListRow(
            title = stringResource(R.string.pref_clear_database),
            detail = stringResource(R.string.pref_clear_database_summary),
            onClick = { navigator.open(Route.ClearDatabase) },
        )
        // Phase 5 unit SC4: the repair of the library's chapter counts, beside upstream's other
        // database row. Saikou's own; upstream has no counts table to rebuild.
        ListRow(
            title = stringResource(R.string.pref_rebuild_library_counts),
            detail = stringResource(R.string.pref_rebuild_library_counts_summary),
            onClick = {
                viewModel.rebuildLibraryCounts { success -> showMessage(if (success) rebuildDone else rebuildFailed, null) }
            },
        )
        ListRow(
            title = stringResource(R.string.pref_clear_webview_data),
            detail = "",
            onClick = { showMessage(if (context.clearWebViewData()) webViewCleared else webViewFailed, null) },
        )
        ChoiceRow(
            labelRes = R.string.pref_dns_over_https,
            choices = dohProviderChoices,
            selected = { (provider, _) ->
                if (provider == PREF_DOH_CUSTOM) customDohInEffect else provider == dohProvider
            },
            label = { (provider, name) ->
                when {
                    name != null -> name
                    provider == PREF_DOH_CUSTOM -> stringResource(R.string.pref_dns_over_https_custom)
                    else -> stringResource(R.string.disabled)
                }
            },
            onSelect = { (provider, _) ->
                if (provider == PREF_DOH_CUSTOM) {
                    editingCustomDoh = true
                } else {
                    network.dohProvider.set(provider)
                    offerRestart()
                }
            },
        )
        if (customDohInEffect) {
            Text(
                text = dohCustomUrl,
                modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ListRow(
            title = stringResource(R.string.pref_user_agent_string),
            detail = userAgent,
            onClick = { editingUserAgent = true },
        )
        val userAgentChanged = userAgent != network.defaultUserAgent.defaultValue()
        ListRow(
            title = stringResource(R.string.pref_reset_user_agent_string),
            detail = "",
            modifier = Modifier.alpha(if (userAgentChanged) 1f else DISABLED_ALPHA),
            onClick = if (userAgentChanged) {
                {
                    viewModel.resetDefaultUserAgent()
                    offerRestart()
                }
            } else {
                null
            },
        )
        // Phase 4 unit ST: the head of upstream's library group.
        ListRow(
            title = stringResource(R.string.pref_refresh_library_covers),
            detail = stringResource(R.string.pref_refresh_library_covers_summary),
            onClick = {
                viewModel.refreshLibraryCovers { message -> showMessage(resources.getString(message), null) }
            },
        )
        ListRow(
            title = stringResource(R.string.pref_reset_viewer_flags),
            detail = stringResource(R.string.pref_reset_viewer_flags_summary_synced),
            onClick = {
                viewModel.resetViewerFlags { success -> showMessage(if (success) resetDone else resetFailed, null) }
            },
        )
        CheckboxRow(
            labelRes = R.string.pref_update_library_manga_titles,
            detailRes = R.string.pref_update_library_manga_titles_summary,
            pref = viewModel.libraryPreferences.updateMangaTitles,
        )
        CheckboxRow(
            labelRes = R.string.pref_disallow_non_ascii_filenames,
            detailRes = R.string.pref_disallow_non_ascii_filenames_details,
            pref = viewModel.libraryPreferences.disallowNonAsciiFilenames,
        )
        ChoiceRow(
            labelRes = R.string.pref_hardware_bitmap_threshold,
            choices = bitmapThresholds,
            selected = { it == bitmapThreshold },
            label = { hardwareBitmapThresholdLabel(bitmapThresholds, it) },
            enabled = bitmapThresholdEnabled,
            onSelect = viewModel.basePreferences.hardwareBitmapThreshold::set,
        )
        Text(
            text = stringResource(
                R.string.pref_hardware_bitmap_threshold_summary,
                if (bitmapThreshold in bitmapThresholds) hardwareBitmapThresholdLabel(bitmapThresholds, bitmapThreshold) else "",
            ),
            modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Phase 4 unit ST: the last row of upstream's extensions group, the last group of its screen.
        ListRow(
            title = stringResource(R.string.ext_revoke_trust),
            detail = "",
            onClick = {
                viewModel.revokeTrustedExtensions()
                offerRestart()
            },
        )
        InlineNotice(stringResource(R.string.settings_advanced_abi_note))
    }

    if (editingCustomDoh) {
        CustomDohDialog(
            current = dohCustomUrl,
            onConfirm = { url ->
                viewModel.setCustomDoh(url).also { written ->
                    if (written) {
                        offerRestart()
                        editingCustomDoh = false
                    }
                }
            },
            onDismiss = { editingCustomDoh = false },
        )
    }

    if (editingUserAgent) {
        val invalid = stringResource(R.string.error_user_agent_string_invalid)
        UserAgentDialog(
            current = userAgent,
            onConfirm = {
                if (viewModel.setDefaultUserAgent(it)) {
                    offerRestart()
                    editingUserAgent = false
                } else {
                    context.toast(invalid)
                }
            },
            onDismiss = { editingUserAgent = false },
        )
    }

    if (editingLibVersions) {
        LibVersionOverrideDialog(
            current = libVersionOverride,
            onConfirm = {
                val value = it.trim()
                viewModel.extensionsPreferences.libVersionOverride.set(value)
                editingLibVersions = false
                // Read when extensions load, as the dialog's note says, so only a change asks for it.
                if (value != libVersionOverride) offerRestart()
            },
            onDismiss = { editingLibVersions = false },
        )
    }
}

/**
 * Behaviour that could make the app worse for some owners, each switch off by default (R162 item
 * 2), so the default app stays as it was. Every row says what it changes and what it can cost.
 */
@Composable
private fun ExperimentalSection(viewModel: SettingsViewModel) {
    InlineNotice(stringResource(R.string.settings_experimental_note))
    CheckboxRow(
        labelRes = R.string.settings_experimental_parallel_pages,
        pref = viewModel.readerPreferences.parallelPageLoading,
        detailRes = R.string.settings_experimental_parallel_pages_summary,
    )
}

/**
 * The escape hatch for the day the extension library majors and an index ships packages this build
 * does not accept yet. Nothing here validates the text: the loader's policy already falls back to
 * the build constant when the value parses to nothing, and a second rule here could only disagree
 * with it. The dialog says what the value does instead, including that it is read at load time.
 */
@Composable
private fun LibVersionOverrideDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_extension_lib_override)) },
        text = {
            Column {
                Text(stringResource(R.string.pref_extension_lib_override_note))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.pref_extension_lib_override_hint)) },
                    modifier = Modifier.padding(top = 12.dp),
                    singleLine = true,
                    // A version list is typed, not written: autocorrect turns 1.6 into prose.
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Decimal,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The URL of the DNS over HTTPS row's Custom choice. [onConfirm] answers whether it took the text;
 * on a refusal the dialog stays open with the reason under the field, and Cancel leaves the choice
 * as it was. The error sits in the dialog because a snackbar would be under its scrim.
 */
@Composable
private fun CustomDohDialog(
    current: String,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    var refused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_dns_over_https_custom_title)) },
        text = {
            Column {
                Text(stringResource(R.string.pref_dns_over_https_custom_note))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        refused = false
                    },
                    label = { Text(stringResource(R.string.pref_dns_over_https_custom_url)) },
                    modifier = Modifier.padding(top = 12.dp),
                    isError = refused,
                    supportingText = if (refused) {
                        { Text(stringResource(R.string.error_dns_over_https_custom_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { refused = !onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Upstream's edit text dialog for the default user agent: OK only once the text is changed and not
 * blank, and the dialog stays open on a value [onConfirm] refuses, as upstream's does.
 */
@Composable
private fun UserAgentDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_user_agent_string)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                isError = text.isBlank(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text != current && text.isNotBlank(),
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The in-app updater's rows in Advanced: Check for updates, which asks GitHub now, and the switch for
 * the check when the app comes to the foreground. A newer release raises the prompt over this
 * screen (the navigation host's `AppUpdatePrompt`); every other answer is a line on the snackbar,
 * with no action, since it applies at once. A debug build never checks by itself, and the switch's
 * line says so.
 */
@Composable
private fun AppUpdateRows(appName: String, showMessage: (String, SnackbarAction?) -> Unit) {
    val viewModel: AppUpdateViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val upToDate = stringResource(R.string.app_update_up_to_date)
    val failed = stringResource(R.string.app_update_check_failed)
    ListRow(
        title = stringResource(R.string.pref_app_update_check),
        detail = stringResource(if (state.checking) R.string.pref_app_update_checking else R.string.pref_app_update_check_detail),
        onClick = {
            if (!state.checking) {
                viewModel.checkNow { result ->
                    val message = when (result) {
                        is AppUpdateChecker.Result.Available -> null
                        AppUpdateChecker.Result.UpToDate -> upToDate
                        AppUpdateChecker.Result.Failed -> failed
                        is AppUpdateChecker.Result.NoApk -> resources.getString(R.string.app_update_no_apk, appName, result.version)
                    }
                    if (message != null) showMessage(message, null)
                }
            }
        },
    )
    CheckboxRow(
        labelRes = R.string.pref_app_update_auto_check,
        detailRes = if (viewModel.build.automaticChecks) {
            R.string.pref_app_update_auto_check_detail
        } else {
            R.string.pref_app_update_auto_check_debug
        },
        pref = viewModel.preferences.autoCheck,
    )
}

/**
 * The vendored `CheckboxItem` collects the preference itself, which is what keeps the box in step
 * with a write from anywhere else, but it has no room for a second line. A toggle with a detail
 * string has it drawn under the row instead of dropped; one of upstream's rows with no subtitle
 * (phase 4 unit SR) passes none. A detail built from arguments comes as [detail] instead.
 *
 * A row that is not [enabled] (one that only applies while another is on, as upstream's `enabled`
 * has it) is dimmed and a tap leaves the preference as it is. [onToggled] runs after a tap has
 * written the preference, for a row that says more on a change, as upstream's toasts do. A row with
 * [confirm] (phase 4 unit AL) asks it first and writes the new value only when it answers true, as
 * upstream's `onValueChanged` gates a switch.
 */
@Composable
private fun CheckboxRow(
    @StringRes labelRes: Int,
    pref: Preference<Boolean>,
    @StringRes detailRes: Int? = null,
    detail: String? = null,
    enabled: Boolean = true,
    onToggled: (() -> Unit)? = null,
    confirm: (suspend () -> Boolean)? = null,
) {
    val detailText = detail ?: detailRes?.let { stringResource(it) }
    Column(modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        when {
            !enabled -> {
                val checked by pref.collectAsState()
                CheckboxItem(label = stringResource(labelRes), checked = checked, onClick = {}, enabled = false)
            }
            confirm != null -> {
                val checked by pref.collectAsState()
                val scope = rememberCoroutineScope()
                CheckboxItem(
                    label = stringResource(labelRes),
                    checked = checked,
                    onClick = {
                        val next = !checked
                        scope.launch { if (confirm()) pref.set(next) }
                    },
                )
            }
            onToggled != null -> {
                val checked by pref.collectAsState()
                CheckboxItem(
                    label = stringResource(labelRes),
                    checked = checked,
                    onClick = {
                        pref.toggle()
                        onToggled()
                    },
                )
            }
            else -> CheckboxItem(label = stringResource(labelRes), pref = pref)
        }
        if (detailText != null) {
            Text(
                text = detailText,
                modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Upstream's `StatusWrapper`: [content] is on screen only while [visible], coming in and going out
 * with upstream's expand and fade. Only the Security section's two lock rows use it; every other row
 * that depends on another is drawn dimmed instead (phase 4 unit MP1).
 */
@Composable
private fun ShownWhen(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        content()
    }
}

/**
 * A single choice row of chips, one per entry of [choices] with its [label]: upstream's list
 * preference, drawn the way this screen draws its choices. A stored value no chip [selected], which
 * a backup from another reader can carry, selects none rather than reading as a value it is not.
 */
@Composable
private fun <T> ChoiceRow(
    @StringRes labelRes: Int,
    choices: List<T>,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    SettingsChipRow(labelRes) {
        choices.forEach { choice ->
            FilterChip(
                selected = selected(choice),
                onClick = { onSelect(choice) },
                label = { Text(label(choice)) },
                enabled = enabled,
            )
        }
    }
}

/**
 * A multiple choice row of chips over a string set preference, one chip per key of [choices] with
 * its label: upstream's multi-select list, drawn the way this screen draws its choices.
 */
@Composable
private fun ChoiceSetRow(
    @StringRes labelRes: Int,
    choices: List<Pair<String, Int>>,
    selected: Set<String>,
    enabled: Boolean = true,
    onChange: (Set<String>) -> Unit,
) {
    SettingsChipRow(labelRes) {
        choices.forEach { (key, label) ->
            FilterChip(
                selected = key in selected,
                onClick = { onChange(selected.toggled(key)) },
                label = { Text(stringResource(label)) },
                enabled = enabled,
            )
        }
    }
}

/**
 * Hours between automatic runs. Zero reads as off rather than as an interval, because it cancels
 * the periodic worker instead of scheduling one. A stored value outside [options], which a backup
 * from another reader can carry, selects no chip rather than silently reading as a value it is not.
 */
@Composable
internal fun IntervalRow(
    @StringRes labelRes: Int,
    options: List<Int>,
    selectedHours: Int,
    onSelect: (Int) -> Unit,
) {
    SettingsChipRow(labelRes) {
        options.forEach { hours ->
            FilterChip(
                selected = hours == selectedHours,
                onClick = { onSelect(hours) },
                label = {
                    Text(
                        when (hours) {
                            0 -> stringResource(R.string.interval_off)
                            WEEK_HOURS -> stringResource(R.string.interval_weekly)
                            else -> stringResource(R.string.interval_hours_format, hours)
                        },
                    )
                },
            )
        }
    }
}
