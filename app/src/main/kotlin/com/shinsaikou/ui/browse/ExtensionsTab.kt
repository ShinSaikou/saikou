package com.shinsaikou.ui.browse

import android.content.res.Resources
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.i18n.R
import com.shinsaikou.messages.messageRes
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.SearchTopBar
import com.shinsaikou.ui.util.ErrorText
import com.shinsaikou.ui.util.copyToClipboard
import com.shinsaikou.ui.util.languageName
import com.shinsaikou.ui.components.TopBarAction
import com.shinsaikou.ui.components.TopBarMenuItem
import com.shinsaikou.ui.components.TopBarOverflow
import eu.kanade.tachiyomi.extension.model.InstallStep
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * The Extensions page of the Browse tab, laid out as upstream's `ExtensionScreen`
 * (`presentation/browse/ExtensionsScreen.kt` at v0.20.4): one list cut into the view model's groups,
 * Updates pending with its Update all, Installed, and one heading per available language, then the
 * rows of upstream's `ExtensionItem`. Saikou's own groups, "Needs trust" and "Did not load", sit after
 * Installed, where upstream lists its untrusted extensions. The search and the filter are in the
 * Browse bar ([ExtensionsTopBar]), and the page refreshes on a pull.
 *
 * A tap does what upstream's does: an available row installs, an installed row opens the detail
 * screen, and a row that did not load says why. A long press uninstalls whatever is on disk, after
 * the confirmation, and installs an available row, as upstream's long press does. A row that did not
 * load still uninstalls from its dialog too (upstream's e6d8efe6f and 2d86a70ce), so it is never a
 * dead end.
 */
@Composable
fun ExtensionsTab(
    viewModel: ExtensionsViewModel,
    state: ExtensionsViewModel.State,
    padding: PaddingValues,
) {
    val navigator = LocalNavigator.current

    if (!state.isInitialized) {
        LoadingScreen(Modifier.padding(padding))
        return
    }

    PullRefresh(
        refreshing = state.isRefreshing || state.isRefreshingStore,
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
            when {
                !state.isEmpty -> ExtensionList(viewModel = viewModel, state = state)
                // A repository just added is still being read: what it offers is on its way.
                state.isRefreshingStore -> LoadingScreen()
                state.searchQuery != null -> EmptyScreen(stringRes = R.string.empty_search_results)
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

/**
 * The Extensions page's search, held by Browse rather than by the page: the field is in the bar, and
 * the bar outlives the page while Browse shows another one. The text is the screen's own state and
 * the view model's query follows it, as the library's search does. The view model's copy comes back
 * through a flow on the IO dispatcher a frame or more after the key, and a text field recomposed with
 * that older text moves its cursor and loses what was typed in between: "Asura" typed a key at a time
 * came out as "suraA" on the owner's phone (2026-10-02).
 *
 * Null is a closed search and "" an open one with nothing typed; the view model sees no query for
 * either. It starts from [initialQuery], a search another screen asked for (the restore report's Open
 * Extensions), else from the view model's query, which outlives Browse's composition, and it is saved
 * for a process the system reclaimed.
 */
@Composable
fun rememberExtensionsSearch(viewModel: ExtensionsViewModel, initialQuery: String? = null): MutableState<String?> {
    val search = rememberSaveable { mutableStateOf(initialQuery ?: viewModel.state.value.searchQuery) }
    LaunchedEffect(search.value) { viewModel.search(search.value?.ifEmpty { null }) }
    return search
}

/**
 * Browse's bar while the Extensions page is shown, as upstream's: the search opens in the bar, then
 * the language filter, and the repositories in the overflow, named with how many are configured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsTopBar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    storeCount: Long,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val navigator = LocalNavigator.current
    SearchTopBar(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        placeholder = stringResource(R.string.ext_search_hint),
        scrollBehavior = scrollBehavior,
        actions = {
            TopBarAction(
                label = stringResource(R.string.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.open(Route.ExtensionFilter) },
            )
            // One item, as upstream's overflow on this page holds; the count names how many
            // repositories there are without opening it.
            TopBarOverflow(
                listOf(
                    TopBarMenuItem(stringResource(R.string.ext_stores_count_format, storeCount)) {
                        navigator.open(Route.ExtensionStores())
                    },
                ),
            )
        },
    )
}

@Composable
private fun ExtensionList(viewModel: ExtensionsViewModel, state: ExtensionsViewModel.State) {
    val navigator = LocalNavigator.current
    val openDetail: (String) -> Unit = { navigator.open(Route.ExtensionDetail(it)) }

    FastScrollLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = topSmallPaddingValues) {
        state.groups.forEach { (group, rows) ->
            item(key = group.headerKey(), contentType = "header") {
                ExtensionHeader(
                    text = group.label(),
                    modifier = Modifier.animateItem(),
                    action = {
                        if (group == ExtensionGroup.Updates) {
                            Button(onClick = viewModel::updateAll) { Text(stringResource(R.string.ext_update_all)) }
                        }
                    },
                )
            }
            // A file that could not be read as a package at all reports no package name, so two of
            // them would share a key. The position inside its own group is what separates them.
            itemsIndexed(
                items = rows,
                key = { index, row -> "${group.headerKey()}:$index:${row.packageName}" },
                contentType = { _, _ -> "item" },
            ) { _, row ->
                val explain: () -> Unit = {
                    when (row) {
                        is ExtensionUi.Untrusted -> viewModel.showDialog(ExtensionsViewModel.Dialog.Trust(row.ext))
                        is ExtensionUi.LoadError ->
                            viewModel.showDialog(ExtensionsViewModel.Dialog.LoadErrorDetail(row.error))
                        else -> openDetail(row.packageName)
                    }
                }
                val install: () -> Unit = {
                    if (row is ExtensionUi.Available && row.libSupported) viewModel.install(row.ext)
                }
                ExtensionItem(
                    row = row,
                    installStep = state.installSteps[row.packageName],
                    installFailure = state.installFailures[row.packageName],
                    modifier = Modifier.animateItem(),
                    onClick = if (row is ExtensionUi.Available) install else explain,
                    onLongClick = when {
                        row is ExtensionUi.Available -> install
                        row.canUninstall() -> {
                            { viewModel.showDialog(ExtensionsViewModel.Dialog.Uninstall(row)) }
                        }
                        else -> null
                    },
                    onAction = {
                        when (row) {
                            is ExtensionUi.Available -> install()
                            is ExtensionUi.Update -> viewModel.update(row.ext)
                            is ExtensionUi.Untrusted, is ExtensionUi.LoadError ->
                                if (row.notLoadedUpdate()) viewModel.updateNotLoaded(row.packageName) else explain()
                            is ExtensionUi.Installed -> openDetail(row.packageName)
                        }
                    },
                    onSecondaryAction = {
                        val source = (row as? ExtensionUi.Available)?.ext?.webSource()
                        if (source != null) {
                            navigator.open(Route.WebView(url = source.homeUrl, title = source.name))
                        } else {
                            explain()
                        }
                    },
                    onCancel = { viewModel.cancelInstall(row.packageName) },
                )
            }
        }
    }
}

/** Upstream's `ExtensionHeader`: the group's name, and the group's own action at its end. */
@Composable
private fun ExtensionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .weight(1f),
            style = MaterialTheme.typography.header,
        )
        action()
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
 * One extension, as upstream's `ExtensionItem` and `BaseBrowseItem` draw it: the icon, the name over
 * one line of what it is, and its actions as icons. While an install or an update is in flight a ring
 * turns around the icon, which shrinks into it, and the only action is cancel, because a download
 * the owner started by mistake is otherwise only stopped by killing the app. The row itself takes no
 * tap or long press meanwhile, or a second tap on an available row would start a second install of
 * the same package. After a failed install the action is Retry and the reason takes the line's place,
 * or a refused update would look like nothing at all.
 */
@Composable
private fun ExtensionItem(
    row: ExtensionUi,
    installStep: InstallStep?,
    installFailure: Throwable?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(enabled = !installStep.isInFlight(), onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionItemIcon(row = row, installStep = installStep)
        Column(modifier = Modifier.weight(1f).padding(start = MaterialTheme.padding.medium)) {
            Text(
                text = row.title(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            val failure = installFailure.takeIf { installStep == InstallStep.Error }
            if (failure != null) {
                Text(
                    installFailureText(LocalResources.current, failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                ExtensionItemDetails(row = row, installStep = installStep)
            }
        }
        ExtensionItemActions(
            row = row,
            installStep = installStep,
            onAction = onAction,
            onSecondaryAction = onSecondaryAction,
            onCancel = onCancel,
        )
    }
}

/** Why an install failed, in words: the installer's refusal, or the download's own failure. */
internal fun installFailureText(resources: Resources, failure: Throwable): String =
    (failure as? InstallFailedException)?.let { resources.getString(it.reason.messageRes) }
        ?: ErrorText.of(resources, failure)

/**
 * The index's icon for an extension not yet installed, and the installed package's own launcher icon
 * for one that is, read out of its `.ext` file by the image loader as upstream's `ExtensionIcon`
 * reads it (off the main thread, so not on the path to this list's first frame). A row that did not
 * load has no icon to show and draws upstream's mark for an untrusted one, in the error colour.
 */
@Composable
private fun ExtensionItemIcon(row: ExtensionUi, installStep: InstallStep?) {
    Box(modifier = Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
        val idle = !installStep.isInFlight()
        if (!idle) {
            // Indeterminate for every step, as on the detail screen (ruling R54): the step is not a
            // measurement, and the line under the name names it instead.
            CircularProgressIndicator(modifier = Modifier.size(ICON_SIZE), strokeWidth = 2.dp)
        }
        val padding by animateDpAsState(targetValue = if (idle) 0.dp else 8.dp, label = "iconPadding")
        val iconModifier = Modifier.matchParentSize().padding(padding)
        when (row) {
            is ExtensionUi.Available -> AvailableExtensionIcon(row.ext.iconUrl, iconModifier)
            is ExtensionUi.Installed -> ExtensionIconImage(ExtensionIcon.OfPackage(row.ext.packageName), iconModifier)
            is ExtensionUi.Update -> ExtensionIconImage(ExtensionIcon.OfPackage(row.ext.packageName), iconModifier)
            is ExtensionUi.Untrusted, is ExtensionUi.LoadError -> Icon(
                imageVector = Icons.Filled.Dangerous,
                contentDescription = null,
                modifier = iconModifier,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Upstream's line under the name: the language when the row is not already under a language
 * heading, the version, then the one warning worth the error colour (untrusted, obsolete, or 18+),
 * and the step while an install is in flight, in upstream's order. Saikou adds the lib version after
 * the version, and in the error colour why an entry or its update cannot be installed, or why a
 * package did not load. It wraps rather than cuts, as upstream's does.
 */
@Composable
private fun ExtensionItemDetails(row: ExtensionUi, installStep: InstallStep?) {
    val ext = row.extension()
    val error = MaterialTheme.colorScheme.error
    val parts = buildList<Pair<String, Color?>> {
        if (ext != null && row !is ExtensionUi.Available && ext.lang.isNotBlank()) add(languageLabel(ext.lang) to null)
        if (ext != null && ext.versionName.isNotEmpty()) add(ext.versionName to null)
        if (ext != null) add(stringResource(R.string.ext_lib_pill_format, ext.libVersion.toString()) to null)
        val warning = when {
            row is ExtensionUi.Untrusted -> R.string.ext_untrusted
            (row as? ExtensionUi.Installed)?.ext?.isObsolete == true -> R.string.ext_obsolete
            (row as? ExtensionUi.Update)?.ext?.isObsolete == true -> R.string.ext_obsolete
            ext?.isNsfw == true -> R.string.ext_detail_nsfw
            else -> null
        }
        if (warning != null) add(stringResource(warning).uppercase() to error)
        (row as? ExtensionUi.LoadError)?.let { add(stringResource(it.error.reason.messageRes) to error) }
        val libLabel = when {
            row is ExtensionUi.Available && !row.libSupported -> R.string.ext_lib_needs_newer_app
            row is ExtensionUi.Installed && row.updateNeedsNewerApp -> R.string.ext_update_needs_newer_app
            else -> null
        }
        if (libLabel != null) add(stringResource(libLabel) to error)
        installStep?.labelRes()?.let { add(stringResource(it) to null) }
    }
    FlowRow(
        modifier = Modifier.secondaryItemAlpha(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            parts.forEachIndexed { index, (text, color) ->
                if (index > 0) Text(DOT_SEPARATOR)
                Text(
                    text = text,
                    color = color ?: Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Upstream's `ExtensionItemActions`: cancel while an install is in flight, Retry after one failed,
 * and otherwise settings and Update on an installed extension, Trust on an untrusted one, and
 * WebView and Install on an available one. A row that did not load has the details of why, and,
 * like an untrusted one, Update while an index carries one.
 */
@Composable
private fun ExtensionItemActions(
    row: ExtensionUi,
    installStep: InstallStep?,
    onAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        when {
            installStep.isInFlight() -> IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ext_action_cancel_install))
            }
            installStep == InstallStep.Error -> IconButton(onClick = onAction) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_retry))
            }
            else -> {
                val secondary = when (row) {
                    is ExtensionUi.Installed, is ExtensionUi.Update -> Icons.Outlined.Settings to R.string.action_settings
                    is ExtensionUi.Untrusted -> Icons.Outlined.VerifiedUser to R.string.action_trust
                    is ExtensionUi.LoadError -> Icons.Outlined.Info to R.string.action_details
                    is ExtensionUi.Available ->
                        (Icons.Outlined.Public to R.string.action_open_in_web_view).takeIf { row.ext.webSource() != null }
                }
                secondary?.let { (icon, label) ->
                    IconButton(onClick = onSecondaryAction) { Icon(icon, contentDescription = stringResource(label)) }
                }
                val action = when {
                    row is ExtensionUi.Available -> R.string.action_install
                    row is ExtensionUi.Update || row.notLoadedUpdate() -> R.string.action_update
                    else -> null
                }
                if (action != null) {
                    // An entry built for a lib this host does not load would only be refused.
                    val enabled = (row as? ExtensionUi.Available)?.libSupported != false
                    IconButton(onClick = onAction, enabled = enabled) {
                        Icon(Icons.Outlined.GetApp, contentDescription = stringResource(action))
                    }
                }
            }
        }
    }
}

/** The source upstream's WebView action opens: the extension's first, when it has an address. */
private fun Extension.Available.webSource() = sources.firstOrNull()?.takeIf { it.homeUrl.isNotBlank() }

/** Upstream's row icon and its ring, 40 dp. */
private val ICON_SIZE = 40.dp

/** Upstream's `DotSeparatorNoSpaceText`; the row's spacing puts the room either side of it. */
private const val DOT_SEPARATOR = "•"

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

/** Pending, downloading or installing; a failed step is finished, so its row retries. */
internal fun InstallStep?.isInFlight(): Boolean = this != null && !isCompleted()

/** An in-flight step in words; null once it has finished, whichever way it finished. */
internal fun InstallStep.labelRes(): Int? = when (this) {
    InstallStep.Pending -> R.string.ext_install_pending
    InstallStep.Downloading -> R.string.ext_install_downloading
    InstallStep.Installing -> R.string.ext_install_installing
    InstallStep.Idle, InstallStep.Installed, InstallStep.Error -> null
}
