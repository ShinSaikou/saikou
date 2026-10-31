package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import dev.zacsweers.metro.Inject
import tachiyomi.core.common.preference.getAndSet

/**
 * Upstream's `ToggleSource` (`domain/source/interactor/ToggleSource.kt`), the one-id form the source
 * list's long press calls: a disabled source is enabled and any other is disabled, in
 * [SourcePreferences.disabledSources] (`hidden_catalogues`), whose ids are text as an upstream backup
 * restores them. [enable] defaults to the flip, upstream's `isEnabled`, which answers whether the id is
 * in the disabled set.
 */
@Inject
class ToggleSource(
    private val preferences: SourcePreferences,
) {

    fun await(sourceId: Long, enable: Boolean = isDisabled(sourceId)) {
        val id = sourceId.toString()
        preferences.disabledSources.getAndSet { disabled ->
            if (enable) disabled - id else disabled + id
        }
    }

    private fun isDisabled(sourceId: Long): Boolean = sourceId.toString() in preferences.disabledSources.get()
}
