package com.shinsaikou.source.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One page of a chapter. Mirrors the ABI's Page: [index] (0-based reading order), [url] (the
 * page's own URL when the image URL must be resolved in a second step) and [imageUrl] (the
 * direct image URL when known). The reader's own page state machine (queued, loading, ready,
 * error) lives in :reader, not here; the only mutable member is the download progress hook.
 *
 * Equality, hashCode and copy cover only the three constructor fields; [progress] is per
 * instance and is not carried by copy.
 */
data class PageInfo(
    /** 0-based position in reading order. */
    val index: Int,
    /** The page's own URL, when the image URL has to be resolved in a second step. */
    val url: String = "",
    /** The direct image URL when the source already knows it, else null. */
    val imageUrl: String? = null,
) {
    /** 1-based number for display. */
    val number: Int get() = index + 1

    private val _progress = MutableStateFlow(0)

    /** Download progress in percent, or -1 while the content length is unknown. Starts at 0. */
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /**
     * Progress hook. Called by the source (through the adapter, on an OkHttp thread) while the
     * image body streams. Semantics match Page.update: percent when contentLength > 0, else -1.
     * Safe to call from any thread.
     */
    fun reportProgress(bytesRead: Long, contentLength: Long, done: Boolean) {
        _progress.value = when {
            done && contentLength > 0 -> 100
            contentLength > 0 -> (100 * bytesRead / contentLength).toInt()
            else -> -1
        }
    }

    /** Resets the hook, for a retry. */
    fun resetProgress() {
        _progress.value = 0
    }
}
