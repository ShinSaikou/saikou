package com.shinsaikou.domain.manga.model

/**
 * Reading direction, packed into bits 0 to 2 of `mangas.viewer`. The flag values are upstream's and
 * are load-bearing for backup import of per-manga reader settings; never renumber them.
 */
enum class ReadingMode(val flagValue: Int) {
    DEFAULT(0x00000000),
    LEFT_TO_RIGHT(0x00000001),
    RIGHT_TO_LEFT(0x00000002),
    VERTICAL(0x00000003),
    WEBTOON(0x00000004),
    CONTINUOUS_VERTICAL(0x00000005),
    ;

    val isWebtoon: Boolean get() = this == WEBTOON || this == CONTINUOUS_VERTICAL

    companion object {
        const val MASK = 0x00000007

        fun fromFlags(flags: Long): ReadingMode =
            entries.firstOrNull { it.flagValue == (flags and MASK.toLong()).toInt() } ?: DEFAULT
    }
}
