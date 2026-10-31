package com.shinsaikou.source.model

import okio.Buffer
import okio.BufferedSource
import java.io.Closeable

/**
 * The bytes of one page image plus what is known about them. Produced by
 * [com.shinsaikou.source.Source.getImage]; consumed by the reader's page pipeline (magic-byte sniff,
 * disk cache write, decode) and by the download engine.
 *
 * Close contract:
 *  - The caller owns the result and must call [close] exactly once, normally with `use { }`.
 *  - [source] may be called at most once and only before [close]; it returns a stream the
 *    caller reads to exhaustion or abandons. Closing the result closes the stream and whatever
 *    backs it (for extension sources, the OkHttp Response).
 *  - [bytes] reads the whole body into memory, closes the stream, and may be called once as an
 *    alternative to [source]. Prefer [source] for anything over a few hundred kilobytes.
 *  - A result built with [ofBytes] has a no-op [close] and serves the array through both accessors.
 *  - After [close], both accessors throw IllegalStateException.
 */
class ImageResult private constructor(
    /**
     * Value of the Content-Type header, lowercased, or null when unknown.
     * Not trusted: the reader sniffs magic bytes.
     */
    val contentType: String?,
    /** Body length in bytes when known, else -1. */
    val contentLength: Long,
    /** The image URL actually fetched (after any getImageUrl resolution), or null for local images. */
    val imageUrl: String?,
    private var streamSource: BufferedSource?,
    private var byteArray: ByteArray?,
    private val onClose: () -> Unit,
) : Closeable {

    private var closed = false
    private var consumed = false

    /** Streaming access. Single use. */
    fun source(): BufferedSource {
        check(!closed) { "ImageResult is closed" }
        check(!consumed) { "ImageResult body already consumed" }
        consumed = true
        streamSource?.let { return it }
        return Buffer().write(byteArray!!)
    }

    /** Whole-body access. Single use. Closes the underlying stream once read. */
    fun bytes(): ByteArray {
        check(!closed) { "ImageResult is closed" }
        check(!consumed) { "ImageResult body already consumed" }
        consumed = true
        byteArray?.let { return it }
        return streamSource!!.use { it.readByteArray() }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            streamSource?.close()
        } finally {
            streamSource = null
            byteArray = null
            onClose()
        }
    }

    companion object {
        /**
         * Streaming result. [onClose] releases the backing resource (the adapter passes
         * `response::close`). The source is closed before [onClose] runs.
         */
        fun ofSource(
            source: BufferedSource,
            contentType: String?,
            contentLength: Long,
            imageUrl: String?,
            onClose: () -> Unit = {},
        ): ImageResult = ImageResult(
            contentType = contentType?.lowercase(),
            contentLength = contentLength,
            imageUrl = imageUrl,
            streamSource = source,
            byteArray = null,
            onClose = onClose,
        )

        /** In-memory result (Local source, tests). */
        fun ofBytes(
            bytes: ByteArray,
            contentType: String?,
            imageUrl: String? = null,
        ): ImageResult = ImageResult(
            contentType = contentType?.lowercase(),
            contentLength = bytes.size.toLong(),
            imageUrl = imageUrl,
            streamSource = null,
            byteArray = bytes,
            onClose = {},
        )
    }
}
