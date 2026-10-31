// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.model

import com.shinsaikou.reader.pipeline.PageRoute
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.source.model.PageInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream

/**
 * One page of a chapter, as the viewers see it. Contract 07 section 2.2.
 *
 * Upstream's version extends `eu.kanade.tachiyomi.source.model.Page` and inherits its status and
 * progress flows from the extension ABI. Nothing in `:reader` may import that ABI, so the two
 * flows and the three fields move onto this class and the state enum becomes
 * [com.shinsaikou.reader.pipeline.PageState]. That is modification (P).
 */
open class ReaderPage(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) {

    open lateinit var chapter: ReaderChapter

    /** 1-based number for display and for the slider. */
    val number: Int get() = index + 1

    /** The contract page handed to `Source.getImage`; its progress hook feeds [progressFlow]. */
    val info: PageInfo = PageInfo(index = index, url = url, imageUrl = imageUrl)

    private val _status = MutableStateFlow<PageState>(PageState.Queue)
    val statusFlow: StateFlow<PageState> = _status.asStateFlow()
    var status: PageState
        get() = _status.value
        set(value) {
            _status.value = value
        }

    private val _progress = MutableStateFlow(0)
    val progressFlow: StateFlow<Int> = _progress.asStateFlow()
    var progress: Int
        get() = _progress.value
        set(value) {
            _progress.value = value
        }

    /** Fresh. Decided by `ImageProbe` before the page becomes Ready; null until then. */
    var route: PageRoute? = null

    /** Fresh. Number of tall-strip parts; 1 when unsplit. Part 0 is this page's own [stream]. */
    var partCount: Int = 1
}
