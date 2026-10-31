package com.shinsaikou.reader.state

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * The three chapters a viewer holds at a time, and the reference rules that decide when a loader
 * is recycled. Contract 07 section 3.4.
 */
object ChapterWindow {

    fun around(session: ReaderSession, current: ReaderChapter): ViewerChapters =
        ViewerChapters(current, session.previous(current), session.next(current))

    /**
     * Upstream's rule, and the order is the rule: the new window is referenced before the old one is
     * released, so a chapter present in both never drops to zero references and keeps its loader.
     * Releasing first would recycle it and force a reload of a chapter that never left the screen.
     */
    fun applyRefs(add: ViewerChapters, release: ViewerChapters?) {
        add.ref()
        release?.unref()
    }
}
