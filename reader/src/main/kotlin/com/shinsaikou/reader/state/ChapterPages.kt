package com.shinsaikou.reader.state

import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

// A chapter's page list holds a tall page's parts as entries of their own, spliced in right after
// the page and carrying its index. Progress, the page count and the slider speak of pages, so they
// read the list through these two rather than by position.

/** How many pages the list holds, parts not counted. */
fun List<ReaderPage>.pageCount(): Int = count { it !is InsertPage }

/**
 * The entry that shows page [index] first: the page itself, since its parts come after it. [index]
 * is clamped to the chapter's pages, which must not be empty.
 */
fun List<ReaderPage>.firstPartOf(index: Int): ReaderPage {
    val page = index.coerceIn(0, pageCount() - 1)
    return first { it.index == page && it !is InsertPage }
}
