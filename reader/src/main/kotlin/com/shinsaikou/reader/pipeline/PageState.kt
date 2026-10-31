package com.shinsaikou.reader.pipeline

/**
 * Where one page is in the pipeline. Contract 07 section 4.4.
 *
 * A page walks [Queue] to [LoadPage] (the source is resolving the image URL) to [DownloadImage]
 * (bytes are streaming into the [com.shinsaikou.reader.cache.ChapterCache]) to [Ready], or lands in
 * [Error]. Only a [Ready] page carries a route and a stream, and only a [Ready] page is ever
 * handed to a viewer holder.
 */
sealed interface PageState {
    data object Queue : PageState
    data object LoadPage : PageState
    data object DownloadImage : PageState
    data object Ready : PageState
    data class Error(val error: Throwable) : PageState
}
