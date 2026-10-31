package com.shinsaikou.domain.preferences

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppThemeTest {

    /** Upstream v0.20.4's `AppTheme` entries that are not deprecated, in its order. */
    private val upstreamNames = listOf(
        "DEFAULT",
        "MONET",
        "CATPPUCCIN",
        "GREEN_APPLE",
        "LAVENDER",
        "MIDNIGHT_DUSK",
        "NORD",
        "STRAWBERRY_DAIQUIRI",
        "TAKO",
        "TEALTURQUOISE",
        "TIDAL_WAVE",
        "YINYANG",
        "YOTSUBA",
        "TOKYONIGHT",
        "MONOCHROME",
    )

    @Test
    fun `fromName round trips every shipped theme`() {
        AppTheme.fromName("DEFAULT") shouldBe AppTheme.DEFAULT
        AppTheme.fromName("MONET") shouldBe AppTheme.MONET
        AppTheme.fromName("MIDNIGHT_DUSK") shouldBe AppTheme.MIDNIGHT_DUSK
    }

    @Test
    fun `the entries are upstream's names in upstream's order`() {
        AppTheme.entries.map { it.name } shouldBe upstreamNames
    }

    @Test
    fun `every upstream name parses as itself`() {
        upstreamNames.forEach { AppTheme.fromName(it).name shouldBe it }
    }

    @Test
    fun `Upstream's deprecated names and unknown names fall back to the default theme`() {
        AppTheme.fromName("DARK_BLUE") shouldBe AppTheme.DEFAULT
        AppTheme.fromName("HOT_PINK") shouldBe AppTheme.DEFAULT
        AppTheme.fromName("BLUE") shouldBe AppTheme.DEFAULT
        AppTheme.fromName("") shouldBe AppTheme.DEFAULT
        AppTheme.fromName(null) shouldBe AppTheme.DEFAULT
    }
}
