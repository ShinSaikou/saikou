package com.shinsaikou.ui.browse.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import com.shinsaikou.ui.settings.enabledLanguageLabel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Upstream's Browse source filter screen (`presentation/browse/SourcesFilterScreen.kt` and
 * `ui/browse/source/SourcesFilterScreen.kt`; phase 5 unit BF), reached from the Sources page's
 * Filter action: a row per language with a switch, and under an enabled language a row per source
 * with a checkbox. A tap anywhere on a row toggles it. With no catalogue source installed it is
 * upstream's empty screen; a load failure says upstream's `internal_error` and goes back.
 *
 * [ScreenTopBar], rows on [ListRowLayout], the language switch and a source's checkbox in Material's
 * own colours. The failure is said in this screen's own snackbar, shown for at most
 * [ERROR_MESSAGE_MILLIS] before the screen leaves, since no snackbar host outlives it.
 *
 * Every row is keyed (the language code, `source-filter-<id>`), so a toggle moves rows in and out
 * around the one tapped without the list jumping.
 */
@Composable
fun SourcesFilterScreen() {
    val navigator = LocalNavigator.current
    val viewModel: SourcesFilterViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SourcesFilterContent(
        state = state,
        onBack = navigator::back,
        onClickLanguage = viewModel::toggleLanguage,
        onClickSource = viewModel::toggleSource,
    )
}

/**
 * The screen drawn from [state] alone, so a device test can compose it on a made-up state with no
 * extension installed (`SourcesFilterContentTest`). [onBack] is the top bar's back and the error
 * path's way out.
 */
@Composable
internal fun SourcesFilterContent(
    state: SourcesFilterViewModel.State,
    onBack: () -> Unit,
    onClickLanguage: (String) -> Unit,
    onClickSource: (Source) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.label_sources),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            SourcesFilterViewModel.State.Loading -> LoadingScreen(Modifier.padding(padding))
            is SourcesFilterViewModel.State.Error -> LaunchedEffect(Unit) {
                // Shown for at most ERROR_MESSAGE_MILLIS, not the whole short duration: upstream's
                // toast and pop leave at once, and the screen has nothing else to offer.
                withTimeoutOrNull(ERROR_MESSAGE_MILLIS) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.internal_error))
                }
                onBack()
            }
            is SourcesFilterViewModel.State.Success -> if (state.isEmpty) {
                EmptyScreen(
                    stringRes = R.string.source_filter_empty_screen,
                    modifier = Modifier.padding(padding),
                )
            } else {
                SourcesFilterList(
                    padding = padding,
                    state = state,
                    onClickLanguage = onClickLanguage,
                    onClickSource = onClickSource,
                )
            }
        }
    }
}

@Composable
private fun SourcesFilterList(
    padding: PaddingValues,
    state: SourcesFilterViewModel.State.Success,
    onClickLanguage: (String) -> Unit,
    onClickSource: (Source) -> Unit,
) {
    FastScrollLazyColumn(contentPadding = padding) {
        state.items.forEach { (language, sources) ->
            val enabled = language in state.enabledLanguages
            item(key = language, contentType = "source-filter-header") {
                // The row and its rule move together.
                Column(Modifier.animateItem()) {
                    LanguageSwitchRow(language = language, enabled = enabled, onClick = { onClickLanguage(language) })
                }
            }
            if (enabled) {
                items(
                    items = sources,
                    key = { "source-filter-${it.id}" },
                    contentType = { "source-filter-item" },
                ) { source ->
                    Column(Modifier.animateItem()) {
                        SourcesFilterItem(
                            source = source,
                            enabled = source.id.toString() !in state.disabledSources,
                            onClick = { onClickSource(source) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Upstream's `SwitchPreferenceWidget` row for a language: its name through [enabledLanguageLabel] and a
 * switch. The row is the toggle, so a screen reader hears one switch with the
 * language's name and its state; the [Switch] itself only draws it. The source filter's headers and
 * the extension filter's rows (phase 5 unit BX) are this one row.
 */
@Composable
internal fun LanguageSwitchRow(language: String, enabled: Boolean, onClick: () -> Unit) {
    ListRowLayout(
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = { onClick() }),
        trailing = { Switch(checked = enabled, onCheckedChange = null) },
    ) {
        ListRowTitle(enabledLanguageLabel(language))
    }
}

/**
 * Upstream's `BaseSourceItem` with its language left out: the source's name and a checkbox, ticked
 * while the source is enabled, in Material's own colours.
 * The row is the toggle, a checkbox to a screen reader, as the header is a switch.
 */
@Composable
private fun SourcesFilterItem(source: Source, enabled: Boolean, onClick: () -> Unit) {
    ListRow(
        title = source.name,
        detail = "",
        modifier = Modifier.toggleable(value = enabled, role = Role.Checkbox, onValueChange = { onClick() }),
        trailingIcon = { Checkbox(checked = enabled, onCheckedChange = null) },
    )
}

/** How long a load failure's snackbar shows before the screen goes back. */
internal const val ERROR_MESSAGE_MILLIS = 1_500L
