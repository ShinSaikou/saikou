package com.shinsaikou.ui.history

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations

/**
 * The history tab. One row per entry, newest first, and the search runs in the database rather
 * than over the loaded rows so a long history stays one query.
 *
 * The list holds one row per entry, its latest read chapter, so removing a row removes the entry's
 * whole history: removing only that chapter's would bring the entry straight back with the chapter
 * read before it. Selection is keyed by the row id, which is one to one with the entry.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(HistoryViewModel::class)
@Inject
class HistoryViewModel(
    private val getHistory: GetHistory,
    private val removeHistory: RemoveHistory,
    private val getNextChapters: GetNextChapters,
    private val getScanlatorPriority: GetScanlatorPriority,
    private val uiPreferences: UiPreferences,
    private val dispatchers: Dispatchers,
) : StateViewModel<HistoryViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val items: List<HistoryWithRelations> = emptyList(),
        val query: String = "",
        val selection: Set<Long> = emptySet(),
        val dialog: Dialog? = null,
        /** Relative timestamps, `relative_time_v2`, for the day headers. */
        val relativeTime: Boolean = true,
        /** The stored date format, `app_date_format`, for the day headers. */
        val dateFormat: String = "",
    ) {
        val selectionMode: Boolean
            get() = selection.isNotEmpty()

        /**
         * Safe as a size comparison because the projection prunes ids it no longer shows, so the
         * selection is always a subset of [items].
         */
        val allSelected: Boolean
            get() = items.isNotEmpty() && selection.size == items.size
    }

    sealed interface Dialog {
        data object ClearAll : Dialog

        /** The confirm a row's delete button opens, naming the entry it removes. */
        data class Remove(val item: HistoryWithRelations) : Dialog

        /**
         * [count] is the size the selection had when the confirm opened, so the number the dialog
         * shows is the number the owner was asked about even if the query re-runs underneath it.
         */
        data class RemoveSelected(val count: Int) : Dialog
    }

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event

        data class OpenReader(val mangaId: Long, val chapterId: Long) : Event
    }

    private val queryFlow = MutableStateFlow("")
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            queryFlow
                .flatMapLatest { query -> getHistory.subscribe(query) }
                .flowOn(dispatchers.io)
                .collect { rows ->
                    val visibleIds = rows.mapTo(mutableSetOf()) { it.id }
                    mutableState.update { current ->
                        val selection = current.selection intersect visibleIds
                        current.copy(
                            loading = false,
                            items = rows,
                            // A removal, or a narrower search, takes rows off screen. Keeping them
                            // selected would leave the bar counting rows nobody can see.
                            selection = selection,
                            // And a confirm naming a count the selection no longer has, or an entry
                            // the list no longer shows, would be asking about nothing, so it closes.
                            dialog = when (val dialog = current.dialog) {
                                is Dialog.RemoveSelected -> dialog.takeIf { selection.isNotEmpty() }
                                is Dialog.Remove -> dialog.takeIf { rows.any { it.mangaId == dialog.item.mangaId } }
                                else -> dialog
                            },
                        )
                    }
                }
        }
        viewModelScope.launch {
            combine(uiPreferences.relativeTime.withCurrent(), uiPreferences.dateFormat.withCurrent()) { relative, format ->
                relative to format
            }.collect { (relative, format) ->
                mutableState.update { it.copy(relativeTime = relative, dateFormat = format) }
            }
        }
    }

    fun search(query: String) {
        mutableState.update { it.copy(query = query) }
        // The history view matches against a lower-cased title, so an upper-case letter typed
        // here would otherwise match nothing.
        queryFlow.value = query.lowercase()
    }

    /** A long press opens selection mode, a tap inside it lands here too. */
    fun toggleSelection(id: Long) {
        mutableState.update {
            val selection = if (id in it.selection) it.selection - id else it.selection + id
            it.copy(selection = selection)
        }
    }

    fun selectAll() {
        mutableState.update { state -> state.copy(selection = state.items.mapTo(mutableSetOf()) { it.id }) }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    /**
     * Opens the row's chapter while it is unfinished, and the chapter after it once it is read, as
     * upstream does: a read chapter opens at its first page, so resuming it would replay it. The
     * chapter after is the next number in reading order, its copy picked by the title's scanlator
     * ranking as the library's continue button picks it. A chapter the list no longer holds (gone
     * from the source, or its group excluded) is opened as before, so the reader can say so.
     */
    fun resume(item: HistoryWithRelations) {
        viewModelScope.launch {
            val chapterId = withContext(dispatchers.io) {
                val chapters = getNextChapters.await(item.mangaId, onlyUnread = false)
                val current = chapters.firstOrNull { it.id == item.chapterId }
                if (current == null || !current.read) return@withContext item.chapterId
                val deduplicated = chapters.removeDuplicates(current, getScanlatorPriority.await(item.mangaId))
                deduplicated.getOrNull(deduplicated.indexOf(current) + 1)?.id
            }
            if (chapterId == null) {
                eventChannel.send(Event.Snackbar(R.string.history_no_next_chapter))
            } else {
                eventChannel.send(Event.OpenReader(item.mangaId, chapterId))
            }
        }
    }

    fun askRemove(item: HistoryWithRelations) {
        mutableState.update { it.copy(dialog = Dialog.Remove(item)) }
    }

    /**
     * Removes the entry's history, every chapter of it, so the entry leaves the list. Read chapters
     * stay read, which is why the interactor calls this a reset; the reading time goes with the rows.
     */
    fun remove(item: HistoryWithRelations) {
        viewModelScope.launch {
            withContext(dispatchers.io) { removeHistory.await(item.mangaId) }
            mutableState.update { it.copy(dialog = null) }
        }
    }

    fun askClearAll() {
        mutableState.update { it.copy(dialog = Dialog.ClearAll) }
    }

    fun askRemoveSelected() {
        val count = state.value.selection.size
        if (count == 0) return
        mutableState.update { it.copy(dialog = Dialog.RemoveSelected(count)) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(dispatchers.io) { removeHistory.awaitAll() }
            mutableState.update { it.copy(dialog = null, selection = emptySet()) }
            eventChannel.send(Event.Snackbar(R.string.history_cleared))
        }
    }

    /**
     * `RemoveHistory.await(mangaId)` takes every chapter's history of each selected entry, as a
     * single row's removal does. Despite the interactor's name it deletes: `HistoryRepositoryImpl`
     * zeroes the rows and sweeps every zeroed row in the same transaction (05-data decision E14).
     * `awaitAll()` would empty the whole table, answering "remove these three" by removing all forty.
     */
    fun removeSelected() {
        val current = state.value
        val selected = current.items.filter { it.id in current.selection }
        if (selected.isEmpty()) {
            dismissDialog()
            return
        }
        viewModelScope.launch {
            withContext(dispatchers.io) {
                selected.mapTo(linkedSetOf()) { it.mangaId }.forEach { removeHistory.await(it) }
            }
            mutableState.update { it.copy(dialog = null, selection = emptySet()) }
            eventChannel.send(Event.Snackbar(R.string.history_removed))
        }
    }
}
