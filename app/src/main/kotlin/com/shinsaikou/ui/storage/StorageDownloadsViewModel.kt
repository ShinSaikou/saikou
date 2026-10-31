package com.shinsaikou.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.downloads.OrphanDownloads
import com.shinsaikou.downloads.OrphanTitle
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The storage screen's list of downloads not in the library (phase 4 unit ST, upstream issue #565):
 * the titles [OrphanDownloads] lists, ticked the way upstream's clear database ticks sources, and a
 * delete behind a confirmation that names them.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(StorageDownloadsViewModel::class)
@Inject
class StorageDownloadsViewModel(
    private val orphanDownloads: OrphanDownloads,
) : StateViewModel<StorageDownloadsViewModel.State>(State()) {

    data class State(
        /** Null until the first list has been read. */
        val titles: List<OrphanTitle>? = null,
        /** Manga ids, as the rows are keyed. */
        val selection: Set<Long> = emptySet(),
        /**
         * The titles the open confirmation names, fixed when it opened: the delete acts on these
         * and only these, whatever the list or the selection does meanwhile. Null while closed.
         */
        val confirming: List<OrphanTitle>? = null,
    )

    init {
        refresh()
    }

    /** Reads the list again, keeping only the ticks whose titles are still on it. */
    fun refresh() {
        viewModelScope.launch {
            val titles = orphanDownloads.list()
            val ids = titles.mapTo(HashSet()) { it.mangaId }
            mutableState.update { it.copy(titles = titles, selection = it.selection intersect ids) }
        }
    }

    fun toggleSelection(mangaId: Long) = mutableState.update {
        it.copy(selection = if (mangaId in it.selection) it.selection - mangaId else it.selection + mangaId)
    }

    fun selectAll() = mutableState.update { state ->
        state.copy(selection = state.titles.orEmpty().mapTo(HashSet()) { it.mangaId })
    }

    fun invertSelection() = mutableState.update { state ->
        state.copy(selection = state.titles.orEmpty().map { it.mangaId }.filterNot { it in state.selection }.toSet())
    }

    /** Opens the confirmation over the ticked titles; nothing ticked opens nothing. */
    fun showConfirmation() = mutableState.update { state ->
        val named = state.titles.orEmpty().filter { it.mangaId in state.selection }
        state.copy(confirming = named.ifEmpty { null })
    }

    fun hideConfirmation() = mutableState.update { it.copy(confirming = null) }

    /**
     * Deletes what the confirmation named, as [OrphanDownloads.delete] narrows it, then reads the
     * list again. The delete is not cancelled by leaving the screen: files would otherwise go
     * without their rows until the next sweep.
     */
    fun delete() {
        val confirmed = state.value.confirming ?: return
        mutableState.update { it.copy(confirming = null, selection = emptySet()) }
        viewModelScope.launch {
            withContext(NonCancellable) { orphanDownloads.delete(confirmed) }
            val titles = orphanDownloads.list()
            mutableState.update { it.copy(titles = titles) }
        }
    }
}
