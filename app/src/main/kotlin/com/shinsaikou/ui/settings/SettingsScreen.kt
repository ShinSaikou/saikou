package com.shinsaikou.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

/**
 * The tag on the two pane layout's root. It was a bare literal repeated in the device suite until
 * the library grew a pane of its own; all four two pane screens now name theirs the same way.
 */
const val SETTINGS_TWO_PANE_TAG = "settings_two_pane"

/**
 * The section list and the body of the selected section.
 *
 * A phone shows one or the other, and a tap navigates, so a section is its own back stack entry
 * and back returns to the list. A tablet shows both and swaps the end pane in place: [Route] has
 * no popUpTo option, so navigating per tap would stack one Settings entry for every section the
 * reader looked at.
 */
@Composable
fun SettingsScreen(requestedSection: SettingsSection?) {
    val viewModel: SettingsViewModel = metroViewModel()
    val navigator = LocalNavigator.current
    val tabletUi = isTabletUi()
    var selected by rememberSaveable(requestedSection) {
        mutableStateOf(requestedSection ?: SettingsSection.APPEARANCE)
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

    Scaffold(
        topBar = { scrollBehavior ->
            // The tablet keeps the list on screen, so the bar names the screen rather than the pane.
            val showingList = tabletUi || requestedSection == null
            ScreenTopBar(
                title = stringResource(if (showingList) R.string.settings_title else selected.labelRes),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            tabletUi -> TwoPanelBox(
                // The tag is the only way MainActivitySmokeTest can tell the tablet composition
                // apart from the phone one: both render the same section body, so asserting on
                // any visible text would pass on either.
                modifier = Modifier.testTag(SETTINGS_TWO_PANE_TAG),
                startContent = {
                    SectionList(
                        sections = viewModel.sections,
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.padding(padding),
                    )
                },
                endContent = {
                    SettingsSectionBody(
                        section = selected,
                        viewModel = viewModel,
                        showMessage = showMessage,
                        modifier = Modifier.padding(padding),
                    )
                },
            )
            requestedSection == null -> SectionList(
                sections = viewModel.sections,
                selected = null,
                onSelect = { navigator.open(Route.Settings(it)) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            // Nothing writes [selected] on a phone, so here it is still the requested section.
            else -> SettingsSectionBody(
                section = selected,
                viewModel = viewModel,
                showMessage = showMessage,
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
            ListRow(
                title = stringResource(section.labelRes),
                detail = "",
                modifier = Modifier.selectedBackground(section == selected),
                onClick = { onSelect(section) },
            )
        }
    }
}
