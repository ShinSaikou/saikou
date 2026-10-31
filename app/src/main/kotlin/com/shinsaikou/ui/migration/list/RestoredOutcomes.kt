package com.shinsaikou.ui.migration.list

import androidx.compose.runtime.saveable.Saver

/**
 * What the migration list keeps across a process death (phase 4 unit MB, departure M1): each
 * resolved entry's outcome, the entries that left the list, and the run's flags.
 *
 * [outcomeIds] and [outcomeTargets] are parallel: an entry id and its match's id, or 0 for "No
 * alternatives found". [removedIds] are the entries removed from the list (the close icon, Don't
 * migrate, a hide switch, a write, or an entry no longer in the library), which a restored list
 * leaves out. [flags] is the run's `migration_flags` bitmask, used in place of the key after a
 * restore, so a change to the key made elsewhere does not reach a run already started.
 *
 * The screen saves it in `rememberSaveable` through [Saver] and hands it to the new view model, as
 * the history screen hands back its query. About 12 KB at 500 entries.
 */
class RestoredOutcomes(
    val outcomeIds: LongArray,
    val outcomeTargets: LongArray,
    val removedIds: LongArray,
    val flags: Int,
) {

    /** Entry id to match id, 0 for no match. */
    fun outcomes(): Map<Long, Long> = outcomeIds.indices.associate { outcomeIds[it] to outcomeTargets[it] }

    companion object {

        fun of(outcomes: Map<Long, Long>, removedIds: Set<Long>, flags: Int) = RestoredOutcomes(
            outcomeIds = outcomes.keys.toLongArray(),
            outcomeTargets = outcomes.values.toLongArray(),
            removedIds = removedIds.toLongArray(),
            flags = flags,
        )

        /** Nothing is saved for a list that has not built yet; a restore of nothing is a new list. */
        val Saver: Saver<RestoredOutcomes?, Any> = Saver(
            save = { value ->
                value?.let { arrayListOf(it.outcomeIds, it.outcomeTargets, it.removedIds, it.flags) }
            },
            restore = { saved ->
                val parts = saved as List<*>
                RestoredOutcomes(
                    outcomeIds = parts[0] as LongArray,
                    outcomeTargets = parts[1] as LongArray,
                    removedIds = parts[2] as LongArray,
                    flags = parts[3] as Int,
                )
            },
        )
    }
}
