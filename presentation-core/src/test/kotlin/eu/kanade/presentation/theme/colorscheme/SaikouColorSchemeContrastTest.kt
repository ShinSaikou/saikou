// Fresh: written for Saikou. Placed beside BaseColorSchemeTest, which reads the same internal schemes.
package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Accessibility as a test (`design/plans/phase5-start.md` section 3.1.14, kept by the look rethink's
 * build plan, decision 1, for the Saikou scheme only): every text pair of the Saikou scheme reads at
 * 4.5:1 or more and every container pair at 3:1, in light, dark and pure black. Upstream's named
 * themes are taken as they are and are not tested here.
 * The ratio is WCAG 2's, computed here from the sRGB channels with no library, so the threshold is
 * the one thing a failing pair can be made to pass by: when a hex fails, the hex moves.
 */
class SaikouColorSchemeContrastTest {

    private val schemes = mapOf(
        "light" to SaikouColorScheme.lightScheme,
        "dark" to SaikouColorScheme.darkScheme,
        "pure black" to SaikouColorScheme.getColorScheme(isDark = true, isAmoled = true, overrideDarkSurfaceContainers = true),
    )

    private val textPairs: List<Pair<String, (ColorScheme) -> Pair<Color, Color>>> = listOf(
        "onPrimary on primary" to { it.onPrimary to it.primary },
        "onSecondary on secondary" to { it.onSecondary to it.secondary },
        "onTertiary on tertiary" to { it.onTertiary to it.tertiary },
        "onError on error" to { it.onError to it.error },
        "onBackground on background" to { it.onBackground to it.background },
        "onSurface on surface" to { it.onSurface to it.surface },
        "onSurface on surfaceContainer" to { it.onSurface to it.surfaceContainer },
        "onSurface on surfaceContainerHigh" to { it.onSurface to it.surfaceContainerHigh },
        "onSurface on surfaceContainerHighest" to { it.onSurface to it.surfaceContainerHighest },
        "onSurfaceVariant on surface" to { it.onSurfaceVariant to it.surface },
        // A Settings card's and a Stats tile's detail lines sit on the tile (phase5-chrome-3).
        "onSurfaceVariant on surfaceContainer" to { it.onSurfaceVariant to it.surfaceContainer },
        "onPrimaryContainer on primaryContainer" to { it.onPrimaryContainer to it.primaryContainer },
        "onSecondaryContainer on secondaryContainer" to { it.onSecondaryContainer to it.secondaryContainer },
        "onTertiaryContainer on tertiaryContainer" to { it.onTertiaryContainer to it.tertiaryContainer },
        "onErrorContainer on errorContainer" to { it.onErrorContainer to it.errorContainer },
        "inverseOnSurface on inverseSurface" to { it.inverseOnSurface to it.inverseSurface },
        "primary on surface (accent text on paper)" to { it.primary to it.surface },
    )

    private val containerPairs: List<Pair<String, (ColorScheme) -> Pair<Color, Color>>> = listOf(
        "outline on surface" to { it.outline to it.surface },
        "primary on surfaceContainer" to { it.primary to it.surfaceContainer },
    )

    @Test
    fun `every text pair reaches the text ratio in light, dark and pure black`() {
        failures(textPairs, minimum = 4.5).shouldBeEmpty()
    }

    @Test
    fun `every container pair reaches the container ratio in light, dark and pure black`() {
        failures(containerPairs, minimum = 3.0).shouldBeEmpty()
    }

    @Test
    fun `the accent reaches the text ratio on both papers`() {
        contrast(SaikouColorScheme.lightScheme.primary, SaikouColorScheme.lightScheme.background) shouldBeGreaterThanOrEqual 4.5
        contrast(SaikouColorScheme.darkScheme.primary, SaikouColorScheme.darkScheme.background) shouldBeGreaterThanOrEqual 4.5
    }

    @Test
    fun `the ratio is WCAG 2's`() {
        contrast(Color.Black, Color.White) shouldBeGreaterThanOrEqual 20.99
        // #767676 on white is the textbook pair that just passes 4.5:1 (4.54).
        contrast(Color(0xFF767676), Color.White) shouldBeGreaterThanOrEqual 4.5
        contrast(Color(0xFF777777), Color.White) shouldBeLessThan 4.5
    }

    /** Each failing pair as "scheme: pair = ratio", so a failure names every pair that fell short. */
    private fun failures(pairs: List<Pair<String, (ColorScheme) -> Pair<Color, Color>>>, minimum: Double): List<String> =
        schemes.flatMap { (schemeName, scheme) ->
            pairs.mapNotNull { (pairName, pick) ->
                val (foreground, background) = pick(scheme)
                val ratio = contrast(foreground, background)
                if (ratio >= minimum) null else "$schemeName: $pairName = ${"%.3f".format(ratio)}:1, under $minimum:1"
            }
        }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
