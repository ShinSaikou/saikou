// Fresh: written for Saikou. Placed in the vendored package so it can read internals.
package eu.kanade.presentation.theme.colorscheme

import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The pure black recipe. Upstream's navigation bar defects come from painting the bar the same black
 * as the page behind it, so the surface container roles stay just off black; this pins that, and
 * pins that pure black touches nothing but the background and surface roles.
 */
class BaseColorSchemeTest {

    private val scheme = SaikouColorScheme

    @Test
    fun `light mode ignores pure black`() {
        val light = scheme.getColorScheme(isDark = false, isAmoled = true, overrideDarkSurfaceContainers = true)
        light shouldBe scheme.lightScheme
    }

    @Test
    fun `dark mode without pure black is the plain dark scheme`() {
        val dark = scheme.getColorScheme(isDark = true, isAmoled = false, overrideDarkSurfaceContainers = true)
        dark shouldBe scheme.darkScheme
    }

    @Test
    fun `pure black forces background and surface only`() {
        val amoled = scheme.getColorScheme(isDark = true, isAmoled = true, overrideDarkSurfaceContainers = false)
        amoled.background shouldBe Color.Black
        amoled.onBackground shouldBe Color.White
        amoled.surface shouldBe Color.Black
        amoled.onSurface shouldBe Color.White
        amoled.primary shouldBe scheme.darkScheme.primary
        amoled.secondary shouldBe scheme.darkScheme.secondary
        amoled.tertiary shouldBe scheme.darkScheme.tertiary
        amoled.error shouldBe scheme.darkScheme.error
        // Dynamic colour keeps its own container roles.
        amoled.surfaceContainer shouldBe scheme.darkScheme.surfaceContainer
    }

    @Test
    fun `pure black keeps the navigation bar surfaces off black`() {
        val amoled = scheme.getColorScheme(isDark = true, isAmoled = true, overrideDarkSurfaceContainers = true)
        amoled.surfaceContainerLowest shouldBe Color(0xFF0C0C0C)
        amoled.surfaceContainerLow shouldBe Color(0xFF0C0C0C)
        amoled.surfaceContainer shouldBe Color(0xFF0C0C0C)
        amoled.surfaceVariant shouldBe Color(0xFF0C0C0C)
        amoled.surfaceContainerHigh shouldBe Color(0xFF131313)
        amoled.surfaceContainerHighest shouldBe Color(0xFF1B1B1B)
        amoled.surfaceContainer shouldNotBe Color.Black
    }
}
