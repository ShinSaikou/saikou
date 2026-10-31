// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsScreen.kt (modified: yes) a composable destination on Route.Statistics instead of a Voyager screen
package com.shinsaikou.ui.stats

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.ScreenTopBar
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun StatsScreen() {
    val navigator = LocalNavigator.current

    val viewModel: StatsViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.label_stats),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        when (val current = state) {
            StatsScreenState.Loading -> LoadingScreen(Modifier.padding(paddingValues))
            is StatsScreenState.Success -> StatsScreenContent(
                state = current,
                paddingValues = paddingValues,
            )
        }
    }
}
