package com.shinsaikou.ui.browse

import android.content.res.Resources
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.i18n.R
import com.shinsaikou.messages.messageRes
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.SearchField
import com.shinsaikou.ui.util.ErrorText
import com.shinsaikou.ui.util.copyToClipboard
import com.shinsaikou.ui.util.languageName
import eu.kanade.tachiyomi.extension.model.InstallStep
import tachiyomi.presentation.core.components.DropdownMenu
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The Extensions page of the Browse tab.
 *
 * The list is one lazy column cut into the view model's groups rather than three fixed sections, so
 * "update available", "did not load" and one heading per available language all read the same way
 * and the search field narrows every one of them at once.
 *
 * An installed row still leads to the detail screen rather than acting in place: Uninstall wants
 * the version, the library version and the signer in front of the owner first. Update does not,
 * which is why a row with an update pending carries the button itself, and Uninstall sits in the
 * row's overflow rather than becoming a second full-width action. A row that did not load has no
 * detail screen, so a tap shows why it did not, and its overflow still uninstalls it: upstream's
 * e6d8efe6f and 2d86a70ce, so such a row is never a dead end.
 */
@Composable
fun ExtensionsTab(
    viewModel: ExtensionsViewModel,
    state: ExtensionsViewModel.State,
    padding: PaddingValues,
    initialQuery: String? = null,
) {
    val navigator = LocalNavigator.current

    // The field's text is the page's own state and the view model's query follows it, as the
    // library's search does. The view model's copy comes back through a flow on the IO dispatcher a
    // frame or more after the key, and a text field recomposed with that older text moves its
    // cursor and loses what was typed in between: "Asura" typed a key at a time came out as "suraA"
    // on the owner's phone (2026-10-02). It starts from the view model's query, which outlives this
    // page while Browse shows another one, and it is saved for a process the system reclaimed.
    // [initialQuery], a search another screen asked for (the restore report's Open Extensions),
    // starts it instead, and reaches the view model through the same effect.
    var query by rememberSaveable { mutableStateOf(initialQuery ?: state.searchQuery.orEmpty()) }
    // An empty field is no search, so a page with nothing to search hides the field again.
    LaunchedEffect(query) { viewModel.search(query.ifEmpty { null }) }

    if (!state.isInitialized) {
        LoadingScreen(Modifier.padding(padding))
        return
    }

    PullRefresh(
        refreshing = state.isRefreshing,
        enabled = true,
        onRefresh = viewModel::refresh,
        indicatorPadding = padding,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Above the list rather than inside it: a failed index fetch must not push the
            // installed extensions out of reach, and it stays until it is dismissed.
            state.refreshError?.let { error ->
                RefreshErrorBanner(error = error, onDismiss = viewModel::dismissRefreshError)
            }
            ExtensionsHeader(
                storeCount = state.storeCount,
                hasUpdates = state.hasUpdates,
                onStores = { navigator.open(Route.ExtensionStores()) },
                onUpdateAll = viewModel::updateAll,
            )
            // Hidden only when there is nothing to search and nothing typed: a query that matches
            // nothing has to leave the field on screen, or there is no way to clear it. Both halves
            // are the view model's, so they describe the same projection: the page's own text runs
            // a frame ahead, and a field cleared while its old query matched nothing would
            // otherwise leave the composition for that frame and take the keyboard with it.
            if (!state.isEmpty || state.searchQuery != null) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.ext_search_hint),
                )
            }
            when {
                !state.isEmpty -> ExtensionList(viewModel = viewModel, state = state)
                state.searchQuery != null -> InlineNotice(stringResource(R.string.empty_search_results))
                state.storeCount == 0L -> EmptyScreen(
                    stringRes = R.string.ext_empty_no_stores,
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = R.string.ext_stores_add,
                            icon = Icons.Outlined.Add,
                            onClick = { navigator.open(Route.ExtensionStores()) },
                        ),
                    ),
                )
                else -> EmptyScreen(
                    stringRes = R.string.ext_empty,
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = R.string.action_refresh,
                            icon = Icons.Outlined.Refresh,
                            onClick = viewModel::refresh,
                        ),
                    ),
                )
            }
        }
    }

    when (val dialog = state.dialog) {
        is ExtensionsViewModel.Dialog.Trust -> ExtensionTrustDialog(
            ext = dialog.ext,
            onConfirm = { viewModel.trust(dialog.ext) },
            onDismiss = viewModel::dismissDialog,
        )
        is ExtensionsViewModel.Dialog.Uninstall -> ExtensionUninstallDialog(
            name = dialog.row.title(),
            onConfirm = { viewModel.uninstall(dialog.row) },
            onDismiss = viewModel::dismissDialog,
        )
        is ExtensionsViewModel.Dialog.LoadErrorDetail -> {
            val row = ExtensionUi.LoadError(dialog.error)
            ExtensionLoadErrorDialog(
                error = dialog.error,
                onUninstall = if (row.canUninstall()) {
                    { viewModel.showDialog(ExtensionsViewModel.Dialog.Uninstall(row)) }
                } else {
                    null
                },
                onDismiss = viewModel::dismissDialog,
            )
        }
        null -> Unit
    }
}

@Composable
private fun ExtensionList(viewModel: ExtensionsViewModel, state: ExtensionsViewModel.State) {
    val navigator = LocalNavigator.current
    val openDetail: (String) -> Unit = { navigator.open(Route.ExtensionDetail(it)) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        state.groups.forEach { (group, rows) ->
            item(key = group.headerKey()) { ListGroupHeader(text = group.label()) }
            // A file that could not be read as a package at all reports no package name, so two of
            // them would share a key. The position inside its own group is what separates them.
            itemsIndexed(
                items = rows,
                key = { index, row -> "${group.headerKey()}:$index:${row.packageName}" },
            ) { _, row ->
                val explain: () -> Unit = {
                    when (row) {
                        is ExtensionUi.Untrusted -> viewModel.showDialog(ExtensionsViewModel.Dialog.Trust(row.ext))
                        is ExtensionUi.LoadError ->
                            viewModel.showDialog(ExtensionsViewModel.Dialog.LoadErrorDetail(row.error))
                        else -> openDetail(row.packageName)
                    }
                }
                ExtensionItem(
                    row = row,
                    installStep = state.installSteps[row.packageName],
                    installFailure = state.installFailures[row.packageName],
                    // A row with something on disk opens what there is to know about it: the detail
                    // screen when it loaded, the reason it did not otherwise.
                    onClick = if (row is ExtensionUi.Available) null else explain,
                    onAction = {
                        when (row) {
                            is ExtensionUi.Available -> viewModel.install(row.ext)
                            is ExtensionUi.Update -> viewModel.update(row.ext)
                            is ExtensionUi.Untrusted, is ExtensionUi.LoadError ->
                                if (row.notLoadedUpdate()) viewModel.updateNotLoaded(row.packageName) else explain()
                            is ExtensionUi.Installed -> openDetail(row.packageName)
                        }
                    },
                    onCancel = { viewModel.cancelInstall(row.packageName) },
                    onUninstall = if (row.canUninstall()) {
                        { viewModel.showDialog(ExtensionsViewModel.Dialog.Uninstall(row)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * The header row: how many repositories are configured, and the one action that covers every
 * pending update at once. Refreshing is the pull gesture over the list and the empty screen's own
 * action, so it does not take a third seat here.
 */
@Composable
private fun ExtensionsHeader(
    storeCount: Long,
    hasUpdates: Boolean,
    onStores: () -> Unit,
    onUpdateAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onStores) {
            Text(stringResource(R.string.ext_stores_count_format, storeCount))
        }
        Spacer(Modifier.weight(1f))
        if (hasUpdates) {
            Button(onClick = onUpdateAll) { Text(stringResource(R.string.ext_update_all)) }
        }
    }
}

@Composable
private fun RefreshErrorBanner(error: Throwable, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ext_refresh_failed, RepoErrorText.of(resources, error)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}

/**
 * One extension. The trailing control is the row's action, except while an install is in flight:
 * then it is a progress indicator that cancels on a tap, because a download the owner started by
 * mistake is otherwise only stopped by killing the app. After a failed install the action reads
 * Retry and the reason takes the version's place, or a refused update would look like nothing at all.
 */
@Composable
private fun ExtensionItem(
    row: ExtensionUi,
    installStep: InstallStep?,
    installFailure: Throwable?,
    onClick: (() -> Unit)?,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: (() -> Unit)?,
) {
    val failed = installStep == InstallStep.Error && installFailure != null
    ListRowLayout(
        onClick = onClick,
        leading = { ExtensionRowIcon(row) },
        trailing = {
            if (installStep != null && !installStep.isCompleted()) {
                IconButton(onClick = onCancel) {
                    Box(contentAlignment = Alignment.Center) {
                        // Indeterminate for every step, as on the detail screen (ruling R54): the
                        // step is not a measurement, and the detail line names it instead.
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ext_action_cancel_install),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                val actionRes = if (failed) R.string.action_retry else row.actionRes()
                // An entry built for a lib this host does not load would only be refused.
                val enabled = (row as? ExtensionUi.Available)?.libSupported != false
                TextButton(onClick = onAction, enabled = enabled) { Text(stringResource(actionRes)) }
            }
            if (onUninstall != null) {
                ExtensionOverflow(onUninstall = onUninstall)
            }
        },
    ) {
        ListRowTitle(row.title())
        if (failed && installFailure != null) {
            Text(
                installFailureText(LocalResources.current, installFailure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        } else {
            ExtensionDetailLine(row, installStep)
        }
    }
}

/** Why an install failed, in words: the installer's refusal, or the download's own failure. */
internal fun installFailureText(resources: Resources, failure: Throwable): String =
    (failure as? InstallFailedException)?.let { resources.getString(it.reason.messageRes) }
        ?: ErrorText.of(resources, failure)

/**
 * The index's icon for an extension not yet installed, and the installed package's own launcher
 * icon for one that is, read out of its `.ext` file by the image loader as upstream's `ExtensionIcon`
 * reads it (off the main thread, so not on the path to this list's first frame). An untrusted
 * extension and a load error have no icon to show and keep the generic mark.
 */
@Composable
private fun ExtensionRowIcon(row: ExtensionUi) {
    when (row) {
        is ExtensionUi.Available -> AsyncImage(
            model = row.ext.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        is ExtensionUi.Installed -> ExtensionIconImage(ExtensionIcon.OfPackage(row.ext.packageName), 40.dp)
        is ExtensionUi.Update -> ExtensionIconImage(ExtensionIcon.OfPackage(row.ext.packageName), 40.dp)
        else -> ExtensionIconImage(null, 40.dp)
    }
}

/**
 * The version and the pills. While an install or an update is in flight the step is named in the
 * version's place, as upstream names it, because the ring beside the row no longer says anything
 * about how far along it is (ruling R54). The version comes back when the step completes.
 */
@Composable
private fun ExtensionDetailLine(row: ExtensionUi, installStep: InstallStep?) {
    val error = (row as? ExtensionUi.LoadError)?.error
    if (error != null) {
        Text(
            stringResource(error.reason.messageRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
        )
        return
    }
    val ext = row.extension() ?: return
    val stepRes = installStep?.labelRes()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stepRes != null) {
            Text(
                stringResource(stepRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        } else {
            Text(
                stringResource(R.string.ext_version_format, ext.versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Pill(
            text = stringResource(R.string.ext_lib_pill_format, ext.libVersion.toString()),
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
        )
        if (ext.isNsfw) {
            Pill(
                text = stringResource(R.string.ext_detail_nsfw),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
            )
        }
        if (ext.lang.isNotBlank()) {
            Pill(
                text = languageLabel(ext.lang),
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
            )
        }
        val libLabel = when {
            row is ExtensionUi.Available && !row.libSupported -> R.string.ext_lib_needs_newer_app
            row is ExtensionUi.Installed && row.updateNeedsNewerApp -> R.string.ext_update_needs_newer_app
            else -> null
        }
        if (libLabel != null) {
            Pill(
                text = stringResource(libLabel),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
            )
        }
    }
}

@Composable
private fun ExtensionOverflow(onUninstall: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.ext_row_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_uninstall)) },
                onClick = {
                    expanded = false
                    onUninstall()
                },
            )
        }
    }
}

/** The signer hash is the whole point of this dialog, so it is shown in full rather than truncated. */
@Composable
private fun ExtensionTrustDialog(
    ext: Extension.Untrusted,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_trust_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ext_trust_message_format, ext.name))
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.ext_trust_signer_format, ext.signatureHash),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_trust)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ExtensionUninstallDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_uninstall_title)) },
        text = { Text(stringResource(R.string.ext_uninstall_message_format, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_uninstall)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Why an extension did not load. The throwable's message is shown and its whole stack trace can be
 * copied, because that is what a report about it needs; Uninstall is here too, as upstream's has it.
 */
@Composable
private fun ExtensionLoadErrorDialog(error: LoadResult.Error, onUninstall: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_load_error_title)) },
        text = {
            Column {
                Text(
                    error.packageName
                        ?.let { stringResource(R.string.ext_load_error_package_format, it) }
                        ?: stringResource(R.string.ext_load_error_unknown_package),
                )
                Spacer(Modifier.size(12.dp))
                Text(stringResource(error.reason.messageRes))
                // The throwable's own text is the only thing that separates two class load
                // failures, and it is the line the owner would paste into a report.
                error.cause?.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Spacer(Modifier.size(12.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error.cause?.let { cause ->
                    TextButton(
                        onClick = {
                            context.copyToClipboard(
                                cause.stackTraceToString(),
                                resources.getString(R.string.copied_to_clipboard),
                            )
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.ext_load_error_copy_trace))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = onUninstall?.let {
            { TextButton(onClick = it) { Text(stringResource(R.string.action_uninstall)) } }
        },
    )
}

private fun ExtensionUi.actionRes(): Int = when {
    this is ExtensionUi.Available -> R.string.action_install
    this is ExtensionUi.Update || notLoadedUpdate() -> R.string.action_update
    this is ExtensionUi.Untrusted -> R.string.action_trust
    this is ExtensionUi.LoadError -> R.string.ext_action_show
    else -> R.string.action_details
}

/** A row that did not load, with an update in an index: its action updates it. */
private fun ExtensionUi.notLoadedUpdate(): Boolean =
    (this as? ExtensionUi.Untrusted)?.hasUpdate == true || (this as? ExtensionUi.LoadError)?.hasUpdate == true

private fun ExtensionUi.extension(): Extension? = when (this) {
    is ExtensionUi.Available -> ext
    is ExtensionUi.Update -> ext
    is ExtensionUi.Untrusted -> ext
    is ExtensionUi.Installed -> ext
    is ExtensionUi.LoadError -> null
}

/**
 * The rows an uninstall applies to: everything on disk, loaded or not. A file that could not even be
 * read as a package goes by its path, the one thing known about it.
 */
private fun ExtensionUi.canUninstall(): Boolean = when (this) {
    is ExtensionUi.Update, is ExtensionUi.Installed, is ExtensionUi.Untrusted -> true
    is ExtensionUi.LoadError -> error.packageName != null || error.file != null
    is ExtensionUi.Available -> false
}

private fun ExtensionUi.title(): String = extension()?.name
    ?: (this as? ExtensionUi.LoadError)?.error?.let { it.name ?: it.packageName ?: it.file?.name }
    ?: packageName

private fun ExtensionGroup.headerKey(): String = when (this) {
    ExtensionGroup.Updates -> "updates"
    ExtensionGroup.Installed -> "installed"
    ExtensionGroup.Untrusted -> "untrusted"
    ExtensionGroup.Errors -> "errors"
    is ExtensionGroup.AvailableLanguage -> "lang:$code"
}

@Composable
private fun ExtensionGroup.label(): String = when (this) {
    ExtensionGroup.Updates -> stringResource(R.string.ext_section_updates)
    ExtensionGroup.Installed -> stringResource(R.string.ext_section_installed)
    ExtensionGroup.Untrusted -> stringResource(R.string.ext_section_untrusted)
    ExtensionGroup.Errors -> stringResource(R.string.ext_section_errors)
    is ExtensionGroup.AvailableLanguage -> languageLabel(code)
}

/** "all" and the empty code are contract values with no locale behind them. */
@Composable
private fun languageLabel(code: String): String = when {
    code == "all" -> stringResource(R.string.ext_language_all)
    code.isBlank() -> stringResource(R.string.ext_section_available)
    else -> languageName(code)
}

/** An in-flight step in words; null once it has finished, whichever way it finished. */
internal fun InstallStep.labelRes(): Int? = when (this) {
    InstallStep.Pending -> R.string.ext_install_pending
    InstallStep.Downloading -> R.string.ext_install_downloading
    InstallStep.Installing -> R.string.ext_install_installing
    InstallStep.Idle, InstallStep.Installed, InstallStep.Error -> null
}
