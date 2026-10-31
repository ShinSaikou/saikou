package com.shinsaikou.sources.local

import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Registers the Local source like any other provider, so `SourceManager.get(0)` answers with it
 * and no stub is ever built for id 0. The list never changes, so the provider is initialized from
 * the start.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
@Inject
class LocalSourceProvider(localSource: LocalSource) : SourceProvider {
    override val sources: StateFlow<List<Source>> = MutableStateFlow(listOf<Source>(localSource))

    override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
}
