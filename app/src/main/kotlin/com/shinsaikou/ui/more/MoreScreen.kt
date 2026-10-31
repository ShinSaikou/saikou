package com.shinsaikou.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SectionLabel
import eu.kanade.presentation.more.LogoHeader
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The More tab: the two process-wide toggles, then the way in to everything that is not a tab.
 *
 * It has no view model of its own. Both toggles are `BasePreferences` values the whole app reads,
 * so they live on `MainViewModel`, which the navigation host owns; this screen is handed their
 * current value and the two functions that flip them.
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
                    checked = incognito,
                    onToggle = onToggleIncognito,
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.more_downloaded_only),
                    detail = stringResource(R.string.more_downloaded_only_detail),
                    checked = downloadedOnly,
                    onToggle = onToggleDownloadedOnly,
                )
            }

            item { SectionLabel(stringResource(R.string.more_section_manage)) }
            item {
                ListRow(
                    title = stringResource(R.string.download_queue_title),
                    detail = stringResource(R.string.more_download_queue_detail),
                    onClick = { navigator.open(Route.DownloadQueue) },
                )
            }
            item {
                ListRow(
                    title = stringResource(R.string.categories_title),
                    detail = stringResource(R.string.more_categories_detail),
                    onClick = { navigator.open(Route.Categories) },
                )
            }
            // Upstream's order: download queue, categories, statistics, data and storage, then the rest.
            item {
                ListRow(
                    title = stringResource(R.string.label_stats),
                    detail = "",
                    onClick = { navigator.open(Route.Statistics) },
                )
            }
            // Upstream's row opens its settings on the Data and storage screen; Saikou's opens Settings
            // on the Data section (phase 4 unit ST).
            item {
                ListRow(
                    title = stringResource(R.string.label_data_storage),
                    detail = "",
                    onClick = { navigator.open(Route.Settings(SettingsSection.DATA)) },
                )
            }
            item {
                ListRow(
                    title = stringResource(R.string.ext_stores_title),
                    detail = stringResource(R.string.more_stores_detail),
                    onClick = { navigator.open(Route.ExtensionStores()) },
                )
            }
            item {
                ListRow(
                    title = stringResource(R.string.backup_title),
                    detail = stringResource(R.string.more_backup_detail),
                    onClick = { navigator.open(Route.BackupCreate) },
                )
            }
            item {
                ListRow(
                    title = stringResource(R.string.label_settings),
                    detail = stringResource(R.string.more_settings_detail),
                    onClick = { navigator.open(Route.Settings()) },
                )
            }
            item {
                ListRow(
                    title = stringResource(R.string.crash_title),
                    detail = stringResource(R.string.more_crash_detail),
                    onClick = { navigator.open(Route.CrashLog) },
                )
            }
        }
    }
}

/**
 * `CheckboxItem` carries a label and nothing else, and both of these toggles are worth a summary
 * line: neither name says on its own what the setting stops the app from doing. The row is
 * `ListRowLayout`'s, so the quick controls and the rows under them line up.
 *
 * The switch takes no callback of its own; the whole row is the target, which keeps the touch area
 * the same as every other row on the screen.
 */
@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ListRowLayout(
        onClick = onToggle,
        trailing = {
            Switch(checked = checked, onCheckedChange = null)
        },
    ) {
        ListRowTitle(title)
        ListRowDetail(detail)
    }
}
