package com.shinsaikou.source

import kotlinx.coroutines.flow.StateFlow

/**
 * Something that contributes sources to the [SourceManager]. The extension host contributes one
 * provider whose list changes as extensions are installed, trusted and removed; the Local source
 * contributes a constant single-element list. Providers are collected into the manager through a
 * Metro multibinding (Set<SourceProvider>).
 */
interface SourceProvider {
    /** Current sources. Emits a new list, never mutates one. Must be safe to collect from any thread. */
    val sources: StateFlow<List<Source>>

    /**
     * True once the provider has finished its first load, whether or not anything loaded, and
     * never false again. A provider publishes that first list to [sources] before it sets this, so
     * whoever reads this flag and then [sources] sees the loaded list, never the empty one a
     * provider starts with. Until then, an empty [sources] means the provider has not loaded yet.
     */
    val isInitialized: StateFlow<Boolean>
}
