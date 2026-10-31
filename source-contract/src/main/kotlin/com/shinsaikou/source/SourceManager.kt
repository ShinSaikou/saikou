package com.shinsaikou.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single registry of live sources. One implementation exists in the app graph
 * (DefaultSourceManager in :extensions, see 03-extensions.md 12). It merges every
 * [SourceProvider] (the extension host, the Local source) and answers unknown ids with a
 * [StubSource] so library rows whose extension is gone still render.
 */
interface SourceManager {
    /**
     * True once every provider has finished its first load ([SourceProvider.isInitialized]), and
     * never false again. Once it is true, [get] and [getOrStub] answer from the loaded lists; before
     * it, an installed source can still read as missing. UI shows a spinner until then, and
     * anything that resolves sources without a person waiting (a worker the system started) waits
     * for it first.
     */
    val isInitialized: StateFlow<Boolean>

    /** The live source with [id], or null if none is loaded. Never returns a stub. */
    fun get(id: Long): Source?

    /**
     * The live source with [id], or a [StubSource] carrying the last known name and language
     * (looked up through [StubSourceNames]) when no provider has it. Never null.
     */
    fun getOrStub(id: Long): Source

    /** Every live [CatalogueSource], re-emitted whenever a provider's list changes. */
    fun getCatalogueSources(): Flow<List<CatalogueSource>>

    /** Every live source of any kind. */
    fun getSources(): Flow<List<Source>>

    /** All currently known stubs (ids requested through [getOrStub] that no provider serves). */
    fun getStubSources(): Flow<List<StubSource>>
}
