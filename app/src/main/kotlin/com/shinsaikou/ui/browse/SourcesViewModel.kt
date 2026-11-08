package com.shinsaikou.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.ToggleSource
import com.shinsaikou.domain.source.interactor.ToggleSourcePin
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Sources page of the Browse tab. Rows are whatever the source manager currently publishes, so
 * installing or removing an extension changes this list without the page being revisited.
 *
 * Contract 08 section 5.3's two filters apply here as they do in Global Search: a source in a
 * language the owner has not enabled is left out, with `"all"` a literal code (ruling R57), and so
 * is every source of an adult extension while the mature sources switch is off.
 *
 * The row type carries only what the list renders: a live [CatalogueSource] in state would keep a
 * reference to an extension's class loader alive after the extension is gone.
 *
 * Pinned sources come first under their own label, as upstream's list puts them in a "Pinned" group
 * above the rest; Saikou has no language groups, so the rest sit under one label of their own. Each
 * group reads by name with the Local source last ([localSourceLast]). The
 * last used source (`lastUsedSource`, written when a source's listing opens) is listed once more
 * above both, under "Last used", as upstream's `GetEnabledSources` and `SourcesViewModel` list it
 * (phase 4 unit MP2).
 *
 * A long press opens upstream's `SourceOptionsDialog` over [State.dialog]: Pin or Unpin, and Disable,
 * which writes `hidden_catalogues` through [ToggleSource] and is not offered for the Local source.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(SourcesViewModel::class)
@Inject
class SourcesViewModel(
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val sourcePreferences: SourcePreferences,
    private val toggleSourcePin: ToggleSourcePin,
    private val toggleSource: ToggleSource,
    private val dispatchers: Dispatchers,
) : StateViewModel<SourcesViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val sources: List<SourceRow> = emptyList(),
        val query: String = "",
        /** The last used source's id, `-1` for none, as `lastUsedSource` stores it. */
        val lastUsedId: Long = -1L,
        /** The source whose long press options are open, upstream's `SourcesViewModel.Dialog`. */
        val dialog: SourceRow? = null,
    ) {
        /** [sources] as the page draws them, with the section labels between. */
        val items: List<SourceListItem> = sourceListItems(sources, lastUsedId)
    }

    /**
     * [lastUsed] marks the second listing of the last used source, in its own group (upstream's
     * `Source.isUsedLast`); its pin button still shows the source's pin, as upstream's copy keeps
     * `Pin.Pinned`.
     */
    data class SourceRow(
        val id: Long,
        val name: String,
        val lang: String,
        val pinned: Boolean = false,
        val supportsLatest: Boolean = false,
        val lastUsed: Boolean = false,
    ) {
        /** Upstream's `Source.visualName`, the long press dialog's title. */
        val visualName: String
            get() = if (lang.isEmpty()) name else "$name (${lang.uppercase()})"

        /** Upstream's `Source.key()`: the last used copy is a second row of the same source. */
        val key: String
            get() = if (lastUsed) "$id-lastused" else "$id"

        val isLocal: Boolean
            get() = id == SourceIds.LOCAL
    }

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            // The flag and the source list are read here, flag first, instead of taken from the
            // combine (ruling R69 item 3): its collectors resume on their own, so the flag's can run
            // before the list's and pair a true flag with the list from before the first load, which
            // drops the spinner over an empty list for a frame. The registry sets its list before
            // its flag, so a true flag read first always comes with the loaded list.
            combine(
                sourceManager.getCatalogueSources(),
                sourceManager.isInitialized,
                extensionManager.installedExtensions,
                queryFlow,
                settingsChanges(),
            ) { _, _, installed, query, _ ->
                val initialized = sourceManager.isInitialized.value
                project(sourceManager.getCatalogueSources().first(), initialized, installed, query)
            }
                .flowOn(dispatchers.io)
                // The open dialog is the page's own and outlives a change to the list, as upstream's
                // state keeps it across each new source list.
                .collect { projected -> mutableState.update { projected.copy(dialog = it.dialog) } }
        }
    }

    fun search(query: String) {
        queryFlow.value = query
    }

    /** The row's pin button. The list follows through [settingsChanges], as a pin made elsewhere does. */
    fun togglePin(sourceId: Long) {
        toggleSourcePin.await(sourceId)
    }

    /** The long press dialog's Disable: the source leaves the list through [settingsChanges]. */
    fun toggleSource(sourceId: Long) {
        toggleSource.await(sourceId)
    }

    /** A row's long press, upstream's `showSourceDialog`. */
    fun showSourceDialog(row: SourceRow) {
        mutableState.update { it.copy(dialog = row) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    /**
     * An empty list before every provider has finished its first load is a spinner, not an empty
     * state: a source list that flashes "nothing installed" during startup reads as a lost extension.
     */
    private fun project(
        sources: List<CatalogueSource>,
        initialized: Boolean,
        installed: List<Extension.Installed>,
        query: String,
    ): State {
        val pinned = sourcePreferences.pinnedSources.get()
        val rows = sources
            .enabledSources(sourcePreferences, installed)
            .filter { it.matches(query) }
            .sortedWith(
                compareByDescending<CatalogueSource> { it.id.toString() in pinned }
                    .then(localSourceLast())
                    .thenBy { it.name.lowercase() },
            )
            .map {
                SourceRow(
                    id = it.id,
                    name = it.name,
                    lang = it.lang,
                    pinned = it.id.toString() in pinned,
                    supportsLatest = it.supportsLatest,
                )
            }
        return State(
            loading = !initialized,
            sources = rows,
            query = query,
            lastUsedId = sourcePreferences.lastUsedSource.get(),
        )
    }

    private fun CatalogueSource.matches(query: String): Boolean = query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        lang.contains(query, ignoreCase = true)

    /**
     * One tick per change to any preference the projection reads with `get()`, so hiding or
     * pinning a source, a language or the mature sources switch changed from somewhere else in the
     * app, or a source's listing opened, reaches this list on the next frame.
     */
    private fun settingsChanges(): Flow<Unit> = merge(
        sourcePreferences.disabledSources.changes(),
        sourcePreferences.pinnedSources.changes(),
        sourcePreferences.lastUsedSource.changes(),
        sourcePreferences.enabledLanguages.changes(),
        sourcePreferences.showNsfwSource.changes(),
    ).map { }.onStart { emit(Unit) }
}

/** One entry of the Sources page: a section label or a source row. */
sealed interface SourceListItem {
    data class Label(val section: SourceSection) : SourceListItem
    data class Source(val row: SourcesViewModel.SourceRow) : SourceListItem
}

enum class SourceSection { LAST_USED, PINNED, OTHER }

/**
 * The groups in upstream's order (`SourcesViewModel.collectLatestSources`'s comparator): the last used
 * source first, then the pinned sources, then the rest. The last used source is listed twice, as
 * upstream's `GetEnabledSources` lists it: once under "Last used" and once where it would otherwise
 * be, pinned or not. A listed last used source or a listed pin brings the labels; a label never
 * stands over nothing, so "Pinned" and "Other" are left out when empty, and with neither the page
 * is the plain list it was before pins could be made. [rows] arrive pinned first; a last used id
 * that is not among them (disabled, filtered out, uninstalled, or `-1`) draws no group.
 */
internal fun sourceListItems(rows: List<SourcesViewModel.SourceRow>, lastUsedId: Long = -1L): List<SourceListItem> {
    val lastUsed = rows.firstOrNull { it.id == lastUsedId }
    val (pinned, other) = rows.partition { it.pinned }
    if (lastUsed == null && pinned.isEmpty()) return rows.map { SourceListItem.Source(it) }
    return buildList {
        if (lastUsed != null) {
            add(SourceListItem.Label(SourceSection.LAST_USED))
            add(SourceListItem.Source(lastUsed.copy(lastUsed = true)))
        }
        if (pinned.isNotEmpty()) {
            add(SourceListItem.Label(SourceSection.PINNED))
            pinned.mapTo(this) { SourceListItem.Source(it) }
        }
        if (other.isNotEmpty()) {
            add(SourceListItem.Label(SourceSection.OTHER))
            other.mapTo(this) { SourceListItem.Source(it) }
        }
    }
}
