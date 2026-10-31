package com.shinsaikou.presentation.core.util

import android.content.res.Configuration
import com.shinsaikou.domain.preferences.TabletUiMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two pane layouts branch on this one boolean, so AUTOMATIC's two breakpoints and the three
 * manual overrides are pinned here.
 */
class WindowSizeTest {

    /**
     * Upstream's AUTOMATIC, which this rule reproduces (ruling R52). A tablet reporting 719 dp in
     * landscape splits; under the old 720 dp rule in both orientations it did not, and the only
     * chips left that split it upright were ALWAYS, which a backup then carried to the phone.
     */
    @Test
    fun `automatic splits a mid-size tablet in landscape`() {
        TabletUiMode.AUTOMATIC.isTabletUi(719, Configuration.ORIENTATION_LANDSCAPE) shouldBe true
        TabletUiMode.AUTOMATIC.isTabletUi(720, Configuration.ORIENTATION_PORTRAIT) shouldBe true
        TabletUiMode.AUTOMATIC.isTabletUi(1280, Configuration.ORIENTATION_PORTRAIT) shouldBe true
    }

    @Test
    fun `automatic turns on at 600 dp in landscape`() {
        TabletUiMode.AUTOMATIC.isTabletUi(599, Configuration.ORIENTATION_LANDSCAPE) shouldBe false
        TabletUiMode.AUTOMATIC.isTabletUi(600, Configuration.ORIENTATION_LANDSCAPE) shouldBe true
    }

    @Test
    fun `automatic turns on at 700 dp upright`() {
        TabletUiMode.AUTOMATIC.isTabletUi(699, Configuration.ORIENTATION_PORTRAIT) shouldBe false
        TabletUiMode.AUTOMATIC.isTabletUi(700, Configuration.ORIENTATION_PORTRAIT) shouldBe true
        // The width that splits in landscape does not split upright.
        TabletUiMode.AUTOMATIC.isTabletUi(600, Configuration.ORIENTATION_PORTRAIT) shouldBe false
    }

    /** Upstream's `else` branch: anything that is not portrait takes the landscape threshold. */
    @Test
    fun `automatic treats an undefined orientation as landscape`() {
        TabletUiMode.AUTOMATIC.isTabletUi(600, Configuration.ORIENTATION_UNDEFINED) shouldBe true
        TabletUiMode.AUTOMATIC.isTabletUi(599, Configuration.ORIENTATION_UNDEFINED) shouldBe false
    }

    /** Every phone AVD the gate uses reports sw411, so AUTOMATIC never splits one in either orientation. */
    @Test
    fun `automatic does not split a phone`() {
        TabletUiMode.AUTOMATIC.isTabletUi(411, Configuration.ORIENTATION_PORTRAIT) shouldBe false
        TabletUiMode.AUTOMATIC.isTabletUi(411, Configuration.ORIENTATION_LANDSCAPE) shouldBe false
    }

    @Test
    fun `always and never ignore the window`() {
        TabletUiMode.ALWAYS.isTabletUi(320, Configuration.ORIENTATION_PORTRAIT) shouldBe true
        TabletUiMode.NEVER.isTabletUi(1280, Configuration.ORIENTATION_LANDSCAPE) shouldBe false
    }

    @Test
    fun `landscape follows the orientation and not the width`() {
        TabletUiMode.LANDSCAPE.isTabletUi(320, Configuration.ORIENTATION_LANDSCAPE) shouldBe true
        TabletUiMode.LANDSCAPE.isTabletUi(1280, Configuration.ORIENTATION_PORTRAIT) shouldBe false
    }

    /**
     * The two manual overrides at the one window the gate's phone AVD really reports, which is
     * where the "LANDSCAPE has no width check" finding was settled. Measured on
     * `saikou_pixel8a_2g`: upright it is `sw411dp w411dp h914dp`, rotated it is
     * `sw411dp w914dp h411dp`. So the mode that skips the width check and gets a narrow window is
     * ALWAYS, not LANDSCAPE, and the smallest width both are handed is the same number either way.
     * Pinned here so the next reader of that finding starts from the measurement.
     */
    @Test
    fun `a phone in landscape is wide and a phone upright is not, at the same smallest width`() {
        TabletUiMode.LANDSCAPE.isTabletUi(411, Configuration.ORIENTATION_LANDSCAPE) shouldBe true
        TabletUiMode.LANDSCAPE.isTabletUi(411, Configuration.ORIENTATION_PORTRAIT) shouldBe false
        TabletUiMode.ALWAYS.isTabletUi(411, Configuration.ORIENTATION_PORTRAIT) shouldBe true
    }
}
