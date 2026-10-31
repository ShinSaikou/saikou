package com.shinsaikou.tracking

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Every tracker in the graph, ordered by id so lists render in the same order everywhere. Phase 1
 * contributes none, so [trackers] is empty and [loggedInTrackersFlow] emits one empty list.
 */
@SingleIn(AppScope::class)
@Inject
class TrackerManager(trackers: Set<@JvmSuppressWildcards Tracker>) {

    val trackers: List<Tracker> = trackers.sortedBy { it.id }

    fun get(id: Long): Tracker? = this.trackers.find { it.id == id }

    fun getAll(ids: Set<Long>): List<Tracker> = trackers.filter { it.id in ids }

    fun loggedInTrackers(): List<Tracker> = trackers.filter { it.isLoggedIn }

    fun loggedInTrackersFlow(): Flow<List<Tracker>> {
        if (trackers.isEmpty()) return flowOf(emptyList())
        return combine(trackers.map { it.isLoggedInFlow }) { states ->
            trackers.filterIndexed { index, _ -> states[index] }
        }
    }
}
