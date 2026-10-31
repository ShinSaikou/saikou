// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/sources/MigrateSourceViewModel.kt (modified: yes)
package com.shinsaikou.ui.migration.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.interactor.SetMigrateSorting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.Collections

/**
 * Browse's Migrate tab (phase 4 unit MB): every source holding library entries, with the count, in
 * upstream's order, and the two sort buttons.
 *
 * Saikou: Metro and the graph's dispatchers for Injekt and `launchIO`; upstream's
 * `GetSourcesWithFavoriteCount` folded in, over `MangaRepository.getSourceIdsWithFavoriteCountAsFlow`
 * and the registry, where upstream reads a source table; and the state stays Loading until the registry
 * has loaded (R69 items 3 and 4), its flag read before the sources, so no installed source reads
 * "Not installed" for a frame after a cold start. The registry's source and stub lists are inputs, so
 * a reinstalled extension loses its "Not installed" at once and a remembered name replaces the id as
 * soon as its lookup ends. A source is resolved live, else as a stub with its remembered name. The
 * two sort keys are read as state flows, which hold the stored value from the start on either
 * preference store.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(MigrateSourcesViewModel::class)
@Inject
class MigrateSourcesViewModel(
    private val preferences: SourcePreferences,
    private val setMigrateSorting: SetMigrateSorting,
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<MigrateSourcesViewModel.State>(State()) {

    init {
        viewModelScope.launch {
            combine(
                preferences.migrationSortingMode.stateIn(viewModelScope),
                preferences.migrationSortingDirection.stateIn(viewModelScope),
                mangaRepository.getSourceIdsWithFavoriteCountAsFlow(),
                combine(sourceManager.getCatalogueSources(), sourceManager.getStubSources()) { _, _ -> },
                sourceManager.isInitialized,
            ) { mode, direction, counts, _, _ ->
                // R69 item 3: the flag is read here, before the sources, never taken from the
                // combine, whose collectors can pair a true flag with the list from before the load.
                val initialized = sourceManager.isInitialized.value
                State(
                    isLoading = !initialized,
                    items = if (initialized) rows(counts).sortedWith(sortFn(direction, mode)) else emptyList(),
                    sortingMode = mode,
                    sortingDirection = direction,
                )
            }
                .flowOn(dispatchers.io)
                .catch { e ->
                    if (e is CancellationException) throw e
                    logcat(LogPriority.WARN) { "Migrate tab: the sources with library entries failed: ${e::class.simpleName}" }
                }
                .collect { mutableState.value = it }
        }
    }

    /** Local is left out, as in upstream; any other id is its live source, else a stub. */
    private fun rows(counts: Map<Long, Long>): List<SourceRow> = counts
        .filterKeys { it != SourceIds.LOCAL }
        .map { (id, count) ->
            val source = sourceManager.get(id) ?: sourceManager.getOrStub(id)
            SourceRow(id = id, name = source.name, lang = source.lang, isStub = source is StubSource, count = count)
        }

    /**
     * Upstream's comparator exactly: in either mode a stub before a live source, then the lowercased
     * name through the collator or the count; descending reverses the whole comparator, stubs
     * included, so they go last.
     */
    private fun sortFn(
        direction: SetMigrateSorting.Direction,
        sorting: SetMigrateSorting.Mode,
    ): java.util.Comparator<SourceRow> {
        val sortFn: (SourceRow, SourceRow) -> Int = { a, b ->
            when (sorting) {
                SetMigrateSorting.Mode.ALPHABETICAL -> {
                    when {
                        a.isStub && !b.isStub -> -1
                        b.isStub && !a.isStub -> 1
                        else -> a.name.lowercase().compareToWithCollator(b.name.lowercase())
                    }
                }
                SetMigrateSorting.Mode.TOTAL -> {
                    when {
                        a.isStub && !b.isStub -> -1
                        b.isStub && !a.isStub -> 1
                        else -> a.count.compareTo(b.count)
                    }
                }
            }
        }

        return when (direction) {
            SetMigrateSorting.Direction.ASCENDING -> Comparator(sortFn)
            SetMigrateSorting.Direction.DESCENDING -> Collections.reverseOrder(sortFn)
        }
    }

    fun toggleSortingMode() {
        with(state.value) {
            val newMode = when (sortingMode) {
                SetMigrateSorting.Mode.ALPHABETICAL -> SetMigrateSorting.Mode.TOTAL
                SetMigrateSorting.Mode.TOTAL -> SetMigrateSorting.Mode.ALPHABETICAL
            }

            setMigrateSorting.await(newMode, sortingDirection)
        }
    }

    fun toggleSortingDirection() {
        with(state.value) {
            val newDirection = when (sortingDirection) {
                SetMigrateSorting.Direction.ASCENDING -> SetMigrateSorting.Direction.DESCENDING
                SetMigrateSorting.Direction.DESCENDING -> SetMigrateSorting.Direction.ASCENDING
            }

            setMigrateSorting.await(sortingMode, newDirection)
        }
    }

    /**
     * One source holding library entries. The id, name and language only, never a live source: a
     * state object outlives the extension it came from.
     */
    data class SourceRow(val id: Long, val name: String, val lang: String, val isStub: Boolean, val count: Long)

    data class State(
        val isLoading: Boolean = true,
        val items: List<SourceRow> = listOf(),
        val sortingMode: SetMigrateSorting.Mode = SetMigrateSorting.Mode.ALPHABETICAL,
        val sortingDirection: SetMigrateSorting.Direction = SetMigrateSorting.Direction.ASCENDING,
    ) {
        val isEmpty = items.isEmpty()
    }
}
