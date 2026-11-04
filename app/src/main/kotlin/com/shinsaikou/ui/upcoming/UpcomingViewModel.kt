package com.shinsaikou.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.manga.interactor.GetLibraryManga
import kotlin.time.Clock

/**
 * The releases predicted for the library. It lists the same entries with the release rule off, as
 * it is a prediction and not the update's queue, and adds no work to an update.
 *
 * The library is read only while the screen is watched: the reader's progress writes re-run the
 * library query, and this screen left on the back stack under the reader would pay for each one.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(UpcomingViewModel::class)
@Inject
class UpcomingViewModel(
    private val getLibraryManga: GetLibraryManga,
    private val libraryPreferences: LibraryPreferences,
    private val uiPreferences: UiPreferences,
    private val dispatchers: Dispatchers,
) : StateViewModel<UpcomingViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val items: List<UpcomingUiModel> = emptyList(),
        val relativeTime: Boolean = true,
        val dateFormat: String = "",
    )

    init {
        viewModelScope.launch {
            upcoming()
                .distinctUntilChanged()
                .flowOn(dispatchers.io)
                .collect { items -> mutableState.update { it.copy(loading = false, items = items) } }
        }
        viewModelScope.launch {
            combine(uiPreferences.relativeTime.withCurrent(), uiPreferences.dateFormat.withCurrent()) { relative, format ->
                relative to format
            }.collect { (relative, format) ->
                mutableState.update { it.copy(relativeTime = relative, dateFormat = format) }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun upcoming() = mutableState.subscriptionCount
        .map { it > 0 }
        .distinctUntilChanged()
        .flatMapLatest { watched ->
            if (!watched) {
                emptyFlow()
            } else {
                combine(
                    getLibraryManga.subscribe(),
                    libraryPreferences.updateCategories.withCurrent(),
                    libraryPreferences.updateCategoriesExclude.withCurrent(),
                ) { library, included, excluded ->
                    val zone = TimeZone.currentSystemDefault()
                    val today = Clock.System.now().toLocalDateTime(zone).date
                    upcomingUiModels(
                        upcomingEntries(
                            library = library,
                            included = included.mapNotNullTo(mutableSetOf(), String::toLongOrNull),
                            excluded = excluded.mapNotNullTo(mutableSetOf(), String::toLongOrNull),
                            startOfToday = today.atStartOfDayIn(zone).toEpochMilliseconds(),
                        ),
                        zone,
                    )
                }
            }
        }
}
