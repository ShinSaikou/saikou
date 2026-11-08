package com.shinsaikou.ui.settings

import android.content.Context
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.ui.components.IconListRow
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SearchTopBar
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState

/**
 * The tag on the two pane layout's root. It was a bare literal repeated in the device suite until
 * the library grew a pane of its own; all four two pane screens now name theirs the same way.
 */
const val SETTINGS_TWO_PANE_TAG = "settings_two_pane"

/** The tag on the settings search's results list, which a device test waits for. */
internal const val SETTINGS_SEARCH_RESULTS_TAG = "settings_search_results"

/**
 * The section list and the body of the selected section.
 *
 * A phone shows one or the other, and a tap navigates, so a section is its own back stack entry
 * and back returns to the list. A tablet shows both and swaps the end pane in place: [Route] has
 * no popUpTo option, so navigating per tap would stack one Settings entry for every section the
 * reader looked at.
 *
 * The list's bar has upstream's settings search, opened in the bar itself as the library's is. While
 * it holds text the results take the list's place; a result opens its section scrolled to the row
 * ([requestedHighlight] on a phone, which opens it as a section is opened, and the end pane on a
 * tablet), and back from there finds the results still up.
 */
@Composable
internal fun SettingsScreen(requestedSection: SettingsSection?, requestedHighlight: SettingsSearchKey? = null) {
    val viewModel: SettingsViewModel = metroViewModel()
    val navigator = LocalNavigator.current
    val tabletUi = isTabletUi()
    var selected by rememberSaveable(requestedSection) {
        mutableStateOf(requestedSection ?: SettingsSection.APPEARANCE)
    }
    // Null while the search is closed, as the library's bar has it.
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    // Downloads draws its sources-at-once row only while this switch is on, so the search does too.
    val parallelSourceDownloads by viewModel.downloadPreferences.parallelSourceDownloads.collectAsState()
    // The row a result asked for, until it has been shown: saved as its ids, so "none" is saved too,
    // and a section shown again keeps the scroll it was left at.
    var highlightIds by rememberSaveable { mutableStateOf(requestedHighlight?.ids ?: intArrayOf()) }
    val highlight = SettingsSearchKey.of(highlightIds)?.let { key ->
        remember(key) { SettingsHighlight(key) { highlightIds = intArrayOf() } }
    }
    // A row's feedback, where upstream toasts it (plan 3.1 item 12). A new line replaces the one
    // showing, so a second tap is answered at once rather than after the first line's time runs out.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // A line with an action (Restart) stays for the long duration, time enough to reach the button,
    // and still goes by itself rather than covering the list's last rows until it is tapped.
    val showMessage: (String, SnackbarAction?) -> Unit = { message, action ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = action?.label,
                duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) action?.onClick?.invoke()
        }
    }
    // The tablet keeps the list on screen, so the bar names the screen rather than the pane.
    val showingList = tabletUi || requestedSection == null
    BackHandler(enabled = showingList && searchQuery != null) { searchQuery = null }

    // The list, or the results while the search holds text.
    val list: @Composable (Modifier) -> Unit = { modifier ->
        val query = searchQuery
        if (query.isNullOrBlank()) {
            SectionList(
                sections = viewModel.sections,
                selected = selected.takeIf { tabletUi },
                onSelect = { if (tabletUi) selected = it else navigator.open(Route.Settings(it)) },
                modifier = modifier,
            )
        } else {
            SettingsSearchResults(
                query = query,
                sections = viewModel.sections,
                parallelSourceDownloads = parallelSourceDownloads,
                onPick = { entry ->
                    if (tabletUi) {
                        selected = entry.section
                        highlightIds = entry.key.ids
                    } else {
                        navigator.open(entry.key.route)
                    }
                },
                modifier = modifier,
            )
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            if (showingList) {
                SearchTopBar(
                    title = stringResource(R.string.settings_title),
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.settings_search_hint),
                    scrollBehavior = scrollBehavior,
                    onBack = navigator::back,
                )
            } else {
                ScreenTopBar(
                    title = stringResource(selected.labelRes),
                    onBack = navigator::back,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            tabletUi -> TwoPanelBox(
                // The tag is the only way MainActivitySmokeTest can tell the tablet composition
                // apart from the phone one: both render the same section body, so asserting on
                // any visible text would pass on either.
                modifier = Modifier.testTag(SETTINGS_TWO_PANE_TAG),
                startContent = { list(Modifier.padding(padding)) },
                endContent = {
                    SettingsSectionBody(
                        section = selected,
                        viewModel = viewModel,
                        showMessage = showMessage,
                        highlight = highlight?.takeIf { it.key.section == selected },
                        modifier = Modifier.padding(padding),
                    )
                },
            )
            requestedSection == null -> list(Modifier.fillMaxSize().padding(padding))
            // Nothing writes [selected] on a phone, so here it is still the requested section.
            else -> SettingsSectionBody(
                section = selected,
                viewModel = viewModel,
                showMessage = showMessage,
                highlight = highlight?.takeIf { it.key.section == selected },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/** The button a Settings snackbar line carries, such as Restart after a setting read once per process. */
internal class SnackbarAction(val label: String, val onClick: () -> Unit)

/**
 * [sections] is [SettingsViewModel.sections]. [selected] is null on a phone, where the list is a
 * whole screen and nothing on it is current.
 */
@Composable
private fun SectionList(
    sections: List<SettingsSection>,
    selected: SettingsSection?,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        sections.forEach { section ->
            SectionRow(section = section, selected = section == selected, onClick = { onSelect(section) })
        }
    }
}

/**
 * A section's row: its icon, its name and a line naming some of what it holds, in Material's two
 * line list item, as upstream's settings screen draws its sections. A bare name in a plain list
 * row made each one a thin strip of text.
 */
@Composable
private fun SectionRow(section: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    IconListRow(
        title = stringResource(section.labelRes),
        detail = stringResource(section.summaryRes),
        icon = section.icon,
        onClick = onClick,
        selected = selected,
    )
}

/**
 * What [query] finds in [sections], the rows of a section not listed left out, as upstream's
 * results: each row's title and where it is, here under its section's icon so the results read as
 * the list does. A scroll puts the keyboard away, as upstream's does, and so does a pick, which on a
 * tablet stays on this screen; nothing found says so.
 */
@Composable
private fun SettingsSearchResults(
    query: String,
    sections: List<SettingsSection>,
    parallelSourceDownloads: Boolean,
    onPick: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val view = LocalView.current
    val items = remember(resources, sections, parallelSourceDownloads) {
        settingsSearchIndex
            .filter { entry ->
                entry.section in sections && entry.condition?.isMet(context, view, parallelSourceDownloads) != false
            }
            .map { it.resolve(resources) }
    }
    val results = remember(items, query) { matchSettings(items, query) }
    if (results.isEmpty()) {
        EmptyScreen(stringRes = R.string.no_results_found, modifier = modifier)
        return
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }
    LazyColumn(modifier = modifier.testTag(SETTINGS_SEARCH_RESULTS_TAG), state = listState) {
        items(results, key = { it.entry.key.ids.joinToString() }) { item ->
            IconListRow(
                title = item.title,
                detail = item.breadcrumb,
                icon = item.entry.section.icon,
                onClick = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onPick(item.entry)
                },
            )
        }
    }
}

/** Whether the row is drawn: the same checks the section makes before it draws it. */
private fun SettingsSearchCondition.isMet(context: Context, view: View, parallelSourceDownloads: Boolean): Boolean =
    when (this) {
        SettingsSearchCondition.DISPLAY_CUTOUT -> view.hasDisplayCutout()
        SettingsSearchCondition.AUTHENTICATION -> context.isAuthenticationSupported()
        SettingsSearchCondition.PARALLEL_SOURCE_DOWNLOADS -> parallelSourceDownloads
    }
