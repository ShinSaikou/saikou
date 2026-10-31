package com.shinsaikou.reader.settings

import android.graphics.Color
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The stored integers are upstream's and a backup restores them raw, so this pins the mapping rather
 * than the ordinal. `android.graphics.Color`'s constants are compile-time ints, so no Android class
 * is loaded.
 */
class ReaderBackgroundTest {

    @Test
    fun `fromPreference maps upstream's four stored values`() {
        ReaderBackground.fromPreference(0) shouldBe ReaderBackground.WHITE
        ReaderBackground.fromPreference(1) shouldBe ReaderBackground.BLACK
        ReaderBackground.fromPreference(2) shouldBe ReaderBackground.GRAY
        ReaderBackground.fromPreference(3) shouldBe ReaderBackground.AUTOMATIC
    }

    @Test
    fun `a restored value outside the set falls back to black, never to white`() {
        ReaderBackground.fromPreference(-1) shouldBe ReaderBackground.BLACK
        ReaderBackground.fromPreference(4) shouldBe ReaderBackground.BLACK
        ReaderBackground.fromPreference(Int.MAX_VALUE) shouldBe ReaderBackground.BLACK
    }

    @Test
    fun `the fixed backgrounds ignore night mode`() {
        ReaderBackground.WHITE.color(isNightMode = true) shouldBe Color.WHITE
        ReaderBackground.WHITE.color(isNightMode = false) shouldBe Color.WHITE
        ReaderBackground.BLACK.color(isNightMode = true) shouldBe Color.BLACK
        ReaderBackground.GRAY.color(isNightMode = false) shouldBe ReaderBackground.GRAY_COLOR
    }

    @Test
    fun `automatic follows night mode`() {
        ReaderBackground.AUTOMATIC.color(isNightMode = true) shouldBe ReaderBackground.GRAY_COLOR
        ReaderBackground.AUTOMATIC.color(isNightMode = false) shouldBe Color.WHITE
    }

    @Test
    fun `isDark is true for black and gray always, and for automatic only at night`() {
        ReaderBackground.BLACK.isDark(isNightMode = false) shouldBe true
        ReaderBackground.GRAY.isDark(isNightMode = false) shouldBe true
        ReaderBackground.WHITE.isDark(isNightMode = true) shouldBe false
        ReaderBackground.AUTOMATIC.isDark(isNightMode = true) shouldBe true
        ReaderBackground.AUTOMATIC.isDark(isNightMode = false) shouldBe false
    }
}
