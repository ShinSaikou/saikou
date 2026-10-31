package com.shinsaikou.ui.browse.extension

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.ToggleLanguage
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.ui.browse.GetExtensionLanguages
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Upstream's `ExtensionFilterViewModel` (phase 5 unit BX): the languages of every available extension
 * ([GetExtensionLanguages]) and which of them are enabled, from `source_languages`. A language's
 * switch writes that set through [ToggleLanguage], the one key the source filter's switches and
 * Settings' language dialog write too, and the Extensions page re-projects on it.
 *
 * A failure anywhere in the chain is [State.Error], which the screen reports and leaves on, as the
 * source filter does. Upstream sends an event there that its screen never shows; Saikou says it.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ExtensionFilterViewModel::class)
@Inject
class ExtensionFilterViewModel(
    private val preferences: SourcePreferences,
    private val getExtensionLanguages: GetExtensionLanguages,
    private val toggleLanguage: ToggleLanguage,
    private val dispatchers: Dispatchers,
) : StateViewModel<ExtensionFilterViewModel.State>(State.Loading) {

    init {
        viewModelScope.launch {
            combine(
                getExtensionLanguages.subscribe(),
                preferences.enabledLanguages.withCurrent(),
            ) { languages, enabledLanguages ->
                State.Success(languages = languages, enabledLanguages = enabledLanguages) as State
            }
                .flowOn(dispatchers.io)
                .catch { throwable -> emit(State.Error(throwable)) }
                .collect { mutableState.value = it }
        }
    }

    /** A language row's tap: an enabled language is switched off and any other on. */
    fun toggle(language: String) {
        toggleLanguage.await(language)
    }

    sealed interface State {

        @Immutable
        data object Loading : State

        @Immutable
        data class Error(val throwable: Throwable) : State

        /** [languages] in the interactor's order; [enabledLanguages] is `source_languages`. */
        @Immutable
        data class Success(
            val languages: List<String>,
            val enabledLanguages: Set<String>,
        ) : State {

            val isEmpty: Boolean
                get() = languages.isEmpty()
        }
    }
}
