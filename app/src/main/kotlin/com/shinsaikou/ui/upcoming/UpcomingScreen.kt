package com.shinsaikou.ui.upcoming

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.ui.components.LIST_ROW_LEADING_WIDTH
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.TopBarAction
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.relativeDateText
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha
import kotlin.time.Clock

/**
 * The releases predicted for the library, from the Updates bar: upstream's calendar of the month
 * over the list of days, or beside it on a tablet. A tap on a day with releases scrolls the list to
 * that day, and a tap on a row opens the entry.
 */
@Composable
fun UpcomingScreen() {
    val navigator = LocalNavigator.current
    val viewModel: UpcomingViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabletUi = isTabletUi()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
    val calendar: @Composable (Modifier) -> Unit = { modifier ->
        UpcomingCalendar(
            month = state.month,
            days = state.days,
            today = today,
            onPreviousMonth = viewModel::showPreviousMonth,
            onNextMonth = viewModel::showNextMonth,
            onDay = { date ->
                state.days[date]?.let { day ->
                    // On a phone the calendar is the list's first item, so each header is one further down.
                    val index = if (tabletUi) day.headerIndex else day.headerIndex + 1
                    scope.launch { listState.animateScrollToItem(index) }
                }
            },
            modifier = modifier,
        )
    }
    val onOpen: (Manga) -> Unit = { manga -> navigator.open(Route.MangaDetail(manga.id)) }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.upcoming_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    // Upstream's two actions: the category filter, lit while it holds one, and the
                    // guide, here Saikou's own page on Upcoming rather than upstream's.
                    TopBarAction(
                        label = stringResource(R.string.action_filter),
                        icon = Icons.Outlined.FilterList,
                        onClick = viewModel::showFilterDialog,
                        highlighted = state.filter.active,
                    )
                    TopBarAction(
                        label = stringResource(R.string.upcoming_guide),
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = { navigator.openUrl(UPCOMING_GUIDE_URL) },
                    )
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            tabletUi -> TwoPanelBox(
                startContent = { calendar(Modifier.verticalScroll(rememberScrollState())) },
                endContent = { UpcomingList(state = state, listState = listState, onOpen = onOpen) },
                modifier = Modifier.padding(padding),
            )
            else -> UpcomingList(
                state = state,
                listState = listState,
                onOpen = onOpen,
                modifier = Modifier.padding(padding),
                calendar = { calendar(Modifier) },
            )
        }
    }

    when (state.dialog) {
        UpcomingViewModel.Dialog.FilterSheet -> UpcomingFilterDialog(
            filter = state.filter,
            categories = state.categories,
            onDismissRequest = viewModel::dismissDialog,
            onCycleCategory = viewModel::cycleCategory,
        )
        null -> Unit
    }
}

/**
 * The days and their entries, under [calendar] when there is one, with upstream's fast scroller.
 * With nothing predicted the calendar still shows, so the message goes under it as a row rather
 * than taking the screen.
 */
@Composable
private fun UpcomingList(
    state: UpcomingViewModel.State,
    listState: LazyListState,
    onOpen: (Manga) -> Unit,
    modifier: Modifier = Modifier,
    calendar: (@Composable () -> Unit)? = null,
) {
    FastScrollLazyColumn(modifier = modifier, state = listState) {
        if (calendar != null) {
            item(key = "upcoming-calendar", contentType = "calendar") { calendar() }
        }
        if (state.items.isEmpty()) {
            item(key = "upcoming-empty", contentType = "empty") {
                Text(
                    text = stringResource(R.string.upcoming_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.large, vertical = MaterialTheme.padding.extraLarge)
                        .secondaryItemAlpha(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
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
                is UpcomingUiModel.Item -> UpcomingRow(manga = model.manga, onClick = { onOpen(model.manga) })
            }
        }
    }
}

/** Upstream's day heading: the date, then a badge with how many entries fall on it (mihon#1250). */
@Composable
private fun UpcomingHeader(model: UpcomingUiModel.Header, relativeTime: Boolean, dateFormat: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = relativeDateText(model.date, relativeTime, dateFormat),
            modifier = Modifier
                .padding(MaterialTheme.padding.small)
                .padding(start = MaterialTheme.padding.small),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Badge(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text(model.count.toString())
        }
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

/** Saikou's page on Upcoming, where upstream's guide button opens upstream's. */
const val UPCOMING_GUIDE_URL = "https://shinsaikou.com/docs/updates#upcoming"
