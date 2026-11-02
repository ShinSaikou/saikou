package com.shinsaikou.reader

/**
 * An entry's new-chapters notification, which the reader clears as it opens the entry, as upstream's
 * `ReaderActivity` dismisses it. The notifier is `:library-update`'s, which `:reader` cannot reach,
 * so `:app` binds this.
 */
fun interface NewChaptersNotification {
    fun dismiss(mangaId: Long)
}
