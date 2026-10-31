// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/browse/migration/manga/MigrateMangaViewModel.kt (modified: yes)
package com.shinsaikou.ui.migration.picker

import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga

/**
 * One source's library entries, to tick for a migration (phase 4 unit MB), upstream's
 * `MigrateMangaViewModel`.
 *
 * Saikou: Metro's assisted factory and the graph's dispatchers; the source is held as its name only,
 * the stub's remembered name when its extension is gone, since a state object outlives the
 * extension it came from; and [selection] is the selection a screen restored after a process death
 * hands back, in tap order. The entries follow the library live, so one that leaves it leaves the
 * list at once; a failure of the feed is logged and shown as upstream's toast.
 */
@AssistedInject
class MigratePickerViewModel(
    @Assisted private val sourceId: Long,
    @Assisted selection: List<Long>,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val dispatchers: Dispatchers,
) : StateViewModel<MigratePickerViewModel.State>(State(selection = selection)) {

    private val _events: Channel<Event> = Channel()
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            mutableState.update { state ->
                val source = sourceManager.getOrStub(sourceId)
                state.copy(sourceName = source.name.ifBlank { source.id.toString() })
            }

            getFavorites.subscribe(sourceId)
                .map { manga ->
                    manga
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })
                }
                .flowOn(dispatchers.io)
                .catch { e ->
                    if (e is CancellationException) throw e
                    logcat(LogPriority.WARN) { "Migrate: the entries of source $sourceId failed: ${e::class.simpleName}" }
                    _events.send(Event.FailedFetchingFavorites)
                    mutableState.update { state ->
                        state.copy(titleList = listOf())
                    }
                }
                .collectLatest { list ->
                    mutableState.update { it.copy(titleList = list) }
                }
        }
    }

    fun toggleSelection(item: Manga) {
        mutableState.update { state ->
            val selection = if (item.id in state.selection) state.selection - item.id else state.selection + item.id
            state.copy(selection = selection)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptyList()) }
    }

    sealed interface Event {
        data object FailedFetchingFavorites : Event
    }

    /** [selection] holds the ticked ids in the order they were ticked. */
    data class State(
        val sourceName: String? = null,
        val selection: List<Long> = emptyList(),
        val titleList: List<Manga>? = null,
    ) {

        val titles: List<Manga>
            get() = titleList ?: listOf()

        val isLoading: Boolean
            get() = sourceName == null || titleList == null

        val isEmpty: Boolean
            get() = titles.isEmpty()

        val selectionMode = selection.isNotEmpty()
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long, selection: List<Long>): MigratePickerViewModel
    }
}
