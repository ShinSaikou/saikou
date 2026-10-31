package com.shinsaikou.sync

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * One remote store of the library: a flat set of immutable files under paths that
 * `com.shinsaikou.sync.format.SyncLayout` names (Q3). Phase 3 has a WebDAV and a Google Drive
 * implementation. Neither is a graph binding: `:app`'s `SyncSession` builds the configured one from
 * its account.
 *
 * Paths are relative to the backend's own root, use `/` between segments and never start with one.
 * No file is ever replaced once readers may have seen it, so no call carries a precondition
 * (`If-Match` or a revision): the layout, not the store, keeps two devices from colliding.
 *
 * Every call throws an [java.io.IOException] (or a subclass the backend names) when the store
 * cannot answer; none returns a partial result as a success.
 */
interface SyncBackend {
    /** Stable backend key, stored in [SyncPreferences.backendId]. */
    val id: String

    suspend fun probe(): BackendStatus

    /**
     * Every file whose path starts with [prefix], at any depth, in no particular order. A listing
     * may miss a file another device wrote moments ago; readers stop at the gap that leaves (Q3).
     */
    suspend fun list(prefix: String): List<RemoteFile>

    /**
     * Writes [content] at [path], creating any directory the path needs. A file already at [path]
     * is replaced; the layout only writes one path twice when a restarted push rewrites the same
     * range with the same rows.
     */
    suspend fun create(path: String, content: SyncContent)

    /**
     * Whether [create] itself reads the file back and throws unless the store holds exactly the
     * bytes of the content, so that a store that answers a write with success and keeps other
     * bytes, or none, fails the write. The engine reads back each file it writes only when this is
     * false, so a file is read back once either way (phase 5 unit SC3, closing R88 carry-over 5 and
     * R89 carry-over 2). A backend that does not check its writes keeps the default.
     */
    val createReadsBack: Boolean get() = false

    /**
     * Opens [path] and hands its bytes to [block] as a stream, closing it when [block] returns.
     * The file is never read whole into memory, so a snapshot of a large library streams through.
     * A missing file throws [java.io.FileNotFoundException].
     */
    suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T

    /** Deletes [path]. Deleting a file that is already gone is not an error. */
    suspend fun delete(path: String)
}

data class BackendStatus(val reachable: Boolean, val authenticated: Boolean, val message: String?)

/**
 * One listed file. [modifiedAt] is the server's own time in epoch milliseconds, which Q3's
 * deletion rule measures age by, never a device clock.
 */
data class RemoteFile(val path: String, val size: Long, val modifiedAt: Long)

/**
 * Bytes to upload. [open] may be called more than once (a retried upload, a read-back compare) and
 * returns the same [length] bytes each time; the caller closes each stream it opens.
 */
interface SyncContent {
    val length: Long

    fun open(): InputStream
}

/** [bytes] as upload content; the engine plans and encodes each file in memory before it uploads it. */
fun SyncContent(bytes: ByteArray): SyncContent = object : SyncContent {
    override val length: Long = bytes.size.toLong()

    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** [file] as upload content, for a snapshot, which holds the whole library and never sits in memory. */
fun SyncContent(file: File): SyncContent = object : SyncContent {
    override val length: Long = file.length()

    override fun open(): InputStream = file.inputStream()
}
