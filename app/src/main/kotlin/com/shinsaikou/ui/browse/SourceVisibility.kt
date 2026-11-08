package com.shinsaikou.ui.browse

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds

/**
 * The language rule the Sources page and Global Search share (contract 08 section 5.3, ruling
 * R57). `"all"` is a literal code, the one multi-language sources report, matched exactly as upstream
 * matches it rather than read as a wildcard. The Local source is exempt, as it is in upstream's
 * source list: its code is `"other"`, which no extension offers and the language picker would
 * never list, so the rule would otherwise hide the owner's own files.
 */
internal fun CatalogueSource.isInEnabledLanguage(enabled: Set<String>): Boolean =
    id == SourceIds.LOCAL || lang in enabled

/**
 * The ids of every source an adult extension provides. The flag is not on the source contract, so
 * it is joined in from the extension the source was loaded from.
 */
internal fun matureSourceIds(installed: List<Extension.Installed>): Set<Long> = installed
    .filter { it.isNsfw }
    .flatMapTo(mutableSetOf()) { extension -> extension.sources.map { it.id } }

/**
 * The sources the owner has left on (contract 08 section 5.3): an enabled language
 * ([isInEnabledLanguage]), an id that is not hidden, and no source of an adult extension
 * ([matureSourceIds]) while that switch is off. The Sources page lists them, Global Search asks
 * them, and a search another app sends picks its one source among them, all through this one
 * function so the three cannot drift apart. Read when called, never followed.
 */
internal fun <T : CatalogueSource> List<T>.enabledSources(
    preferences: SourcePreferences,
    installed: List<Extension.Installed>,
): List<T> {
    val languages = preferences.enabledLanguages.get()
    val hidden = preferences.disabledSources.get()
    val mature = if (preferences.showNsfwSource.get()) emptySet() else matureSourceIds(installed)
    return filter { it.isInEnabledLanguage(languages) }
        .filterNot { it.id.toString() in hidden }
        .filterNot { it.id in mature }
}

/**
 * Puts the Local source after every extension source and leaves the rest in the order they came in:
 * sorted by name it otherwise lands among the extensions' sources, under L. The Sources page applies
 * it inside the pinned group and inside the rest, and Global Search after the pinned sources.
 */
internal fun <T : CatalogueSource> localSourceLast(): Comparator<T> = compareBy { it.id == SourceIds.LOCAL }
