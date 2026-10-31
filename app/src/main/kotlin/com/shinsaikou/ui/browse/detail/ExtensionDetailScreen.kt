package com.shinsaikou.ui.browse.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.ui.browse.ExtensionIcon
import com.shinsaikou.ui.browse.ExtensionIconImage
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SectionLabel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The tag on the two pane layout's root, for the same reason Settings and the library carry one:
 * both layouts draw the same summary and the same source rows, so nothing visible tells them apart.
 */
const val EXTENSION_DETAIL_TWO_PANE_TAG = "extension_detail_two_pane"

/**
 * One installed extension, opened from the Browse tab's extension list.
 *
 * Update and Uninstall live here rather than on the list row: both want the version, the library
 * version and the signer in front of the owner before they are tapped, and the list has room for
 * one action per row.
 *
 * Navigation is driven by the view model's events rather than by reading [state] in a
 * `LaunchedEffect`. Popping on an uninstall has to happen exactly once, and a state that survives
 * a configuration change would fire again on every recomposition after it.
 */
@Composable
fun ExtensionDetailScreen(packageName: String) {
    val viewModel: ExtensionDetailViewModel =
        assistedMetroViewModel<ExtensionDetailViewModel, ExtensionDetailViewModel.Factory> {
            create(packageName)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ExtensionDetailViewModel.Event.Back -> navigator.back()
                is ExtensionDetailViewModel.Event.OpenSourceSettings ->
                    navigator.open(Route.SourceSettings(event.sourceId))
                is ExtensionDetailViewModel.Event.OpenWebView -> navigator.open(
                    Route.WebView(url = event.url, sourceId = event.sourceId, title = event.title),
                )
            }
        }
    }

    val success = state as? ExtensionDetailViewModel.State.Success

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = success?.ext?.name ?: stringResource(R.string.ext_detail_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            success != null -> DetailBody(state = success, viewModel = viewModel, padding = padding)
            state is ExtensionDetailViewModel.State.NotFound -> EmptyScreen(
                stringRes = R.string.ext_detail_not_found,
                modifier = Modifier.padding(padding),
            )
            else -> LoadingScreen(Modifier.padding(padding))
        }
    }

    if (success != null && success.dialog is ExtensionDetailViewModel.Dialog.Uninstall) {
        UninstallDialog(
            name = success.ext.name,
            onConfirm = viewModel::uninstall,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

/**
 * Two panels on a tablet, one column on a phone. The split is by content rather than by half the
 * rows: what an extension is barely scrolls, and the source list is the part that grows with the
 * extension, so giving it the wider panel is what the extra width buys.
 */
@Composable
private fun DetailBody(
    state: ExtensionDetailViewModel.State.Success,
    viewModel: ExtensionDetailViewModel,
    padding: PaddingValues,
) {
    if (isTabletUi()) {
        TwoPanelBox(
            startContent = {
                LazyColumn(modifier = Modifier.fillMaxSize()) { summaryItems(state, viewModel) }
            },
            endContent = {
                LazyColumn(modifier = Modifier.fillMaxSize()) { sourceItems(state, viewModel) }
            },
            modifier = Modifier.padding(padding).testTag(EXTENSION_DETAIL_TWO_PANE_TAG),
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            summaryItems(state, viewModel)
            sourceItems(state, viewModel)
        }
    }
}

private fun LazyListScope.summaryItems(
    state: ExtensionDetailViewModel.State.Success,
    viewModel: ExtensionDetailViewModel,
) {
    item { ExtensionHeader(state.ext) }
    item {
        ActionRow(
            canUpdate = state.canUpdate,
            busy = state.busy,
            onUpdate = viewModel::update,
            onUninstall = viewModel::requestUninstall,
        )
    }
    item {
        IncognitoRow(
            checked = state.incognito,
            onToggle = { viewModel.toggleIncognito(!state.incognito) },
        )
    }
}

private fun LazyListScope.sourceItems(
    state: ExtensionDetailViewModel.State.Success,
    viewModel: ExtensionDetailViewModel,
) {
    item {
        SourcesHeader(
            onEnableAll = viewModel::enableAll,
            onDisableAll = viewModel::disableAll,
        )
    }
    items(state.sources, key = { it.id }) { row ->
        SourceSwitchRow(
            row = row,
            onToggle = { viewModel.toggleSource(row.id, !row.enabled) },
            onSettings = { viewModel.openSourceSettings(row.id) },
            onOpenPage = { viewModel.openWebView(row) },
        )
    }
}

/**
 * The identity block. The icon is the package's own launcher icon, which the image loader reads out
 * of the `.ext` file off the main thread, as the extension list's row does.
 *
 * The signer is truncated, the way the store list truncates it. A full fingerprint is unreadable at
 * a glance and what the owner is checking here is whether it changed.
 */
@Composable
private fun ExtensionHeader(ext: Extension.Installed) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionIconImage(ExtensionIcon.OfPackage(ext.packageName), 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(ext.name, style = MaterialTheme.typography.titleLarge)
            DetailLine(stringResource(R.string.ext_detail_package_format, ext.packageName))
            DetailLine(stringResource(R.string.ext_detail_version_format, ext.versionName))
            DetailLine(stringResource(R.string.ext_detail_lib_format, ext.libVersion.toString()))
            val signer = ext.store?.signingKey
            if (!signer.isNullOrBlank()) {
                DetailLine(stringResource(R.string.ext_detail_signer_format, signer.take(SIGNER_DIGITS)))
            }
            if (ext.isNsfw) {
                Text(
                    stringResource(R.string.ext_detail_nsfw),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Update only appears when there is one to take; Uninstall is always the second button. */
@Composable
private fun ActionRow(
    canUpdate: Boolean,
    busy: Boolean,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canUpdate) {
            Button(onClick = onUpdate, enabled = !busy) {
                Text(stringResource(R.string.action_update))
            }
        }
        OutlinedButton(onClick = onUninstall, enabled = !busy) {
            Text(stringResource(R.string.action_uninstall))
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Incognito is the extension's, not a single source's: it is stored by package name, so flipping it
 * here silently covers every source the extension provides. The summary line says so, because the
 * switch's own label does not.
 */
@Composable
private fun IncognitoRow(checked: Boolean, onToggle: () -> Unit) {
    ListRowLayout(
        onClick = onToggle,
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    ) {
        ListRowTitle(stringResource(R.string.ext_detail_incognito))
        ListRowDetail(stringResource(R.string.ext_detail_incognito_summary))
    }
}

@Composable
private fun SourcesHeader(onEnableAll: () -> Unit, onDisableAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SectionLabel(stringResource(R.string.ext_detail_sources))
        }
        TextButton(onClick = onEnableAll) { Text(stringResource(R.string.ext_detail_enable_all)) }
        TextButton(onClick = onDisableAll) { Text(stringResource(R.string.ext_detail_disable_all)) }
    }
}

/**
 * The whole row is the switch's target, the way the More tab's toggles work, so the two icon
 * buttons are the only parts of the row that do something else. Both are hidden rather than
 * disabled when they do not apply: a source with no settings and no web presence should read as a
 * name and a switch.
 */
@Composable
private fun SourceSwitchRow(
    row: ExtensionDetailViewModel.SourceRow,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onOpenPage: () -> Unit,
) {
    ListRowLayout(
        onClick = onToggle,
        trailing = {
            if (row.configurable) {
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                    )
                }
            }
            if (row.homeUrl != null) {
                IconButton(onClick = onOpenPage) {
                    Icon(
                        Icons.Outlined.Public,
                        contentDescription = stringResource(R.string.action_open_in_web_view),
                    )
                }
            }
            Switch(checked = row.enabled, onCheckedChange = null)
        },
    ) {
        ListRowTitle(row.name)
        if (row.lang.isNotBlank()) ListRowDetail(row.lang)
    }
}

@Composable
private fun UninstallDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_uninstall_title)) },
        text = { Text(stringResource(R.string.ext_uninstall_message_format, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Enough of the signer to spot a change, short enough to read on a phone. */
private const val SIGNER_DIGITS = 16
