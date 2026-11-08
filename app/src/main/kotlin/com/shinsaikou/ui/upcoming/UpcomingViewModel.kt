package com.shinsaikou.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.merged.interactor.GetMergedGroups
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.preferences.UpcomingPreferences
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetLibraryManga
import kotlin.time.Clock

/**
 * The releases predicted for the library. It lists the same entries with the release rule off, as
 * it is a prediction and not the update's queue, and adds no work to an update.
 *
 * The calendar above the list is read off the same list ([upcomingDays]), so it costs no second
 * query; the month it shows is held here so a rotation keeps it. The Filter button's category
 * lists are upstream's own keys ([UpcomingPreferences]), apart from the update's categories.
 *
 * The library is read only while the screen is watched: the reader's progress writes re-run the
 * library query, and this screen left on the back stack under the reader would pay for each one.
 * A merged title is one row, under its main entry, at the earliest date of its members
 * ([upcomingEntries]).
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(UpcomingViewModel::class)
@Inject
class UpcomingViewModel(
    private val getLibraryManga: GetLibraryManga,
    private val getMergedGroups: GetMergedGroups,
    private val libraryPreferences: LibraryPreferences,
    private val upcomingPreferences: UpcomingPreferences,
    private val getCategories: GetCategories,
    private val uiPreferences: UiPreferences,
    private val dispatchers: Dispatchers,
) : StateViewModel<UpcomingViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val items: List<UpcomingUiModel> = emptyList(),
        /** The days of [items], read off the same list, so a dot and the header it scrolls to agree. */
        val days: Map<LocalDate, UpcomingDay> = emptyMap(),
        /** The month the calendar shows: this one until the owner turns it, kept across a rotation. */
        val month: YearMonth = thisMonth(),
        val filter: Filter = Filter(),
        /** Every live category, the default one first, for the filter dialog. */
        val categories: List<Category> = emptyList(),
        val dialog: Dialog? = null,
        val relativeTime: Boolean = true,
        val dateFormat: String = "",
    )

    /** The Filter button's category lists: a title shows when in an included one and in no excluded one. */
    data class Filter(val included: Set<Long> = emptySet(), val excluded: Set<Long> = emptySet()) {
        /** Lights the Filter button, as upstream's does once either list holds a category. */
        val active: Boolean
            get() = included.isNotEmpty() || excluded.isNotEmpty()

        /**
         * Excluded first, as the list applies it: a restore adds to both lists, so a category can be
         * in each, and its row then shows the excluded state the list is actually in.
         */
        fun categoryState(categoryId: Long): TriState = when (categoryId) {
            in excluded -> TriState.ENABLED_NOT
            in included -> TriState.ENABLED_IS
            else -> TriState.DISABLED
        }
    }

    sealed interface Dialog {
        /** Upstream's filter dialog, a Categories tab alone. It reads [State.filter], so a rotation loses nothing. */
        data object FilterSheet : Dialog
    }

    init {
        viewModelScope.launch {
            upcoming()
                .distinctUntilChanged()
                .flowOn(dispatchers.io)
                .collect { (items, days) ->
                    mutableState.update { it.copy(loading = false, items = items, days = days) }
                }
        }
        viewModelScope.launch {
            filter().flowOn(dispatchers.io).collect { filter -> mutableState.update { it.copy(filter = filter) } }
        }
        viewModelScope.launch {
            getCategories.subscribe()
                .flowOn(dispatchers.io)
                .collect { categories -> mutableState.update { it.copy(categories = categories) } }
        }
        viewModelScope.launch {
            combine(uiPreferences.relativeTime.withCurrent(), uiPreferences.dateFormat.withCurrent()) { relative, format ->
                relative to format
            }.collect { (relative, format) ->
                mutableState.update { it.copy(relativeTime = relative, dateFormat = format) }
            }
        }
    }

    /** The calendar's arrows. */
    fun showPreviousMonth() {
        mutableState.update { it.copy(month = it.month.minusMonth()) }
    }

    fun showNextMonth() {
        mutableState.update { it.copy(month = it.month.plusMonth()) }
    }

    fun showFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.FilterSheet) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    /**
     * A category row of the filter dialog, cycling neither, included, excluded, as upstream's
     * `UpcomingViewModel.cycleCategory` does. A category a restore left in both lists shows as
     * excluded ([Filter.categoryState]), so its tap clears both, the step after excluded.
     */
    fun cycleCategory(categoryId: Long) {
        val included = upcomingPreferences.filterIncludedCategories
        val excluded = upcomingPreferences.filterExcludedCategories
        when (categoryId) {
            in excluded.get() -> {
                excluded.getAndSet { it - categoryId }
                included.getAndSet { it - categoryId }
            }
            in included.get() -> {
                included.getAndSet { it - categoryId }
                excluded.getAndSet { it + categoryId }
            }
            else -> included.getAndSet { it + categoryId }
        }
    }

    /**
     * The two lists read against the live categories, the default one among them. Deleting a
     * category leaves its id in the lists; kept, an included one would hide every entry and light
     * the button with no row in the dialog left to clear it. A restore remaps ids, so nothing
     * needs pruning from the stored lists.
     */
    private fun filter() = combine(
        upcomingPreferences.filterIncludedCategories.withCurrent(),
        upcomingPreferences.filterExcludedCategories.withCurrent(),
        getCategories.subscribe().map { categories -> categories.mapTo(HashSet()) { it.id } },
    ) { included, excluded, live ->
        Filter(included.filterTo(HashSet()) { it in live }, excluded.filterTo(HashSet()) { it in live })
    }
        .distinctUntilChanged()

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
                    getMergedGroups.subscribe(),
                    filter(),
                ) { library, included, excluded, groups, filter ->
                    val zone = TimeZone.currentSystemDefault()
                    val today = Clock.System.now().toLocalDateTime(zone).date
                    val items = upcomingUiModels(
                        upcomingEntries(
                            library = library,
                            included = included.mapNotNullTo(mutableSetOf(), String::toLongOrNull),
                            excluded = excluded.mapNotNullTo(mutableSetOf(), String::toLongOrNull),
                            startOfToday = today.atStartOfDayIn(zone).toEpochMilliseconds(),
                            groups = groups,
                            filter = filter,
                        ),
                        zone,
                    )
                    items to upcomingDays(items)
                }
            }
        }
}

private fun thisMonth(): YearMonth = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.yearMonth
