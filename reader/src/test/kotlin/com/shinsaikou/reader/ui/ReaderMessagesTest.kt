package com.shinsaikou.reader.ui

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * Where a reader message is shown (unit ID3): the snackbar while the reader is open, a toast once it
 * is closing. The late message is R138 item 10's: a note the database refused because its entry was
 * removed, which reaches the screen after `Finish` has closed the reader. `ReaderScreen` marks
 * [ReaderMessages.closing] when it performs `Finish`, then hands every message to [ReaderMessages.show].
 */
class ReaderMessagesTest {

    private val snackbars = mutableListOf<String>()
    private val toasts = mutableListOf<String>()
    private var activityFinishing = false

    private val messages = ReaderMessages(
        snackbar = { snackbars += it },
        toast = { toasts += it },
        activityFinishing = { activityFinishing },
    )

    @Test
    fun `a message while the reader is open is a snackbar`() {
        messages.show("Couldn't load the chapter")

        snackbars shouldContainExactly listOf("Couldn't load the chapter")
        toasts.shouldBeEmpty()
    }

    @Test
    fun `a message after Finish is still shown, as a toast, since the snackbar goes with the activity`() {
        messages.show("Picture saved")
        // Finish with its own reason, then the refused note behind it.
        messages.closing()
        messages.show("Entry not found")
        messages.show("Couldn't save the note: this entry was removed")

        snackbars shouldContainExactly listOf("Picture saved")
        toasts shouldContainExactly listOf("Entry not found", "Couldn't save the note: this entry was removed")
    }

    @Test
    fun `a message while the activity is finishing without Finish, as after Back, is a toast`() {
        activityFinishing = true
        messages.show("Couldn't save the note: this entry was removed")

        toasts shouldContainExactly listOf("Couldn't save the note: this entry was removed")
        snackbars.shouldBeEmpty()
    }
}
