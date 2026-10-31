// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/more/settings/screen/advanced/ClearDatabaseScreen.kt (modified: yes) ClearDatabaseViewModel only, on Metro and MangaRepository
package com.shinsaikou.ui.storage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Modified: Metro constructor injection instead of Injekt; the list is
 * `MangaRepository.getSourceIdsWithNonLibraryMangaAsFlow`, which leaves out soft-deleted rows the
 * delete never removes (upstream's query counts them), named through [SourceManager.getOrStub] where
 * upstream's `GetSourcesWithNonLibraryManga` maps to its domain source; the delete is
 * `MangaRepository.deleteNonLibraryManga` with no `removeResettedHistory` after it, because Saikou
 * already drops reset history inside every reset and at start-up; and the selection is a set.
 * Upstream's delete takes the ticked source ids at confirm time, so rows a sync moves out of the
 * library while the dialog is open would go too; here the confirmation freezes the rows of the
 * ticked sources when it opens, as the list shows them, and the delete
 * (`MangaRepository.deleteNonLibraryMangaByIds`, replacing `removeMangaBySourceId`) acts on those
 * and only those.
 *
 * The delete is a hard delete of rows that are neither favourites nor tombstones, so it logs no sync
 * change (`delete_manga_change` needs one or the other); their chapters, history, queue rows and
 * bookmark notes cascade with them, and their downloads stay in the manifest, where the storage
 * screen lists them.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ClearDatabaseViewModel::class)
@Inject
class ClearDatabaseViewModel(
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<ClearDatabaseViewModel.State>(State.Loading) {

    init {
        viewModelScope.launch {
            mangaRepository.getSourceIdsWithNonLibraryMangaAsFlow()
                .flowOn(dispatchers.io)
                .collectLatest { counts ->
                    val items = counts.map { (sourceId, count) -> SourceWithCount(sourceId, sourceName(sourceId), count) }
                        .sortedBy { it.name }
                    mutableState.update { old ->
                        when (old) {
                            State.Loading -> State.Ready(items)
                            // A source that has nothing left to clear is no longer ticked.
                            is State.Ready -> old.copy(items = items, selection = old.selection intersect counts.keys)
                        }
                    }
                }
        }
    }

    /**
     * Upstream's `Source.visualName`: the name, and the language in capitals after it when the source
     * has one.
     */
    private fun sourceName(sourceId: Long): String {
        val source = sourceManager.getOrStub(sourceId)
        return if (source.lang.isBlank()) source.name else "${source.name} (${source.lang.uppercase()})"
    }

    /**
     * Deletes the rows the open confirmation froze, less those that joined the library or gained a
     * tombstone since; not cancelled by leaving the screen.
     */
    suspend fun removeConfirmedManga(keepReadManga: Boolean) = withContext(NonCancellable) {
        val confirming = (state.value as? State.Ready)?.confirming ?: return@withContext
        mangaRepository.deleteNonLibraryMangaByIds(confirming, keepReadManga)
    }

    fun toggleSelection(sourceId: Long) = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        val selection = if (sourceId in state.selection) state.selection - sourceId else state.selection + sourceId
        state.copy(selection = selection)
    }

    fun clearSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = emptySet())
    }

    fun selectAll() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = state.items.mapTo(HashSet()) { it.id })
    }

    fun invertSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = state.items.map { it.id }.filterNot { it in state.selection }.toSet())
    }

    /**
     * Opens the confirmation over the rows the ticked sources hold now, the rows the list counts;
     * nothing ticked, or nothing left in them, opens nothing.
     */
    fun showConfirmation() {
        val selection = (state.value as? State.Ready)?.selection.orEmpty()
        if (selection.isEmpty()) return
        viewModelScope.launch {
            val ids = mangaRepository.getLiveNonLibraryMangaIds(selection.toList())
            mutableState.update { state ->
                if (state !is State.Ready) return@update state
                state.copy(confirming = ids.ifEmpty { null })
            }
        }
    }

    fun hideConfirmation() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(confirming = null)
    }

    /** Upstream's `SourceWithCount`, with the name already made visual. */
    data class SourceWithCount(val id: Long, val name: String, val count: Long)

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val items: List<SourceWithCount>,
            val selection: Set<Long> = emptySet(),
            /**
             * The manga ids the open confirmation covers, fixed when it opened: the delete acts on
             * these and only these, whatever a sync or the selection does meanwhile. Null while
             * closed.
             */
            val confirming: List<Long>? = null,
        ) : State
    }
}
