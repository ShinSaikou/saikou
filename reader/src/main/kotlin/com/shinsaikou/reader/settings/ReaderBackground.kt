package com.shinsaikou.reader.settings

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * What the `readerTheme` preference integer means. Contract 07 part 2 section 6.5.
 *
 * Upstream keeps the key as a bare `Int` and inlines a four-way `when` in three places. One enum, one
 * decoder, three readers. The stored integers are upstream's and a backup restores them raw, so they
 * are the enum's payload rather than its ordinal: never renumber them (decision D26).
 */
enum class ReaderBackground(val storedValue: Int) {
    WHITE(0),
    BLACK(1),
    GRAY(2),
    AUTOMATIC(3),
    ;

    val isAutomatic: Boolean get() = this == AUTOMATIC

    @ColorInt
    fun color(isNightMode: Boolean): Int = when (this) {
        WHITE -> Color.WHITE
        BLACK -> Color.BLACK
        GRAY -> GRAY_COLOR
        AUTOMATIC -> if (isNightMode) GRAY_COLOR else Color.WHITE
    }

    /** True when the page's own letterbox should be painted dark. */
    fun isDark(isNightMode: Boolean): Boolean =
        this == BLACK || this == GRAY || (this == AUTOMATIC && isNightMode)

    companion object {
        /** Upstream's `grayBackgroundColor`. */
        const val GRAY_COLOR: Int = 0xFF202125.toInt()

        /**
         * Falls back to [BLACK], which is both upstream's `else` branch and the preference default, so
         * a corrupt restored value cannot produce a white reader.
         */
        fun fromPreference(value: Int): ReaderBackground =
            entries.firstOrNull { it.storedValue == value } ?: BLACK
    }
}
