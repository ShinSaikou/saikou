package com.shinsaikou.migration

import com.shinsaikou.domain.preferences.SourcePreferences
import tachiyomi.domain.migration.models.MigrationFlag

/**
 * The one-time upgrade that turns Downloaded chapters on for an install that saved its migration
 * options before the flag existed (unit migrate-downloads).
 *
 * [SourcePreferences.migrationFlags] is upstream's Int bitmask, and only its default (nothing stored)
 * has every flag. A bitmask written by an earlier Saikou build lacks bit `0b1000000` and would read
 * Downloaded chapters off, leaving every migrated entry's downloads behind for exactly the owners
 * who once changed the options. So the first start of this build adds the bit to a stored value,
 * once: [SourcePreferences.migrationFlagsDownloadsAdded] records that it ran, and an owner who
 * unticks the option afterwards keeps it unticked. Nothing stored stays nothing stored, so the
 * default goes on deciding.
 *
 * Saikou has no counterpart of upstream's version-code `Migrator`; each start-up step that runs once
 * keeps its own marker (as the hardware bitmap threshold is seeded once in `SaikouApp`). A value a
 * backup restores later is not this step's concern: `UpstreamPreferenceKeys` translates the bit on
 * the way in and out.
 */
object MigrationFlagsUpgrade {

    /** Runs the upgrade unless it has run. Returns true when it changed the stored flags. */
    fun run(preferences: SourcePreferences): Boolean {
        val done = preferences.migrationFlagsDownloadsAdded
        if (done.get()) return false
        val flags = preferences.migrationFlags
        val changed = flags.isSet() && MigrationFlag.DOWNLOADS !in flags.get()
        if (changed) flags.set(flags.get() + MigrationFlag.DOWNLOADS)
        done.set(true)
        return changed
    }
}
