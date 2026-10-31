package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.model.Source
import java.text.Collator
import java.util.Locale

/**
 * Upstream's `GetLanguagesWithSources` (`app/.../domain/source/interactor/GetLanguagesWithSources.kt`),
 * over Saikou's source registry: every live catalogue source grouped by its language code, for the
 * Browse source filter screen. The Local source is left out, as upstream leaves it out by listing only
 * its `HttpSource`s; it has no language to switch and cannot be disabled.
 *
 * The order is [languagesWithSources]'s. Each source is the domain [Source] rather than the live
 * [CatalogueSource], so a screen's state never holds an extension's class loader.
 */
@Inject
class GetLanguagesWithSources(
    private val sourceManager: SourceManager,
    private val preferences: SourcePreferences,
) {

    fun subscribe(): Flow<Map<String, List<Source>>> = combine(
        preferences.enabledLanguages.withCurrent(),
        preferences.disabledSources.withCurrent(),
        sourceManager.getCatalogueSources(),
    ) { enabled, disabled, sources ->
        languagesWithSources(sources.map { it.toDomainSource() }, enabled, disabled)
    }

    private fun CatalogueSource.toDomainSource() = Source(
        id = id,
        lang = lang,
        name = name,
        supportsLatest = supportsLatest,
        isStub = false,
    )

    /**
     * The Android store replays its current value from `changes()` and the in-memory store the
     * tests use does not; prepending the current value gives both the first emission the combine
     * waits for.
     */
    private fun <T> Preference<T>.withCurrent(): Flow<T> = changes().onStart { emit(get()) }
}

/**
 * Upstream's order: the enabled languages first, then the rest, each half in [languageOrder]; and
 * inside a language the enabled sources first, then the disabled, each half by name ignoring case.
 * Each code keeps its own group (upstream's `SortedMap` would merge two codes its comparator calls
 * equal). The Local source is never listed.
 */
fun languagesWithSources(
    sources: List<Source>,
    enabledLanguages: Set<String>,
    disabledSources: Set<String>,
    locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
): Map<String, List<Source>> {
    val sourceOrder = compareBy<Source> { it.id.toString() in disabledSources }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    return sources
        .filterNot { it.id == SourceIds.LOCAL }
        .sortedWith(sourceOrder)
        .groupBy { it.lang }
        .toSortedMap(languageOrder(enabledLanguages, locale))
        .let { LinkedHashMap(it) }
}

/**
 * The one order of language codes on a screen that switches them: the source filter
 * ([languagesWithSources]) and the extension filter (`GetExtensionLanguages`, phase 5 unit BX)
 * both sort by it. The enabled languages first, then the rest, each half by display name with
 * `"all"` (multi-language sources) at its head, as upstream's `LocaleHelper.comparator` keeps it.
 *
 * The display name is the one the screen shows, [languageDisplayName] in [locale], the function
 * the row's words come from too (`enabledLanguageLabel`), where upstream sorts by each language's
 * name in that language; sorting by the words on screen keeps the list reading in order. The names
 * are compared with [locale]'s collator at primary strength, as `compareToWithCollator` compares
 * titles, so an accented name sorts with its letter rather than after "z". Codes whose names tie
 * fall back to the code, so the order is total.
 */
fun languageOrder(
    enabledLanguages: Set<String>,
    locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
): Comparator<String> {
    val names = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return compareBy<String> { it !in enabledLanguages }
        .thenBy { it != LANGUAGE_ALL }
        .thenBy(names) { languageDisplayName(it, locale) }
        .thenBy { it }
}

/**
 * A language code's name as Saikou shows it: the code's display name in [locale], or the code itself
 * when the locale has no name for it. The source filter's header and Settings' language dialog word
 * a code with this (`enabledLanguageLabel`), and [languagesWithSources] sorts by it, so the order
 * is always the order of the words on screen.
 */
fun languageDisplayName(code: String, locale: Locale = Locale.getDefault(Locale.Category.DISPLAY)): String =
    Locale.forLanguageTag(code).getDisplayName(locale).ifBlank { code }

private const val LANGUAGE_ALL = "all"
