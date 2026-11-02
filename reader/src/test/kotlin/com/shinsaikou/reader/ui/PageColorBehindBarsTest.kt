package com.shinsaikou.reader.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageColorBehindBarsTest {

    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `a white page stays light under a mild dim`() {
        pageColorBehindBars(white, brightness = -20, filter = null).luminance() shouldBeGreaterThan 0.5f
    }

    @Test
    fun `a white page under a strong dim reads dark`() {
        pageColorBehindBars(white, brightness = -80, filter = null).luminance() shouldBeLessThan 0.5f
    }

    @Test
    fun `a white page under a mostly black filter reads dark`() {
        pageColorBehindBars(white, brightness = 0, filter = 0xCC000000.toInt()).luminance() shouldBeLessThan 0.5f
    }

    @Test
    fun `a black page under a light filter reads light`() {
        val black = 0xFF000000.toInt()
        pageColorBehindBars(black, brightness = 0, filter = 0xE6FFFFFF.toInt()).luminance() shouldBeGreaterThan 0.5f
    }

    @Test
    fun `a white page dimmed to -30 keeps dark icons, and one dimmed to -70 takes light ones`() {
        prefersLightIcons(pageColorBehindBars(white, brightness = -30, filter = null)) shouldBe false
        prefersLightIcons(pageColorBehindBars(white, brightness = -70, filter = null)) shouldBe true
    }

    @Test
    fun `the app bars' surfaces split as before`() {
        prefersLightIcons(Color(0xFF1A1110)) shouldBe true
        prefersLightIcons(Color(0xFFFFF8F6)) shouldBe false
        prefersLightIcons(Color.Black) shouldBe true
    }

    @Test
    fun `a black filter in screen mode leaves a white page white`() {
        val page = pageColorBehindBars(white, brightness = 0, filter = 0xFF000000.toInt(), mode = BlendMode.Screen)

        page.red shouldBe (1f plusOrMinus 0.001f)
        prefersLightIcons(page) shouldBe false
    }

    @Test
    fun `a white filter in multiply mode darkens a white page by its alpha`() {
        val page = pageColorBehindBars(white, brightness = 0, filter = 0x4DFFFFFF, mode = BlendMode.Modulate)

        page.red shouldBe (0.3f plusOrMinus 0.01f)
    }

    @Test
    fun `darken and lighten keep the darker and the lighter of the two`() {
        val grey = 0xFF808080.toInt()
        pageColorBehindBars(grey, brightness = 0, filter = 0xFFFFFFFF.toInt(), mode = BlendMode.Darken).red shouldBe
            (Color(grey).red plusOrMinus 0.001f)
        pageColorBehindBars(grey, brightness = 0, filter = 0xFFFFFFFF.toInt(), mode = BlendMode.Lighten).red shouldBe
            (1f plusOrMinus 0.001f)
    }
}
