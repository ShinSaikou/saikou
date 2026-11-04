package com.shinsaikou.ui.upcoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.LIST_ROW_LEADING_WIDTH
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ScreenTopBar
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.relativeDateText
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/** The releases predicted for the library, from the Updates bar: a tap on a row opens the entry. */
@Composable
fun UpcomingScreen() {
    val navigator = LocalNavigator.current
    val viewModel: UpcomingViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.upcoming_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            state.items.isEmpty() -> EmptyScreen(
                stringRes = R.string.upcoming_empty,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(
                    items = state.items,
                    key = { it.key },
                    contentType = {
                        when (it) {
                            is UpcomingUiModel.Header -> "header"
                            is UpcomingUiModel.Item -> "item"
                        }
                    },
                ) { model ->
                    when (model) {
                        is UpcomingUiModel.Header -> UpcomingHeader(
                            model = model,
                            relativeTime = state.relativeTime,
                            dateFormat = state.dateFormat,
                        )
                        is UpcomingUiModel.Item -> UpcomingRow(
                            manga = model.manga,
                            onClick = { navigator.open(Route.MangaDetail(model.manga.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingHeader(model: UpcomingUiModel.Header, relativeTime: Boolean, dateFormat: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListGroupHeader(text = relativeDateText(model.date, relativeTime, dateFormat))
        Text(
            text = pluralStringResource(R.plurals.upcoming_entry_count, model.count, model.count),
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UpcomingRow(manga: Manga, onClick: () -> Unit) {
    ListRowLayout(
        onClick = onClick,
        leading = {
            MangaCover(
                manga.asMangaCover(),
                modifier = Modifier.width(LIST_ROW_LEADING_WIDTH),
                contentDescription = manga.displayTitle,
            )
        },
    ) {
        ListRowTitle(manga.displayTitle)
    }
}
