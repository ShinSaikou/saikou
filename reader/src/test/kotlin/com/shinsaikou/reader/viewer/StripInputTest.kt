package com.shinsaikou.reader.viewer

import android.view.KeyEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The long strip's keyboard and wheel, as a web page scrolls. */
class StripInputTest {

    @Test
    fun `scrolling keys act on key down, so holding one repeats, and their key up does nothing`() {
        key(KeyEvent.KEYCODE_DPAD_DOWN) shouldBe StripKey.STEP_FORWARD
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP) shouldBe StripKey.NOTHING
        key(KeyEvent.KEYCODE_DPAD_RIGHT) shouldBe StripKey.STEP_FORWARD
        key(KeyEvent.KEYCODE_DPAD_UP) shouldBe StripKey.STEP_BACK
        key(KeyEvent.KEYCODE_DPAD_LEFT) shouldBe StripKey.STEP_BACK
    }

    @Test
    fun `page down, page up and space move a whole viewport`() {
        key(KeyEvent.KEYCODE_PAGE_DOWN) shouldBe StripKey.PAGE_FORWARD
        key(KeyEvent.KEYCODE_PAGE_UP) shouldBe StripKey.PAGE_BACK
        key(KeyEvent.KEYCODE_SPACE) shouldBe StripKey.PAGE_FORWARD
        key(KeyEvent.KEYCODE_SPACE, shift = true) shouldBe StripKey.PAGE_BACK
        key(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_UP) shouldBe StripKey.NOTHING
    }

    @Test
    fun `space is left to the menu's buttons while the menu is open`() {
        key(KeyEvent.KEYCODE_SPACE, menuVisible = true) shouldBe null
        key(KeyEvent.KEYCODE_PAGE_DOWN, menuVisible = true) shouldBe StripKey.PAGE_FORWARD
    }

    @Test
    fun `volume keys step only while enabled and the menu is closed, and follow the inversion`() {
        key(KeyEvent.KEYCODE_VOLUME_DOWN) shouldBe null
        key(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeys = true) shouldBe StripKey.STEP_FORWARD
        key(KeyEvent.KEYCODE_VOLUME_UP, volumeKeys = true) shouldBe StripKey.STEP_BACK
        key(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeys = true, inverted = true) shouldBe StripKey.STEP_BACK
        key(KeyEvent.KEYCODE_VOLUME_DOWN, volumeKeys = true, menuVisible = true) shouldBe null
        key(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, volumeKeys = true) shouldBe StripKey.NOTHING
    }

    @Test
    fun `menu toggles on key up only, and other keys are left alone`() {
        key(KeyEvent.KEYCODE_MENU) shouldBe StripKey.NOTHING
        key(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP) shouldBe StripKey.MENU
        key(KeyEvent.KEYCODE_N) shouldBe null
    }

    @Test
    fun `the wheel scrolls in proportion to the event's delta`() {
        // One notch down scrolls the platform's scroll factor; a touchpad's fraction, that fraction.
        wheelScroll(-1f, 64f) shouldBe 64
        wheelScroll(1f, 64f) shouldBe -64
        wheelScroll(-0.25f, 64f) shouldBe 16
        wheelScroll(-3f, 64f) shouldBe 192
        // A sideways swipe has no vertical delta and used to scroll a step up.
        wheelScroll(0f, 64f) shouldBe 0
    }

    private fun key(
        keyCode: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        shift: Boolean = false,
        volumeKeys: Boolean = false,
        inverted: Boolean = false,
        menuVisible: Boolean = false,
    ): StripKey? = stripKey(keyCode, action, shift, volumeKeys, inverted, menuVisible)
}
