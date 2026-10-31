package com.shinsaikou.ui.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.GetLanguagesWithSources
import com.shinsaikou.domain.source.interactor.ToggleLanguage
import com.shinsaikou.domain.source.interactor.ToggleSource
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.browse.matureSourceIds
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import tachiyomi.domain.source.model.Source

/**
 * Upstream's `SourcesFilterViewModel` (phase 5 unit BF): the languages of the installed catalogue
 * sources with their sources, which languages are enabled and which sources are disabled, from
 * [GetLanguagesWithSources] and the two preferences' changes. A language's switch writes
 * `source_languages` through [ToggleLanguage] and a source's checkbox `hidden_catalogues` through
 * [ToggleSource], the two keys an upstream backup carries; the Sources page re-projects on both.
 *
 * Two things upstream gets elsewhere. The state stays [State.Loading] until the source registry has
 * finished its first load, as the Sources page's spinner does, so a cold start never shows "No
 * installed source found" over sources still loading; the registry publishes its list before its
 * flag, so the first list read after the flag is the loaded one. And while the mature sources switch
 * is off an adult extension's sources are left out, a language with nothing else with them: upstream
 * never loads those extensions with the switch off, so its screen never lists them.
 *
 * A failure anywhere in the chain is [State.Error], which the screen reports and leaves on, as
 * upstream's does.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(SourcesFilterViewModel::class)
@Inject
class SourcesFilterViewModel(
    private val preferences: SourcePreferences,
    private val getLanguagesWithSources: GetLanguagesWithSources,
    private val toggleSource: ToggleSource,
    private val toggleLanguage: ToggleLanguage,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<SourcesFilterViewModel.State>(State.Loading) {

    init {
        viewModelScope.launch {
            flow<State> {
                sourceManager.isInitialized.first { it }
                emitAll(
                    combine(
                        getLanguagesWithSources.subscribe(),
                        preferences.enabledLanguages.withCurrent(),
                        preferences.disabledSources.withCurrent(),
                        preferences.showNsfwSource.withCurrent(),
                        extensionManager.installedExtensions,
                    ) { languagesWithSources, enabledLanguages, disabledSources, showNsfw, installed ->
                        val mature = if (showNsfw) emptySet() else matureSourceIds(installed)
                        State.Success(
                            items = languagesWithSources
                                .map { (language, sources) -> language to sources.filterNot { it.id in mature } }
                                .filter { (_, sources) -> sources.isNotEmpty() },
                            enabledLanguages = enabledLanguages,
                            disabledSources = disabledSources,
                        )
                    },
                )
            }
                .flowOn(dispatchers.io)
                .catch { throwable -> emit(State.Error(throwable)) }
                .collect { mutableState.value = it }
        }
    }

    /** A source row's tap: a disabled source is enabled and any other disabled. */
    fun toggleSource(source: Source) {
        toggleSource.await(source.id)
    }

    /** A language header's tap: an enabled language is switched off and any other on. */
    fun toggleLanguage(language: String) {
        toggleLanguage.await(language)
    }

    sealed interface State {

        @Immutable
        data object Loading : State

        @Immutable
        data class Error(val throwable: Throwable) : State

        /**
         * [items] in upstream's order, language code and its sources ([GetLanguagesWithSources]);
         * [disabledSources] holds ids as text, as `hidden_catalogues` stores them.
         *
         * [items] is a list, not the interactor's map: a map's equality ignores its order, so a
         * change that only re-sorts the languages (one switched off moves down) would make a state
         * equal to the one before, which the state flow then drops.
         */
        @Immutable
        data class Success(
            val items: List<Pair<String, List<Source>>>,
            val enabledLanguages: Set<String>,
            val disabledSources: Set<String>,
        ) : State {

            val isEmpty: Boolean
                get() = items.isEmpty()
        }
    }
}
