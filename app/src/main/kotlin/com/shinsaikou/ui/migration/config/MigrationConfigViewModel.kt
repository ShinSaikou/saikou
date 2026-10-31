// Vendored from upstream v0.20.4: app/src/main/java/tachiyomi/feature/migration/config/MigrationConfigScreen.kt (modified: yes)
package com.shinsaikou.ui.migration.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.browse.isInEnabledLanguage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The `Model` of upstream's `MigrationConfigScreen` (phase 4 unit MB): the target sources of a batch
 * migration and their order, saved to `migration_sources` after every change.
 *
 * Saikou: Metro and the graph's dispatchers for Injekt and `launchIO`; the registry is awaited first
 * (R69 item 4), so no installed source is missing from the first frame after a cold start; the
 * offered sources are the live catalogue sources other than Local in an enabled language, which is
 * upstream's `filterIsInstance<HttpSource>()` and its language filter over Saikou's registry (its live
 * list holds no stubs, and Local is its one non-HTTP source); and [moveSource] takes the two keys of
 * a drag, upstream's lookup among the selected rows moved here from the screen.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(MigrationConfigViewModel::class)
@Inject
class MigrationConfigViewModel(
    val sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<MigrationConfigViewModel.State>(State()) {

    private val sourcesComparator = { includedSources: List<Long> ->
        compareBy<MigrationSource>(
            { !it.isSelected },
            { includedSources.indexOf(it.id) },
            { with(it) { "$name ($shortLanguage)" } },
        )
    }

    init {
        viewModelScope.launch(dispatchers.io) {
            sourceManager.isInitialized.first { it }
            initSources()
            mutableState.update { it.copy(isLoading = false) }
        }
    }

    private fun updateSources(action: (List<MigrationSource>) -> List<MigrationSource>) {
        mutableState.update { state ->
            val updatedSources = action(state.sources)
            val includedSources = updatedSources.mapNotNull { if (!it.isSelected) null else it.id }
            state.copy(sources = updatedSources.sortedWith(sourcesComparator(includedSources)))
        }
        saveSources()
    }

    private suspend fun initSources() {
        val languages = sourcePreferences.enabledLanguages.get()
        val pinnedSources = sourcePreferences.pinnedSources.get().mapNotNull { it.toLongOrNull() }
        val includedSources = sourcePreferences.migrationSources.get()
        val disabledSources = sourcePreferences.disabledSources.get()
            .mapNotNull { it.toLongOrNull() }
        val sources = sourceManager.getCatalogueSources().first()
            .asSequence()
            .filter { it.id != SourceIds.LOCAL && it.isInEnabledLanguage(languages) }
            .map {
                MigrationSource(
                    id = it.id,
                    name = it.name,
                    lang = it.lang,
                    isSelected = when {
                        includedSources.isNotEmpty() -> it.id in includedSources
                        pinnedSources.isNotEmpty() -> it.id in pinnedSources
                        else -> it.id !in disabledSources
                    },
                )
            }
            .toList()

        mutableState.update { state ->
            state.copy(sources = sources.sortedWith(sourcesComparator(includedSources)))
        }
    }

    fun toggleSelection(id: Long) {
        updateSources { sources ->
            sources.map { source ->
                source.copy(isSelected = if (source.id == id) !source.isSelected else source.isSelected)
            }
        }
    }

    fun toggleSelection(config: SelectionConfig) {
        val pinnedSources = sourcePreferences.pinnedSources.get().mapNotNull { it.toLongOrNull() }
        val disabledSources = sourcePreferences.disabledSources.get().mapNotNull { it.toLongOrNull() }
        val isSelected: (Long) -> Boolean = {
            when (config) {
                SelectionConfig.All -> true
                SelectionConfig.None -> false
                SelectionConfig.Pinned -> it in pinnedSources
                SelectionConfig.Enabled -> it !in disabledSources
            }
        }
        updateSources { sources ->
            sources.map { source ->
                source.copy(isSelected = isSelected(source.id))
            }
        }
    }

    /**
     * A drag from the row keyed [fromKey] onto the row keyed [toKey]. The keys are looked up among
     * the selected rows only, which lead the list, so a move onto a header or an available row is
     * ignored (upstream's screen, `MigrationConfigScreen.kt:156-161`).
     */
    fun moveSource(fromKey: Any, toKey: Any) {
        val selectedSources = state.value.sources.filter { it.isSelected }
        val fromIndex = selectedSources.indexOfFirst { it.id == fromKey }
        val toIndex = selectedSources.indexOfFirst { it.id == toKey }
        if (fromIndex == -1 || toIndex == -1) return
        orderSource(fromIndex, toIndex)
    }

    private fun orderSource(from: Int, to: Int) {
        updateSources {
            it.toMutableList()
                .apply {
                    add(to, removeAt(from))
                }
                .toList()
        }
    }

    fun saveSources() {
        state.value.sources
            .filter { source -> source.isSelected }
            .map { source -> source.id }
            .let { sources -> sourcePreferences.migrationSources.set(sources) }
    }

    data class State(
        val isLoading: Boolean = true,
        val sources: List<MigrationSource> = emptyList(),
    )

    enum class SelectionConfig {
        All,
        None,
        Pinned,
        Enabled,
    }

    /** Saikou: the source's id, name and language in place of upstream's domain `Source`. */
    data class MigrationSource(
        val id: Long,
        val name: String,
        val lang: String,
        val isSelected: Boolean,
    ) {
        val shortLanguage: String = shortDisplayName(lang)
    }

    companion object {
        /** Upstream's `LocaleHelper.getShortDisplayName`. */
        fun shortDisplayName(lang: String, uppercase: Boolean = false): String =
            when (lang) {
                "es-419" -> "es-la"
                "zh-CN" -> "zh-hans"
                "zh-TW" -> "zh-hant"
                else -> lang
            }
                .let { if (uppercase) it.uppercase(Locale.ENGLISH) else it }
    }
}
