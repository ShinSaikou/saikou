package com.shinsaikou.ui.util

import android.content.res.Resources
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.LibraryUpdateError
import com.shinsaikou.presentation.core.util.ErrorKeyText

/**
 * The one throwable-to-text mapping for the screens (contract 08 section 5 preamble). Snackbars and
 * error states call this rather than rendering `Throwable.message` directly, so the common network
 * failures read the same wherever they surface.
 *
 * It takes [Resources] rather than a `Context` so a composable can pass `LocalResources.current`.
 * A resource read off `LocalContext.current` is not configuration aware and lint rejects it.
 *
 * Every named failure is resolved by [ErrorKey], the same resolver the library update error list
 * stores its keys from, so each reads the same in a snackbar and in that list, the HTTP status code
 * included. A generic failure's own message, first line only ([ErrorKey.message]), is what a
 * snackbar shows; the error list shows the generic text with that same message under it.
 *
 * The throwable half and the key-to-string table are [ErrorKeyText]'s, in `:presentation-core`, so the
 * reader resolves a failure the same way; this object adds the error list's rows.
 */
object ErrorText {

    fun of(resources: Resources, error: Throwable?): String = ErrorKeyText.of(resources, error)

    /**
     * One row of the library update error list. An HTTP failure's detail is its status code; a
     * generic failure reads as the generic text, with [messageOf] under it. A row stored before keys
     * were (see [LibraryUpdateError]) shows the text it was stored with, as it did before.
     */
    fun of(resources: Resources, error: LibraryUpdateError): String {
        val key = error.key
            ?: return error.detail?.takeIf { it.isNotBlank() } ?: ErrorKeyText.text(resources, ErrorKey.GENERIC, null)
        return if (key == ErrorKey.GENERIC) ErrorKeyText.text(resources, key, null) else of(resources, key, error.detail)
    }

    /**
     * What a generic row of the library update error list shows under its text: the failure's own
     * message, as the extension wrote it, or its class name for a row stored before messages were
     * and for a failure that had none. Null for every other row.
     */
    fun messageOf(error: LibraryUpdateError): String? =
        error.detail?.takeIf { error.key == ErrorKey.GENERIC && it.isNotBlank() }

    /**
     * A failure stored as a key and its detail, as the library update error list, the adoption
     * summary and the sync run record store them (ruling R67). A sync key's detail is its
     * `KeyedFailure.errorDetail`.
     */
    fun of(resources: Resources, key: ErrorKey, storedDetail: String?): String {
        val detail = storedDetail?.takeIf { it.isNotBlank() }
        return when (key) {
            ErrorKey.HTTP -> ErrorKeyText.text(resources, key, detail?.toIntOrNull())
            ErrorKey.GENERIC -> {
                val text = ErrorKeyText.text(resources, key, null)
                if (detail == null) text else resources.getString(R.string.update_error_detail_format, text, detail)
            }
            else -> ErrorKeyText.text(resources, key, null, detail)
        }
    }

    /**
     * A download queue row's failure, stored in one text column: the id of a key whose text needs no
     * detail, resolved here in the reader's language, or the text the row was stored with, shown as
     * it is (an HTTP failure's "HTTP error 503", any row written before keys were, a folder in use
     * stored as text before R104 item 3 included; R67 rule 5, no migration). Which one a row holds is
     * a match on the text, heuristic both ways: an old row whose message happened to be an id, as a
     * socket's "timeout" is, reads as that key.
     */
    fun ofStored(resources: Resources, stored: String): String =
        ErrorKey.fromId(stored)?.let { of(resources, it, null) } ?: stored
}
