package com.shinsaikou.ui.browse.settings

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.shinsaikou.extensions.di.SourcePreferencesFiles
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.source.ConfigurableSource
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SectionLabel
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * One source's settings: whatever preferences the extension declares for itself, then the cookie
 * section that lets the owner get past a login or an interstitial.
 *
 * **On not using a fragment.** Contract 08 decision D23 prescribed hosting a
 * `PreferenceFragmentCompat` in a `FragmentContainerView` inside `AndroidView`, on the assumption
 * that the only way to obtain a `PreferenceScreen` is from a fragment's `PreferenceManager`. This
 * screen builds the tree itself and draws it in Compose instead. The constructor it uses is marked
 * `@RestrictTo(LIBRARY_GROUP_PREFIX)`, which is why [sourcePreferenceScreen] carries a suppression:
 * androidx expects a fragment to own the manager, and the restriction is the only thing standing in
 * the way. It is worth stepping around, because building the tree here buys two things the fragment
 * route cannot give. The preference list is a `RecyclerView`, and a `RecyclerView` inside a scrolling
 * `Column` is measured with an unbounded height, so the fragment route forces either a hard coded
 * container height or two nested scrollers; drawing the rows directly puts both sections in one
 * ordinary scroll. And the app keeps no `FragmentManager` at all, which is one fewer lifecycle to
 * be wrong about in a Compose-only activity.
 *
 * What does not change is where values land. The manager is pointed at the `source_<id>` file
 * [SourcePreferencesFiles] names before the screen is created, because that is the file the
 * extension's own code opens at runtime, and because every `Preference` reads its initial value
 * through the manager the moment it is added to the tree.
 *
 * A source with neither preferences of its own nor a home address shows `source_settings_none` and
 * nothing else. That one line covers both gaps: it is the answer to "what can I change here", and
 * a source with no web presence has no cookies to change either.
 */
@Composable
fun SourceSettingsScreen(sourceId: Long) {
    val viewModel: SourceSettingsViewModel =
        assistedMetroViewModel<SourceSettingsViewModel, SourceSettingsViewModel.Factory> {
            create(sourceId)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SourceSettingsViewModel.Event.Message -> resources.getString(event.messageRes)
                is SourceSettingsViewModel.Event.CountMessage ->
                    resources.getString(event.messageRes, event.count)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Built once per source. setupPreferenceScreen runs the extension's own code on the main
    // thread, which is what its contract asks for, and composition is the only main-thread moment
    // this screen has.
    // Keyed on configurable too: the source is looked up once the registry has loaded, which on a
    // screen restored after process death is after the first composition.
    val preferenceScreen = remember(viewModel, state.configurable) {
        viewModel.configurableSource?.let { source -> sourcePreferenceScreen(context, sourceId, source) }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = state.sourceName ?: stringResource(R.string.source_title_fallback),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            LoadingScreen(Modifier.padding(padding))
        } else if (!state.installed) {
            EmptyScreen(
                stringRes = R.string.error_source_not_installed,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel(stringResource(R.string.source_settings_title))
                SourcePreferenceSection(preferenceScreen)
                if (state.homeUrl != null) {
                    CookieSection(
                        state = state,
                        onHost = viewModel::setCookieHost,
                        onHeader = viewModel::setCookieHeader,
                        onImport = viewModel::importCookies,
                        onClear = viewModel::clearCookies,
                        onOpenPage = { url ->
                            navigator.open(Route.WebView(url = url, sourceId = sourceId, title = state.sourceName))
                        },
                    )
                }
            }
        }
    }
}

/**
 * The source's own rows. [screen] is null when the source has no preferences at all, and an empty
 * tree is treated the same way: a `ConfigurableSource` that adds nothing has nothing to show, and
 * a bare heading over empty space reads as a bug.
 *
 * [revision] exists because `Preference` is an ordinary mutable Java object with no snapshot state
 * behind it. Every mutation bumps it, which is what re-reads the values below.
 */
@Composable
private fun SourcePreferenceSection(screen: PreferenceScreen?) {
    var revision by remember(screen) { mutableIntStateOf(0) }
    val rows = remember(screen, revision) { screen?.let { flatten(it) }.orEmpty() }
    if (rows.isEmpty()) {
        InlineNotice(stringResource(R.string.source_settings_none))
        return
    }
    rows.forEachIndexed { index, row ->
        // Keyed on the preference's own key rather than on the Preference object, because the saved
        // state inside a row is filed under this key. The tree above is `remember(viewModel)`, so an
        // activity re-creation rebuilds it and hands back objects that are equal to nothing: an
        // identity key would file the restored dialog under a row that no longer exists, and the
        // dialog would be gone even though it had been saved. The key string is what the extension
        // stores its own value under, so it survives the rebuild by construction.
        //
        // Position is the fallback for the rows androidx leaves keyless, which are the headings and
        // labels: they open nothing and hold nothing worth restoring.
        key(row.preference.key ?: index) {
            PreferenceRow(row = row, onChanged = { revision++ })
        }
    }
}

@Composable
private fun PreferenceRow(row: PreferenceRow, onChanged: () -> Unit) {
    // Saved, not merely remembered. A re-creation would otherwise take the dialog off the screen
    // outright, along with the key or the address the owner was half way through typing into it.
    // Every sibling screen that opens something over a list does the same.
    var dialog by rememberSaveable { mutableStateOf(false) }
    val preference = row.preference
    val open = { dialog = true }.takeIf { row.enabled }

    when (row.kind) {
        PreferenceKind.CATEGORY -> SectionLabel(row.title)
        PreferenceKind.SWITCH -> PreferenceSwitchRow(
            title = row.title,
            summary = row.summary,
            checked = row.checked,
            enabled = row.enabled,
            onCheckedChange = { wanted ->
                // The framework asks the listener first and only writes when it agrees, and
                // extensions use that listener to do their own bookkeeping. Setting the value
                // first would run those two in the wrong order.
                val target = preference as TwoStatePreference
                if (target.callChangeListener(wanted)) {
                    target.isChecked = wanted
                    onChanged()
                }
            },
        )
        PreferenceKind.LIST -> {
            ListRow(title = row.title, detail = row.summary, onClick = open)
            if (dialog) {
                val target = preference as ListPreference
                ListPreferenceDialog(
                    preference = target,
                    onDismissRequest = { dialog = false },
                    onConfirm = { value ->
                        dialog = false
                        if (target.callChangeListener(value)) {
                            target.value = value
                            onChanged()
                        }
                    },
                )
            }
        }
        PreferenceKind.MULTI_SELECT -> {
            ListRow(title = row.title, detail = row.summary, onClick = open)
            if (dialog) {
                val target = preference as MultiSelectListPreference
                MultiSelectPreferenceDialog(
                    preference = target,
                    onDismissRequest = { dialog = false },
                    onConfirm = { values ->
                        dialog = false
                        if (target.callChangeListener(values)) {
                            target.values = values
                            onChanged()
                        }
                    },
                )
            }
        }
        PreferenceKind.TEXT -> {
            ListRow(title = row.title, detail = row.summary, onClick = open)
            if (dialog) {
                val target = preference as EditTextPreference
                TextPreferenceDialog(
                    preference = target,
                    onDismissRequest = { dialog = false },
                    onConfirm = { text ->
                        dialog = false
                        if (target.callChangeListener(text)) {
                            target.text = text
                            onChanged()
                        }
                    },
                )
            }
        }
        // Anything else is a label or an action. Only the click listener is honoured: a
        // preference carrying an Intent would leave the app, and where a tap on a source's
        // setting may take the owner is the host's decision, not the extension's.
        PreferenceKind.PLAIN -> ListRow(
            title = row.title,
            detail = row.summary,
            onClick = {
                preference.onPreferenceClickListener?.onPreferenceClick(preference)
                onChanged()
            }.takeIf { row.enabled },
        )
    }
}

/** A switch row on [ListRow]'s frame, `ListRowLayout`: [ListRow] cannot give it, its trailing slot is text. */
@Composable
private fun PreferenceSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListRowLayout(
        onClick = { onCheckedChange(!checked) }.takeIf { enabled },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    ) {
        ListRowTitle(title)
        if (summary.isNotBlank()) ListRowDetail(summary, maxLines = 3)
    }
}

@Composable
private fun ListPreferenceDialog(
    preference: ListPreference,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val entries = preference.entries.orEmpty()
    val values = preference.entryValues.orEmpty()
    // Saved with the flag that opens the dialog, so a re-creation brings back the row the owner had
    // moved to rather than the one already stored.
    var selected by rememberSaveable(preference) {
        mutableIntStateOf(preference.findIndexOfValue(preference.value))
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(preference.title?.toString().orEmpty()) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = index }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = index == selected, onClick = { selected = index })
                        Text(entry.toString())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(values[selected].toString()) },
                enabled = selected in values.indices,
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MultiSelectPreferenceDialog(
    preference: MultiSelectListPreference,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val entries = preference.entries.orEmpty()
    val values = preference.entryValues.orEmpty()
    // Saved with the flag that opens the dialog. A Set is not a shape the saved instance state
    // holds, so the working selection crosses a re-creation as a list.
    var selected by rememberSaveable(
        preference,
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(preference.values.toSet()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(preference.title?.toString().orEmpty()) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                entries.forEachIndexed { index, entry ->
                    val value = values.getOrNull(index)?.toString() ?: return@forEachIndexed
                    val checked = value in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (checked) selected - value else selected + value }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { selected = if (checked) selected - value else selected + value },
                        )
                        Text(entry.toString())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TextPreferenceDialog(
    preference: EditTextPreference,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Saved, not merely remembered: this is the one dialog on the screen that holds typing, and an
    // API key or a base URL pasted in here is the last thing that should go with a rotation.
    var text by rememberSaveable(preference) { mutableStateOf(preference.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(preference.title?.toString().orEmpty()) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // A source setting is a key, a host or a number. Autocorrect only damages those.
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The cookie section. Every client in the process shares one cookie jar, so a cookie set here is
 * attached to the extension's next request with nothing copied; that is why this section is plain
 * text fields and a button rather than anything resembling a transfer.
 */
@Composable
private fun CookieSection(
    state: SourceSettingsViewModel.State,
    onHost: (String) -> Unit,
    onHeader: (String) -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    val address = state.cookieHost.trim()
    SectionLabel(stringResource(R.string.pref_cookies_title))
    InlineNotice(stringResource(R.string.pref_cookies_summary))
    OutlinedTextField(
        value = state.cookieHost,
        onValueChange = onHost,
        label = { Text(stringResource(R.string.source_cookies_host)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = state.cookieHeader,
        onValueChange = onHeader,
        label = { Text(stringResource(R.string.source_cookies_header)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        // A pasted Cookie header is long and has no line breaks, so it wraps rather than scrolls
        // sideways past the end of the field.
        maxLines = 4,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    Button(
        onClick = onImport,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = state.cookieHeader.isNotBlank() && !state.importing,
    ) {
        Text(stringResource(R.string.source_cookies_import))
    }
    ListRow(
        title = stringResource(R.string.pref_cookies_open_webview),
        detail = address,
        onClick = { onOpenPage(address) }.takeIf { address.isNotEmpty() },
    )
    ListRow(
        title = stringResource(R.string.source_cookies_current),
        detail = "",
        trailing = stringResource(R.string.action_clear).takeIf { state.cookieNames.isNotEmpty() },
        onTrailing = onClear,
    )
    if (state.cookieNames.isEmpty()) {
        InlineNotice(stringResource(R.string.source_cookies_none))
    } else {
        state.cookieNames.forEach { name -> ListRow(title = name, detail = "") }
    }
}

private enum class PreferenceKind { CATEGORY, SWITCH, LIST, MULTI_SELECT, TEXT, PLAIN }

/**
 * One drawable row, with the values already read off the preference. They are copied rather than
 * read inside the row composable so that a change actually reaches Compose: `Preference` is not
 * snapshot state, and a row handed only the object itself would have nothing to recompose on.
 */
private data class PreferenceRow(
    val preference: Preference,
    val kind: PreferenceKind,
    val title: String,
    val summary: String,
    val enabled: Boolean,
    val checked: Boolean,
)

/**
 * Flattens the tree into rows. Nested groups are inlined: a `PreferenceCategory` keeps its heading,
 * and a nested `PreferenceScreen` (which extensions use as a submenu) is drawn in place, because a
 * settings screen that pushes a second settings screen is one more back stack for one extension's
 * layout choice.
 */
private fun flatten(group: PreferenceGroup, into: MutableList<PreferenceRow> = mutableListOf()): List<PreferenceRow> {
    for (index in 0 until group.preferenceCount) {
        val child = group.getPreference(index)
        if (!child.isVisible) continue
        if (child is PreferenceGroup) {
            if (child is PreferenceCategory) into += rowOf(child, PreferenceKind.CATEGORY)
            flatten(child, into)
        } else {
            into += rowOf(child, kindOf(child))
        }
    }
    return into
}

private fun kindOf(preference: Preference): PreferenceKind = when (preference) {
    is TwoStatePreference -> PreferenceKind.SWITCH
    is ListPreference -> PreferenceKind.LIST
    is MultiSelectListPreference -> PreferenceKind.MULTI_SELECT
    is EditTextPreference -> PreferenceKind.TEXT
    else -> PreferenceKind.PLAIN
}

private fun rowOf(preference: Preference, kind: PreferenceKind): PreferenceRow {
    val checked = preference is TwoStatePreference && preference.isChecked
    // A two state preference carries a summary per position; the framework swaps them on bind and
    // only falls back to the plain summary when the position specific one is missing.
    val summary = when {
        preference !is TwoStatePreference -> preference.summary
        checked -> preference.summaryOn ?: preference.summary
        else -> preference.summaryOff ?: preference.summary
    }
    return PreferenceRow(
        preference = preference,
        kind = kind,
        title = preference.title?.toString().orEmpty(),
        summary = summary?.toString().orEmpty(),
        enabled = preference.isEnabled,
        checked = checked,
    )
}

/**
 * Builds the source's preference tree against the `source_<id>` file. The name is set before the
 * screen exists on purpose: `PreferenceGroup.addPreference` reads each preference's stored value
 * through the manager as it is added, so a manager still pointed at the default file would show
 * defaults and then write them somewhere the extension never looks.
 *
 * Returns null when the source's own code fails. An extension runs arbitrary code in
 * `setupPreferenceScreen`, and this call happens during composition, where a throw takes the
 * activity down rather than the section.
 */
@SuppressLint("RestrictedApi")
private fun sourcePreferenceScreen(
    context: Context,
    sourceId: Long,
    source: ConfigurableSource,
): PreferenceScreen? = runCatching {
    // PreferenceManager's constructor is @RestrictTo(LIBRARY_GROUP_PREFIX) because androidx expects
    // PreferenceFragmentCompat to own it. There is no fragment here and there cannot be one: the
    // extension API hands us a ConfigurableSource that wants a real androidx PreferenceScreen, and
    // createPreferenceScreen is the only way to build one. The alternative is hosting a fragment
    // inside Compose purely to reach a constructor, which buys nothing and costs a view hierarchy.
    val themed = ContextThemeWrapper(context, preferenceTheme(context))
    val manager = PreferenceManager(themed)
    manager.sharedPreferencesName = SourcePreferencesFiles.fileName(sourceId)
    manager.sharedPreferencesMode = Context.MODE_PRIVATE
    val screen = manager.createPreferenceScreen(themed)
    source.setupPreferenceScreen(screen)
    screen
}.onFailure {
    // Logged on the source rather than on the throwable, so the tag names the extension whose
    // setupPreferenceScreen threw. A top level function has no receiver of its own to log against.
    source.logcat(LogPriority.ERROR, it) { "The source's preference screen could not be built" }
}.getOrNull()

/**
 * The same lookup `PreferenceFragmentCompat` does. Without it the preference widgets resolve their
 * style attributes against a theme that declares none, and every constructed preference falls back
 * to platform defaults.
 */
private fun preferenceTheme(context: Context): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(androidx.preference.R.attr.preferenceTheme, value, true)
    return value.resourceId.takeIf { it != 0 } ?: androidx.preference.R.style.PreferenceThemeOverlay
}
