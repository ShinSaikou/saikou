package com.shinsaikou.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.ui.components.IconListRow
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SectionLabel
import eu.kanade.presentation.more.LogoHeader
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import com.shinsaikou.R as AppR

/**
 * The More tab: the two process-wide toggles, then the way in to everything that is not a tab.
 *
 * It has no view model of its own. Both toggles are `BasePreferences` values the whole app reads,
 * so they live on `MainViewModel`, which the navigation host owns; this screen is handed their
 * current value and the two functions that flip them.
 *
 * Every row leads with an icon, as upstream's More rows do, so a row can be found by its glyph before
 * its name is read. The rows upstream has take upstream's icons (v0.20.4); the rest take the closest
 * Material icon.
 */
@Composable
fun MoreScreen(
    incognito: Boolean,
    downloadedOnly: Boolean,
    onToggleIncognito: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
) {
    val navigator = LocalNavigator.current

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.label_more),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ScrollbarLazyColumn(contentPadding = padding) {
            item { LogoHeader(iconPadding = PaddingValues(vertical = 32.dp)) }
            item { SectionLabel(stringResource(R.string.more_section_quick)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.more_incognito),
                    detail = stringResource(R.string.more_incognito_detail),
                    icon = ImageVector.vectorResource(AppR.drawable.ic_glasses_24dp),
                    checked = incognito,
                    onToggle = onToggleIncognito,
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.more_downloaded_only),
                    detail = stringResource(R.string.more_downloaded_only_detail),
                    icon = Icons.Outlined.CloudOff,
                    checked = downloadedOnly,
                    onToggle = onToggleDownloadedOnly,
                )
            }

            item { SectionLabel(stringResource(R.string.more_section_manage)) }
            item {
                IconListRow(
                    title = stringResource(R.string.download_queue_title),
                    detail = stringResource(R.string.more_download_queue_detail),
                    icon = Icons.Outlined.GetApp,
                    onClick = { navigator.open(Route.DownloadQueue) },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.categories_title),
                    detail = stringResource(R.string.more_categories_detail),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    onClick = { navigator.open(Route.Categories) },
                )
            }
            // Upstream's order: download queue, categories, statistics, data and storage, then the rest.
            item {
                IconListRow(
                    title = stringResource(R.string.label_stats),
                    icon = Icons.Outlined.QueryStats,
                    onClick = { navigator.open(Route.Statistics) },
                )
            }
            // Upstream's row opens its settings on the Data and storage screen; Saikou's opens Settings
            // on the Data section (phase 4 unit ST).
            item {
                IconListRow(
                    title = stringResource(R.string.label_data_storage),
                    icon = Icons.Outlined.Storage,
                    onClick = { navigator.open(Route.Settings(SettingsSection.DATA)) },
                )
            }
            // Browse's icon for extensions, since the repositories are where they come from.
            item {
                IconListRow(
                    title = stringResource(R.string.ext_stores_title),
                    detail = stringResource(R.string.more_stores_detail),
                    icon = Icons.Outlined.Extension,
                    onClick = { navigator.open(Route.ExtensionStores()) },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.backup_title),
                    detail = stringResource(R.string.more_backup_detail),
                    icon = Icons.Outlined.SettingsBackupRestore,
                    onClick = { navigator.open(Route.BackupCreate) },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.label_settings),
                    detail = stringResource(R.string.more_settings_detail),
                    icon = Icons.Outlined.Settings,
                    onClick = { navigator.open(Route.Settings()) },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.crash_title),
                    detail = stringResource(R.string.more_crash_detail),
                    icon = Icons.Outlined.BugReport,
                    onClick = { navigator.open(Route.CrashLog) },
                )
            }
        }
    }
}

/**
 * A toggle with its summary line, upstream's `SwitchPreferenceWidget`: neither name says on its own
 * what the setting stops the app from doing.
 *
 * The switch takes no callback of its own; the whole row is the target, which keeps the touch area
 * the same as every other row on the screen.
 */
@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    icon: ImageVector,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    IconListRow(
        title = title,
        detail = detail,
        icon = icon,
        onClick = onToggle,
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}
