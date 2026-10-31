package com.shinsaikou.domain.manga.model

/**
 * Screen orientation lock, packed into bits 3 to 5 of `mangas.viewer`. The flag values are upstream's;
 * never renumber them.
 */
enum class ReaderOrientation(val flagValue: Int) {
    DEFAULT(0x00000000),
    FREE(0x00000008),
    PORTRAIT(0x00000010),
    LANDSCAPE(0x00000018),
    LOCKED_PORTRAIT(0x00000020),
    LOCKED_LANDSCAPE(0x00000028),
    REVERSE_PORTRAIT(0x00000030),
    ;

    companion object {
        const val MASK = 0x00000038

        fun fromFlags(flags: Long): ReaderOrientation =
            entries.firstOrNull { it.flagValue == (flags and MASK.toLong()).toInt() } ?: DEFAULT
    }
}
