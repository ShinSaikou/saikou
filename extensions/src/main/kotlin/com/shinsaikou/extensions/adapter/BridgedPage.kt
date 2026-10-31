package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.source.model.Page

/**
 * The ABI [Page] handed to an extension for one image fetch. Its progress callbacks, written from
 * OkHttp threads by `ProgressResponseBody`, are forwarded to the [PageInfo] the reader holds.
 */
internal class BridgedPage(
    val info: PageInfo,
    imageUrl: String?,
) : Page(index = info.index, url = info.url, imageUrl = imageUrl) {

    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        super.update(bytesRead, contentLength, done)
        info.reportProgress(bytesRead, contentLength, done)
    }
}
