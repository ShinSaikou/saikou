package com.shinsaikou.reader.ui

/**
 * Where the reader shows a message (unit ID3): on its own snackbar while it is open,
 * and as a toast once it is closing.
 *
 * A message can come with or after `ReaderEffect.Finish`: the reason a failed reader closes, and a
 * note the database refused because its entry was removed, which arrives once the reader is already
 * on its way out (R138 item 10). A snackbar on an activity that is finishing is never seen, so those
 * stay toasts, which outlive the activity. Handing them to the screen the reader returns to instead
 * would need a new channel across modules: the reader is started with a plain `startActivity` from
 * five places (the entry screen, Library, History, Updates and a notification), none of which waits
 * for a result, and `:reader` cannot see `:app`.
 *
 * [closing] is the screen's own mark, set when it performs `Finish`; [activityFinishing] covers a
 * close the screen did not start, such as Back.
 */
internal class ReaderMessages(
    private val snackbar: (String) -> Unit,
    private val toast: (String) -> Unit,
    private val activityFinishing: () -> Boolean,
) {
    private var closing = false

    /** The reader is closing: every message from here on is a toast. */
    fun closing() {
        closing = true
    }

    fun show(text: String) {
        if (closing || activityFinishing()) toast(text) else snackbar(text)
    }
}
