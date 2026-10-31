// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/InsertPage.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.model

import com.shinsaikou.reader.pipeline.PageState
import java.io.InputStream

/**
 * A page inserted beside its [parent] and carrying the parent's index. Contract 07 section 2.2.
 *
 * `partIndex == 0`: a dual-page-split half (upstream's original meaning); shares the parent's stream.
 * `partIndex >= 1`: a tall-strip part produced by `TallStripSplitter`; has its own stream.
 *
 * Never moves reading progress on its own: the state machine commits `parent.index`, which is this
 * page's own index too.
 */
class InsertPage(
    val parent: ReaderPage,
    val partIndex: Int = 0,
    partStream: (() -> InputStream)? = null,
) : ReaderPage(parent.index, parent.url, parent.imageUrl) {

    override var chapter: ReaderChapter = parent.chapter

    init {
        stream = partStream ?: parent.stream
        route = parent.route
        status = PageState.Ready
    }
}
