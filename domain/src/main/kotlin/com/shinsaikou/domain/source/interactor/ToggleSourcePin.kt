package com.shinsaikou.domain.source.interactor

import com.shinsaikou.domain.preferences.SourcePreferences
import dev.zacsweers.metro.Inject
import tachiyomi.core.common.preference.getAndSet

/**
 * Upstream's `ToggleSourcePin`: a pinned source comes out of [SourcePreferences.pinnedSources] and any
 * other goes in. The set holds ids as text, which is how an upstream backup restores it, so the source
 * list and global search both sort a pin made here the same way as one restored from upstream.
 */
@Inject
class ToggleSourcePin(
    private val preferences: SourcePreferences,
) {

    fun await(sourceId: Long) {
        val id = sourceId.toString()
        preferences.pinnedSources.getAndSet { pinned ->
            if (id in pinned) pinned - id else pinned + id
        }
    }
}
