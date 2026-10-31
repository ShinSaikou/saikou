package com.shinsaikou.tracking

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds

/**
 * Declares the tracker multibinding so [TrackerManager] resolves with no tracker implementations
 * in the graph. Phase 3 adds bindings; nothing else changes.
 */
@BindingContainer
@ContributesTo(AppScope::class)
interface TrackingBindings {

    @Multibinds(allowEmpty = true)
    val trackers: Set<Tracker>
}
