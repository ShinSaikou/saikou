package com.shinsaikou.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * The page the paged viewer lands on when a chapter update removed the transition it was showing:
 * the first page of the next chapter, or the last page of the previous one, whichever the
 * transition led to. That is where turning past the transition would have gone.
 *
 * With "always show chapter transition" off, a transition stays only while the chapter it leads to
 * is not loaded or chapters are missing. Upstream also kept the one on screen, so a reader of
 * one-page chapters who reached a transition before the next chapter loaded had to turn past it
 * every time (upstream issues 669, 1020 and 3198). Dropping it is not enough on its own: the
 * pager keeps the removed item's index, which in the right to left pager, whose list is reversed,
 * is the next chapter's last page.
 *
 * Null when [shown] is not a transition, is still among [items], or led to a chapter that is not a
 * neighbour in [chapters]. The last is a chapter switch, which moves the viewer to its own page.
 * While two pages show, the page may be part of a spread among [items]; the spread is where it lands.
 */
fun pageAfterRemovedTransition(shown: Any?, items: List<Any>, chapters: ViewerChapters): ReaderPage? {
    if (shown !is ChapterTransition || shown in items) return null
    val to = shown.to ?: return null
    val pages = to.pages ?: return null
    val page = when {
        to === chapters.nextChapter -> pages.firstOrNull()
        to === chapters.prevChapter -> pages.lastOrNull()
        else -> null
    }
    return page?.takeIf { landing -> items.any { holdsPage(it, landing) } }
}
