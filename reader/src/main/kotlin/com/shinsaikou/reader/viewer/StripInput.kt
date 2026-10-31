package com.shinsaikou.reader.viewer

import android.view.KeyEvent
import kotlin.math.roundToInt

/** What a key asks of the long strip. */
enum class StripKey {
    /** A tap's step. */
    STEP_BACK,
    STEP_FORWARD,

    /** A whole viewport, as Page Up, Page Down and Space move a web page. */
    PAGE_BACK,
    PAGE_FORWARD,
    MENU,

    /** Consumed with nothing to do: the other half of a key that acts on one of its two events. */
    NOTHING,
}

/**
 * The long strip's key map, or null for a key it leaves to the activity. Scrolling keys act on key
 * down, so holding one repeats it (each repeat is another key down); Menu waits for its key up. The
 * volume keys count only while enabled and the menu is closed, and Space only while the menu is
 * closed, where it may be pressing a focused button.
 */
fun stripKey(
    keyCode: Int,
    action: Int,
    shift: Boolean,
    volumeKeys: Boolean,
    volumeKeysInverted: Boolean,
    menuVisible: Boolean,
): StripKey? {
    val down = action == KeyEvent.ACTION_DOWN
    fun onDown(key: StripKey) = if (down) key else StripKey.NOTHING
    return when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
            if (!volumeKeys || menuVisible) return null
            val forward = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != volumeKeysInverted
            onDown(if (forward) StripKey.STEP_FORWARD else StripKey.STEP_BACK)
        }
        KeyEvent.KEYCODE_MENU -> if (down) StripKey.NOTHING else StripKey.MENU
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> onDown(StripKey.STEP_BACK)
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> onDown(StripKey.STEP_FORWARD)
        KeyEvent.KEYCODE_PAGE_UP -> onDown(StripKey.PAGE_BACK)
        KeyEvent.KEYCODE_PAGE_DOWN -> onDown(StripKey.PAGE_FORWARD)
        KeyEvent.KEYCODE_SPACE -> {
            if (menuVisible) return null
            onDown(if (shift) StripKey.PAGE_BACK else StripKey.PAGE_FORWARD)
        }
        else -> null
    }
}

/**
 * How far one wheel or touchpad event scrolls the strip, down positive: its vertical delta in
 * [pixelsPerUnit] (the platform's scroll factor), so a touchpad's many small events scroll a little
 * each rather than a step each.
 */
fun wheelScroll(axisValue: Float, pixelsPerUnit: Float): Int = -(axisValue * pixelsPerUnit).roundToInt()
