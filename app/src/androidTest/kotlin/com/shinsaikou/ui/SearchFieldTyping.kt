package com.shinsaikou.ui

import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals

/**
 * Typing into a search field one key at a time, with a frame between keys, which is how the owner's
 * phone scrambled the Extensions search on 2026-10-02 (`adb shell input text`, "Asura" typed a key
 * every 0.35 s came out as "suraA"). A field whose text comes back from a view model through a flow
 * on another thread recomposes in that gap with the text from before the key, and the String
 * `OutlinedTextField` then moves its cursor and resets its edit buffer to that older text, so the
 * next key lands in the wrong place or is lost. A field bound to the screen's own state has no gap.
 *
 * Each key is its own `performTextInput` or `performKeyInput`, and the rule waits for the frame after
 * each one: that is the frame the bug needs. The text is fixture words, never a source's name.
 */
internal const val TYPED_QUERY = "fixture search"

/** How many keys [typeThenDelete] takes back off the end, leaving "fixture". */
internal const val DELETED_KEYS = 7

/**
 * The view model's copy of the query comes back on its own thread, outside the rule's idling
 * checks, so a late copy is given this long to land, and to do its damage, before the text is read.
 */
private const val LATE_ECHO_GRACE_MILLIS = 750L

/** The one editable field on the screen under test. */
internal fun ComposeTestRule.searchField() = onNode(hasSetTextAction())

/**
 * Focuses the field, types [TYPED_QUERY] one key at a time, checks the text and the cursor, then
 * deletes [DELETED_KEYS] keys one at a time and checks again. [where] names the screen in the
 * failure message. [onSettled] is given the text after each check, for a screen whose results the
 * test can also check.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeTestRule.typeThenDelete(where: String, onSettled: (String) -> Unit = {}) {
    searchField().performClick()
    TYPED_QUERY.forEach { key -> searchField().performTextInput(key.toString()) }
    assertFieldSettlesOn(where, TYPED_QUERY)
    onSettled(TYPED_QUERY)

    repeat(DELETED_KEYS) { searchField().performKeyInput { pressKey(Key.Backspace) } }
    assertFieldSettlesOn(where, TYPED_QUERY.dropLast(DELETED_KEYS))
    onSettled(TYPED_QUERY.dropLast(DELETED_KEYS))
}

/**
 * The text, and the cursor after the last key when [afterTyping], once a late copy from the view
 * model has had time to arrive. A field composed anew with text in it (a page shown again) starts
 * with its cursor at 0 whatever holds the text, which is the String text field's own behaviour and
 * was the same before the fix, so a check of such a field passes `afterTyping = false`.
 */
internal fun ComposeTestRule.assertFieldSettlesOn(where: String, expected: String, afterTyping: Boolean = true) {
    SystemClock.sleep(LATE_ECHO_GRACE_MILLIS)
    waitForIdle()
    val config = searchField().fetchSemanticsNode().config
    assertEquals("$where: the field's text", expected, config[SemanticsProperties.EditableText].text)
    if (afterTyping) {
        assertEquals(
            "$where: the cursor after the last key",
            TextRange(expected.length),
            config[SemanticsProperties.TextSelectionRange],
        )
    }
}
