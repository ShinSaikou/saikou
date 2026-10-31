// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderChapter.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.model

import com.shinsaikou.reader.pipeline.PageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter

/**
 * One chapter of a reader session, with its page list and the loader that produced it.
 * Contract 07 section 2.2.
 *
 * Three modifications:
 *  - [pageLoader] is `com.shinsaikou.reader.pipeline.PageLoader`; upstream's loader package is not vendored.
 *  - [replacePages] is added. It is the only way the page list grows, which is what the tall-strip
 *    split needs (part 1b section 4.7).
 *  - [chapter] is a `var` over the immutable `tachiyomi.domain.chapter.model.Chapter`, where upstream
 *    has a `val` over its mutable database model, because the progress committer advances
 *    `lastPageRead` in memory before it writes. That is also why this is not a `data class`:
 *    structural equality over a mutating field would drift under `ChapterTransition.equals` and
 *    under the reducer's window comparisons every time a page turned. Identity is what every call
 *    site already means.
 */
class ReaderChapter(var chapter: Chapter) {

    val stateFlow = MutableStateFlow<State>(State.Wait)
    var state: State
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    var pageLoader: PageLoader? = null

    var requestedPage: Int = 0

    private var references = 0

    fun ref() {
        references++
    }

    fun unref() {
        references--
        if (references == 0) {
            if (pageLoader != null) {
                logcat { "Recycling chapter ${chapter.id}" }
            }
            pageLoader?.recycle()
            pageLoader = null
            state = State.Wait
        }
    }

    /** Replaces the page list of a loaded chapter. No-op while the chapter is not [State.Loaded]. */
    fun replacePages(pages: List<ReaderPage>) {
        if (state !is State.Loaded) return
        state = State.Loaded(pages)
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}
