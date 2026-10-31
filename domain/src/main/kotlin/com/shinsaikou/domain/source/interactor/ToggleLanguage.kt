package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import dev.zacsweers.metro.Inject
import tachiyomi.core.common.preference.getAndSet

/**
 * Upstream's `ToggleLanguage` (`app/.../domain/source/interactor/ToggleLanguage.kt`): an enabled
 * language code comes out of [SourcePreferences.enabledLanguages] (`source_languages`) and any other
 * goes in, the same set the Settings language dialog, the Sources page, Global Search and the
 * extension list read. The code is stored as the source reports it, `"all"` included (ruling R57).
 */
@Inject
class ToggleLanguage(
    private val preferences: SourcePreferences,
) {

    fun await(language: String) {
        preferences.enabledLanguages.getAndSet { enabled ->
            if (language in enabled) enabled - language else enabled + language
        }
    }
}
