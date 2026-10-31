package com.shinsaikou.reader.viewer

import com.shinsaikou.domain.manga.model.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer

/**
 * The only place a [Viewer] is constructed. Contract 07 section 3.8.
 *
 * The mode handed in is the effective one, which is never [ReadingMode.DEFAULT]: the reducer
 * resolves it through `ModeResolver` before it bumps `viewerGeneration`.
 */
object ViewerFactory {

    fun create(mode: ReadingMode, host: ViewerHost): Viewer = when (mode) {
        ReadingMode.LEFT_TO_RIGHT -> L2RPagerViewer(host)
        ReadingMode.RIGHT_TO_LEFT -> R2LPagerViewer(host)
        ReadingMode.VERTICAL -> VerticalPagerViewer(host)
        ReadingMode.WEBTOON -> WebtoonViewer(host)
        ReadingMode.CONTINUOUS_VERTICAL -> WebtoonViewer(host, isContinuous = false)
        ReadingMode.DEFAULT -> error("effective mode must be resolved")
    }
}
