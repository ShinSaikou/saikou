package com.shinsaikou.ui.about

import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.BuildConfig
import com.shinsaikou.appupdate.ApkKind
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.appupdate.AppUpdateViewModel
import com.shinsaikou.ui.appupdate.message
import com.shinsaikou.ui.components.IconListRow
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SectionLabel
import com.shinsaikou.ui.crash.CrashLogExport
import com.shinsaikou.ui.util.copyToClipboard
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import java.io.File
import com.shinsaikou.R as AppR

/** The project's site and chat, as README.md links them. */
private const val WEBSITE_URL = "https://shinsaikou.com"
private const val DISCORD_URL = "https://discord.gg/VupfVKYquZ"

/** The repository the updater reads (`-Psaikou.updateRepo`), so a fork's About links to its own. */
private const val REPOSITORY_URL = "https://github.com/" + BuildConfig.UPDATE_REPO

/**
 * More > About: the build installed, Check for updates, the latest release's APKs, and the help a
 * bug report needs. The rows are the More tab's, an icon and a line under the title, so the screen
 * reads as the one it was opened from.
 *
 * Check for updates is the Settings row's own check (`AppUpdateViewModel`), whose prompt shows over
 * this screen as over any other; the Update on this build's own newer APK runs the same check, so
 * the prompt, with the release's notes, is the one way an update starts. Every other APK opens in
 * the browser, which downloads it for Android to install.
 */
@Composable
fun AboutScreen() {
    val viewModel: AboutViewModel = metroViewModel()
    val updates: AppUpdateViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    // The chooser is started from the activity this screen is composed in, as the crash log's is.
    val activity = LocalActivity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val info = viewModel.deviceInfo
    val appName = info.appName
    val copied = stringResource(R.string.copied_to_clipboard)
    val subject = stringResource(R.string.about_bug_report_subject, appName)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AboutViewModel.Event.ShareReport ->
                    if (activity == null || !CrashLogExport.share(activity, File(event.path), subject = subject)) {
                        snackbarHostState.showSnackbar(resources.getString(R.string.about_bug_report_share_failed))
                    }
                AboutViewModel.Event.ReportFailed ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.about_bug_report_failed))
            }
        }
    }

    val checkForUpdates = {
        if (!updateState.checking) {
            updates.checkNow { result ->
                result.message(resources, appName)?.let { scope.launch { snackbarHostState.showSnackbar(it) } }
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.about_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        ScrollbarLazyColumn(contentPadding = padding) {
            item {
                val buildType = stringResource(
                    if (info.debuggable) R.string.about_build_type_debug else R.string.about_build_type_release,
                )
                val versionLine = stringResource(
                    R.string.about_version_copy_format,
                    appName,
                    info.versionName,
                    info.versionCode,
                    buildType,
                    info.commit,
                )
                VersionBlock(
                    info = info,
                    buildType = buildType,
                    onClick = { context.copyToClipboard(versionLine, copied) },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.pref_app_update_check),
                    detail = stringResource(
                        if (updateState.checking) R.string.pref_app_update_checking else R.string.pref_app_update_check_detail,
                    ),
                    icon = Icons.Outlined.Update,
                    onClick = checkForUpdates,
                )
            }

            item { SectionLabel(stringResource(R.string.about_section_builds)) }
            when (val builds = state.builds) {
                AboutViewModel.Builds.Loading -> item {
                    IconListRow(
                        title = stringResource(R.string.about_builds_loading),
                        icon = Icons.Outlined.NewReleases,
                        onClick = null,
                        trailing = { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) },
                    )
                }
                AboutViewModel.Builds.Failed -> item {
                    IconListRow(
                        title = stringResource(R.string.about_builds_failed),
                        icon = Icons.Outlined.CloudOff,
                        onClick = viewModel::loadBuilds,
                        trailing = {
                            TextButton(onClick = viewModel::loadBuilds) { Text(stringResource(R.string.action_retry)) }
                        },
                    )
                }
                is AboutViewModel.Builds.Loaded -> {
                    if (builds.assets.isEmpty()) item { InlineNotice(stringResource(R.string.about_builds_none)) }
                    builds.assets.forEach { asset ->
                        item(key = asset.url) {
                            BuildRow(
                                asset = asset,
                                onUpdate = checkForUpdates,
                                onDownload = { navigator.openUrl(asset.url) },
                            )
                        }
                    }
                }
            }
            item {
                IconListRow(
                    title = stringResource(R.string.about_all_releases),
                    detail = stringResource(R.string.about_all_releases_detail),
                    icon = Icons.Outlined.NewReleases,
                    onClick = { navigator.openUrl("$REPOSITORY_URL/releases") },
                )
            }

            item { SectionLabel(stringResource(R.string.about_section_help)) }
            item {
                IconListRow(
                    title = stringResource(R.string.about_share_bug_report),
                    detail = stringResource(R.string.about_share_bug_report_detail) + "\n" +
                        stringResource(R.string.about_bug_report_privacy),
                    icon = Icons.Outlined.BugReport,
                    onClick = viewModel::shareBugReport,
                    trailing = if (state.writingReport) {
                        { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.about_copy_device_info),
                    detail = stringResource(R.string.about_copy_device_info_detail),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = { context.copyToClipboard(info.header(), copied) },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.about_report_issue),
                    detail = stringResource(R.string.about_report_issue_detail),
                    icon = Icons.Outlined.Feedback,
                    onClick = { navigator.openUrl("$REPOSITORY_URL/issues/new/choose") },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.about_discord),
                    detail = stringResource(R.string.about_discord_detail),
                    icon = Icons.Outlined.Forum,
                    onClick = { navigator.openUrl(DISCORD_URL) },
                )
            }
            item {
                IconListRow(
                    title = stringResource(R.string.about_website),
                    detail = stringResource(R.string.about_website_detail),
                    icon = Icons.Outlined.Public,
                    onClick = { navigator.openUrl(WEBSITE_URL) },
                )
            }
        }
    }
}

/**
 * The mark, the app's name, the version and the build, as one button that copies them on one line
 * for a report; the More tab's header, with the name and the build added.
 */
@Composable
private fun VersionBlock(info: DeviceInfo, buildType: String, onClick: () -> Unit) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(R.string.about_copy_version),
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(AppR.drawable.ic_saikou_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = info.appName,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.about_version_format, info.versionName, info.versionCode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.about_build_format, buildType, info.commit),
                modifier = Modifier.secondaryItemAlpha(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
    }
}

/**
 * One APK of the latest release: which build it is and what it is for, then its file, version and
 * size. The trailing word says what a tap does: Update through the in-app updater, Download in the
 * browser, or nothing for the version already installed.
 */
@Composable
private fun BuildRow(asset: BuildAsset, onUpdate: () -> Unit, onDownload: () -> Unit) {
    val context = LocalContext.current
    val debug = asset.kind == ApkKind.DEBUG
    val what = if (debug) {
        stringResource(R.string.about_build_debug_detail, stringResource(R.string.app_name_debug))
    } else {
        stringResource(R.string.about_build_release_detail)
    }
    val file = stringResource(
        R.string.about_build_file_format,
        asset.name,
        asset.version,
        Formatter.formatShortFileSize(context, asset.sizeBytes),
    )
    val onClick = when (asset.action) {
        BuildAsset.Action.INSTALLED -> null
        BuildAsset.Action.UPDATE -> onUpdate
        BuildAsset.Action.DOWNLOAD -> onDownload
    }
    IconListRow(
        title = stringResource(if (debug) R.string.about_build_debug_title else R.string.about_build_release_title),
        detail = "$what\n$file",
        icon = if (debug) Icons.Outlined.DeveloperMode else Icons.Outlined.Android,
        onClick = onClick,
        trailing = {
            when (asset.action) {
                BuildAsset.Action.INSTALLED -> Text(
                    text = stringResource(R.string.about_build_installed),
                    modifier = Modifier.secondaryItemAlpha(),
                    style = MaterialTheme.typography.labelLarge,
                )
                BuildAsset.Action.UPDATE -> TextButton(onClick = onUpdate) { Text(stringResource(R.string.app_update_update)) }
                BuildAsset.Action.DOWNLOAD -> TextButton(onClick = onDownload) { Text(stringResource(R.string.about_build_download)) }
            }
        },
    )
}
