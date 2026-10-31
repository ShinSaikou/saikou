package com.shinsaikou.backup

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import tachiyomi.domain.migration.models.MigrationFlag

/**
 * Three preference keys whose meaning moved between upstream and Saikou, translated in both directions.
 *
 * Decision F10 fixes what a `.tachibk` is: "the `.tachibk` Saikou writes is a pure upstream file".
 * A pure upstream file uses upstream's key convention, so the translation is symmetric. Saikou renamed a
 * property, not a format. Reading applies [toSaikou], writing applies [toUpstream], and a file Saikou
 * wrote restores correctly into Saikou and into upstream alike.
 *
 * 1. Upstream crosses the two chapter swipe properties to their keys: its `swipeToStartAction` reads
 *    `pref_chapter_swipe_end_action` while the settings screen labels it "Chapter on swipe to left".
 *    Saikou does not cross them. Both physical gestures are the same gesture, so the two values are
 *    exchanged. The exchange is its own inverse, which is why one function serves both directions.
 *    Saikou's two defaults are exchanged as well (phase 4 unit CS): each default belongs to the
 *    gesture, not to the key, so a key absent from the file is left alone and the gesture's own
 *    default, the one upstream's user had, stands.
 * 2. `filterIntervalCustom` gained a `_v2` suffix in Saikou. Same value, and the rename is undone on
 *    the way out, so the file carries the spelling upstream reads.
 * 3. `migration_flags` gained Saikou's Downloaded chapters on bit `0b1000000` (unit
 *    migrate-downloads), on by default. Upstream never writes that bit, and neither did Saikou before
 *    the flag, so in the file the bit means the opposite: set means Downloaded chapters is off.
 *    A bitmask from upstream or from an older Saikou therefore restores with Downloaded chapters on,
 *    as on a fresh install, and a Saikou owner's own choice survives the round trip. Upstream's
 *    `MigrationFlag.fromBit` ignores a bit it does not know, so the file still restores into upstream
 *    with every upstream flag as it was. Flipping one bit is its own inverse, like case 1.
 *
 * See architecture ruling R19. An earlier draft of that ruling had the export side write Saikou's own
 * keys, which made the exchange asymmetric and meant a Saikou backup restored into Saikou came back
 * with the two swipe actions the wrong way round. F10 already answered it.
 */
internal object UpstreamPreferenceKeys {

    const val SWIPE_START_ACTION = "pref_chapter_swipe_start_action"
    const val SWIPE_END_ACTION = "pref_chapter_swipe_end_action"
    const val FILTER_INTERVAL_CUSTOM = "pref_filter_library_interval_custom"
    const val FILTER_INTERVAL_CUSTOM_V2 = "pref_filter_library_interval_custom_v2"
    const val MIGRATION_FLAGS = "migration_flags"

    /** Upstream spelling to Saikou spelling, applied to app preferences as they are read. */
    fun toSaikou(preferences: List<BackupPreference>): List<BackupPreference> =
        preferences.map { preference ->
            when (preference.key) {
                SWIPE_START_ACTION -> preference.copy(key = SWIPE_END_ACTION)
                SWIPE_END_ACTION -> preference.copy(key = SWIPE_START_ACTION)
                FILTER_INTERVAL_CUSTOM -> preference.copy(key = FILTER_INTERVAL_CUSTOM_V2)
                MIGRATION_FLAGS -> preference.flipDownloadsBit()
                else -> preference
            }
        }

    /** Saikou spelling to upstream spelling, applied to app preferences as they are written. */
    fun toUpstream(preferences: List<BackupPreference>): List<BackupPreference> =
        preferences.map { preference ->
            when (preference.key) {
                SWIPE_START_ACTION -> preference.copy(key = SWIPE_END_ACTION)
                SWIPE_END_ACTION -> preference.copy(key = SWIPE_START_ACTION)
                FILTER_INTERVAL_CUSTOM_V2 -> preference.copy(key = FILTER_INTERVAL_CUSTOM)
                MIGRATION_FLAGS -> preference.flipDownloadsBit()
                else -> preference
            }
        }

    /** Case 3. A value of another type is left for the restorer's type check to refuse. */
    private fun BackupPreference.flipDownloadsBit(): BackupPreference {
        val bits = value as? IntPreferenceValue ?: return this
        return copy(value = IntPreferenceValue(bits.value xor MigrationFlag.DOWNLOADS.flag))
    }
}
