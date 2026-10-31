package com.shinsaikou.ui.browse

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.languageOrder
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Locale

/**
 * Upstream's `GetExtensionLanguages` (`app/.../domain/extension/interactor/GetExtensionLanguages.kt`;
 * phase 5 unit BX): the languages of every available extension, for the extension filter screen.
 * It lives here rather than in `:domain` because `:domain` may not depend on `:extensions`, where
 * the [ExtensionManager] is (`checkModuleGraph`).
 *
 * The list follows the configured indexes' entries and `source_languages`, and is empty until an
 * index has been fetched, as the extension list is.
 */
@Inject
class GetExtensionLanguages(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {

    fun subscribe(): Flow<List<String>> = combine(
        preferences.enabledLanguages.withCurrent(),
        extensionManager.availableExtensions,
    ) { enabled, available -> extensionLanguages(available, enabled) }
}

/**
 * Every language of [available], once each, in [languageOrder]: the enabled ones first, `"all"` at
 * the head of each half, then by the name the screen shows. Upstream's order, through the comparator
 * the source filter sorts by, so the two screens list a language set the same way.
 */
internal fun extensionLanguages(
    available: List<Extension.Available>,
    enabledLanguages: Set<String>,
    locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
): List<String> = available
    .flatMap { it.languages() }
    .distinct()
    .sortedWith(languageOrder(enabledLanguages, locale))

/**
 * The language codes an index entry answers to: those of its sources, or its own code when it
 * lists none. The extension list shows an entry while any of these is enabled
 * (`ExtensionsViewModel`, ruling R57), and the extension filter offers each of them, so every
 * switch on the filter can bring an entry onto the list.
 */
internal fun Extension.Available.languages(): List<String> = sources.map { it.lang }.ifEmpty { listOf(lang) }
