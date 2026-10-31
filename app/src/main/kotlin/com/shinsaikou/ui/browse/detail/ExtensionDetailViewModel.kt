package com.shinsaikou.ui.browse.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.ConfigurableSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One installed extension: what it is, and the switches that decide which of its sources the rest
 * of the app can see.
 *
 * Everything on screen is projected from the extension manager's own state flows rather than read
 * once in `init`. An extension is a file on disk that a background install, an update or the
 * uninstall button on another screen can replace underneath this one, and a snapshot taken at open
 * time would keep showing a version that is no longer there. The same rule turns an uninstall into
 * an ordinary emission: the package leaves the installed list, the projection becomes
 * [State.NotFound], and the screen is asked to pop.
 */
@AssistedInject
class ExtensionDetailViewModel(
    @Assisted private val packageName: String,
    private val extensionManager: ExtensionManager,
    private val sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<ExtensionDetailViewModel.State>(State.Loading) {

    sealed interface State {
        /** The manager has not finished its first load pass, so an empty list means nothing yet. */
        data object Loading : State

        /** The package is gone: uninstalled while this screen was open, or a stale deep link. */
        data object NotFound : State

        @Immutable
        data class Success(
            val ext: Extension.Installed,
            val available: Extension.Available?,
            val sources: List<SourceRow>,
            val installStep: InstallStep?,
            /**
             * Incognito is stored per extension package, so it belongs here as well as on each
             * row: the action row's switch still needs a defined position for an extension whose
             * sources all failed to load.
             */
            val incognito: Boolean = false,
            val dialog: Dialog? = null,
        ) : State {
            /** An update needs both a newer index entry and the manager's own verdict on it. */
            val canUpdate: Boolean get() = available != null && ext.hasUpdate

            /** True while an install is in flight, which is what disables the action buttons. */
            val busy: Boolean get() = installStep?.isCompleted() == false
        }
    }

    @Immutable
    data class SourceRow(
        val source: Source,
        val enabled: Boolean,
        val incognito: Boolean,
        val configurable: Boolean,
        val homeUrl: String?,
    ) {
        val id: Long get() = source.id
        val name: String get() = source.name
        val lang: String get() = source.lang
    }

    sealed interface Dialog {
        data object Uninstall : Dialog
    }

    sealed interface Event {
        data object Back : Event

        data class OpenSourceSettings(val sourceId: Long) : Event

        data class OpenWebView(val url: String, val sourceId: Long, val title: String) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            // The flag and the installed list are read here, flag first, instead of taken from the
            // combine: its two collectors resume on their own, so the flag's can run before the
            // list's and pair a true flag with the empty list from before the first load. That
            // pairing is a NotFound for an installed extension, and a Back that pops this screen
            // as the load finishes. The manager publishes the list before it sets the flag, so a
            // true flag read first always comes with the loaded list.
            combine(
                extensionManager.isInitialized,
                extensionManager.installedExtensions,
                extensionManager.availableExtensions,
                extensionManager.installSteps,
                preferenceChanges(),
            ) { _, _, available, steps, preferences ->
                val initialized = extensionManager.isInitialized.value
                project(initialized, extensionManager.installedExtensions.value, available, steps, preferences)
            }
                .flowOn(dispatchers.io)
                .collect { projected ->
                    val previous = state.value
                    // The dialog belongs to the screen, not to the manager, so it is carried over
                    // instead of rebuilt: an install step arriving while the uninstall dialog is up
                    // would otherwise close it under the owner's finger.
                    mutableState.value = when {
                        projected is State.Success && previous is State.Success ->
                            projected.copy(dialog = previous.dialog)
                        else -> projected
                    }
                    if (projected is State.NotFound && previous !is State.NotFound) {
                        eventChannel.send(Event.Back)
                    }
                }
        }
    }

    /** Starts the manager's update job. Progress comes back through its `installSteps` flow. */
    fun update() {
        val installed = extensionManager.installedExtensions.value
            .firstOrNull { it.packageName == packageName }
            ?: return
        extensionManager.update(installed)
    }

    fun requestUninstall() {
        mutableState.update { if (it is State.Success) it.copy(dialog = Dialog.Uninstall) else it }
    }

    fun dismissDialog() {
        mutableState.update { if (it is State.Success) it.copy(dialog = null) else it }
    }

    /**
     * Removes the extension. Nothing here sets [State.NotFound]: the manager republishes its
     * installed list and the projection reaches that conclusion on its own, which keeps one path
     * for an uninstall started here and one started anywhere else.
     */
    fun uninstall() {
        dismissDialog()
        viewModelScope.launch {
            withContext(dispatchers.io) { extensionManager.uninstall(packageName) }
        }
    }

    /**
     * The preference holds the ids that are switched OFF and the row shows whether a source is ON,
     * so enabling one REMOVES its id. Writing [enabled] straight through is the bug this screen
     * would otherwise ship: every switch would read correctly and do the opposite.
     */
    fun toggleSource(sourceId: Long, enabled: Boolean) {
        val key = sourceId.toString()
        val disabled = sourcePreferences.disabledSources.get()
        sourcePreferences.disabledSources.set(if (enabled) disabled - key else disabled + key)
    }

    fun enableAll() = setAllSources(enabled = true)

    fun disableAll() = setAllSources(enabled = false)

    fun toggleIncognito(enabled: Boolean) {
        val current = sourcePreferences.incognitoExtensions.get()
        sourcePreferences.incognitoExtensions.set(
            if (enabled) current + packageName else current - packageName,
        )
    }

    fun openSourceSettings(sourceId: Long) {
        viewModelScope.launch { eventChannel.send(Event.OpenSourceSettings(sourceId)) }
    }

    /** A source with no web presence has no page to open, so the row hides the action entirely. */
    fun openWebView(row: SourceRow) {
        val url = row.homeUrl ?: return
        viewModelScope.launch {
            eventChannel.send(Event.OpenWebView(url = url, sourceId = row.id, title = row.name))
        }
    }

    /**
     * One write for the whole extension rather than a write per row: each `set` publishes to every
     * collector of the preference, and a per-row loop would make the source list recompose once
     * per source for a single tap.
     */
    private fun setAllSources(enabled: Boolean) {
        val ids = (state.value as? State.Success)
            ?.sources
            ?.mapTo(mutableSetOf()) { it.id.toString() }
            .orEmpty()
        if (ids.isEmpty()) return
        val disabled = sourcePreferences.disabledSources.get()
        sourcePreferences.disabledSources.set(if (enabled) disabled - ids else disabled + ids)
    }

    private fun preferenceChanges(): Flow<Preferences> = combine(
        sourcePreferences.disabledSources.withCurrent(),
        sourcePreferences.incognitoExtensions.withCurrent(),
    ) { disabled, incognito -> Preferences(disabled, incognito) }

    private fun project(
        initialized: Boolean,
        installed: List<Extension.Installed>,
        available: List<Extension.Available>,
        steps: Map<String, InstallStep>,
        preferences: Preferences,
    ): State {
        if (!initialized) return State.Loading
        val ext = installed.firstOrNull { it.packageName == packageName } ?: return State.NotFound
        val incognito = packageName in preferences.incognitoExtensions
        return State.Success(
            ext = ext,
            available = available.firstOrNull { it.packageName == packageName },
            sources = ext.sources
                .map { it.toRow(preferences, incognito) }
                .sortedBy { it.name.lowercase() },
            installStep = steps[packageName],
            incognito = incognito,
        )
    }

    /**
     * The registry's instance wins over the one the extension published. A reload swaps the adapter
     * behind a source id, and a settings screen opened from the older object would configure
     * something no source lookup can reach any more.
     */
    private fun Source.toRow(preferences: Preferences, incognito: Boolean): SourceRow {
        val live = sourceManager.get(id) ?: this
        return SourceRow(
            source = live,
            enabled = live.id.toString() !in preferences.disabledSources,
            incognito = incognito,
            configurable = live is ConfigurableSource,
            homeUrl = live.homeUrl,
        )
    }

    /** The two preference sets the projection reads, carried together through one combine slot. */
    private data class Preferences(
        val disabledSources: Set<String>,
        val incognitoExtensions: Set<String>,
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(packageName: String): ExtensionDetailViewModel
    }
}
