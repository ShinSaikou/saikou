// Fresh: written for Saikou. Placed beside the vendored theme so it can read the internal scheme map.
package eu.kanade.presentation.theme

import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.presentation.core.theme.titleRes
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.SaikouColorScheme
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * Every [AppTheme] the picker offers resolves a light and a dark scheme and a label, through the
 * same mapping `getThemeColorScheme` uses. Dynamic needs a `Context`, so it is checked by what the
 * mapping hands its builder.
 */
class ThemeColorSchemesTest {

    private val dynamicStandIn = object : BaseColorScheme() {
        override val darkScheme = SaikouColorScheme.darkScheme
        override val lightScheme = SaikouColorScheme.lightScheme
    }

    private val schemes = AppTheme.entries.associateWith { baseColorScheme(it) { dynamicStandIn } }

    @Test
    fun `every theme resolves a light scheme and a dark scheme that differ`() {
        schemes.forEach { (theme, scheme) ->
            val light = scheme.getColorScheme(isDark = false, isAmoled = false, overrideDarkSurfaceContainers = true)
            val dark = scheme.getColorScheme(isDark = true, isAmoled = false, overrideDarkSurfaceContainers = true)
            light shouldBe scheme.lightScheme
            dark shouldBe scheme.darkScheme
            withClue(theme.name) { light.background shouldNotBe dark.background }
        }
    }

    @Test
    fun `each named theme has a scheme of its own`() {
        schemes.filterKeys { it != AppTheme.MONET }.values.toList().shouldBeUnique()
    }

    @Test
    fun `the default is the Saikou scheme and Dynamic is built by its own builder`() {
        schemes.getValue(AppTheme.DEFAULT) shouldBeSameInstanceAs SaikouColorScheme
        schemes.getValue(AppTheme.MONET) shouldBeSameInstanceAs dynamicStandIn
    }

    @Test
    fun `every theme has a label of its own`() {
        val labels = AppTheme.entries.map { it.titleRes }
        labels.forEach { it shouldNotBe 0 }
        labels.shouldBeUnique()
    }
}
