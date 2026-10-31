// Vendored from upstream v0.20.4: app/src/main/java/tachiyomi/domain/migration/models/MigrationFlag.kt (modified: yes)
// Modifications (unit migrate-downloads): DOWNLOADS, Saikou's "Downloaded chapters", on the next free
// bit after upstream's, declared after CUSTOM_COVER so the dialog and the sheet list it there.
package tachiyomi.domain.migration.models

enum class MigrationFlag(val flag: Int) {
    CHAPTER(0b00001),
    CATEGORY(0b00010),

    // 0b00100 was used for manga trackers
    CUSTOM_COVER(0b01000),

    // Saikou: the old entry's downloaded chapters follow it. A stored value written before this
    // flag existed lacks the bit, so MigrationFlagsUpgrade adds it once at start-up, and a backup's
    // value has the bit flipped in UpstreamPreferenceKeys (in the file, set means off).
    DOWNLOADS(0b1000000),
    NOTES(0b100000),
    REMOVE_DOWNLOAD(0b10000),
    ;

    companion object {
        fun fromBit(bit: Int): Set<MigrationFlag> {
            return buildSet {
                entries.forEach { entry ->
                    if (bit and entry.flag != 0) add(entry)
                }
            }
        }

        fun toBit(flags: Set<MigrationFlag>): Int {
            return flags.map { it.flag }
                .reduceOrNull { acc, mask -> acc or mask }
                ?: 0
        }
    }
}
