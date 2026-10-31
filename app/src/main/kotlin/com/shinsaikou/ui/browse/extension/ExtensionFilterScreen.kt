package com.shinsaikou.ui.browse.extension

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.browse.source.ERROR_MESSAGE_MILLIS
import com.shinsaikou.ui.browse.source.LanguageSwitchRow
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Upstream's Extensions filter screen (`presentation/browse/ExtensionFilterScreen.kt` and
 * `ui/browse/extension/ExtensionFilterScreen.kt`; phase 5 unit BX), reached from the Extensions
 * page's Filter action: a row per language of the available extensions with a switch, a tap anywhere
 * on the row toggling it. With nothing available (no extension store, or none fetched) it is upstream's
 * empty screen; a load failure says upstream's `internal_error` and goes back.
 *
 * As the source filter is: [ScreenTopBar] titled as upstream's ("Extensions"), each row the source
 * filter's [LanguageSwitchRow] with Material's switch. The failure is said in this screen's own
 * snackbar for at most [ERROR_MESSAGE_MILLIS] before the screen leaves.
 *
 * Every row is keyed by its code, so a language switched off moves down without the list jumping.
 */
@Composable
fun ExtensionFilterScreen() {
    val navigator = LocalNavigator.current
    val viewModel: ExtensionFilterViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ExtensionFilterContent(
        state = state,
        onBack = navigator::back,
        onClickLanguage = viewModel::toggle,
    )
}

/**
 * The screen drawn from [state] alone, so a device test can compose it on a made-up state with no
 * extension store (`ExtensionFilterContentTest`). [onBack] is the top bar's back and the error path's
 * way out.
 */
@Composable
internal fun ExtensionFilterContent(
    state: ExtensionFilterViewModel.State,
    onBack: () -> Unit,
    onClickLanguage: (String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.label_extensions),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state) {
            ExtensionFilterViewModel.State.Loading -> LoadingScreen(Modifier.padding(padding))
            is ExtensionFilterViewModel.State.Error -> LaunchedEffect(Unit) {
                // As the source filter: at most ERROR_MESSAGE_MILLIS, then back.
                withTimeoutOrNull(ERROR_MESSAGE_MILLIS) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.internal_error))
                }
                onBack()
            }
            is ExtensionFilterViewModel.State.Success -> if (state.isEmpty) {
                EmptyScreen(
                    stringRes = R.string.empty_screen,
                    modifier = Modifier.padding(padding),
                )
            } else {
                FastScrollLazyColumn(contentPadding = padding) {
                    items(
                        items = state.languages,
                        key = { it },
                        contentType = { "extension-filter-language" },
                    ) { language ->
                        // The row and its rule move together.
                        Column(Modifier.animateItem()) {
                            LanguageSwitchRow(
                                language = language,
                                enabled = language in state.enabledLanguages,
                                onClick = { onClickLanguage(language) },
                            )
                        }
                    }
                }
            }
        }
    }
}
