package com.shinsaikou.sync

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A [SyncBackend] held in memory, for the JVM tests of the layout and of the sync engine. Three
 * faults a real store shows are switchable: [hide] leaves a file out of every listing (a listing
 * that lags behind a write, Q3) while it can still be read, [listingOrder] rearranges what [list]
 * returns, since neither real backend promises an order, and [dropWrites] answers a write with
 * success and stores nothing, as a server that lies about a write does.
 *
 * [clock] is the server's time that [RemoteFile.modifiedAt] reports.
 */
class InMemorySyncBackend(
    override val id: String = "memory",
    var clock: () -> Long = { 0L },
) : SyncBackend {

    private val files = linkedMapOf<String, Stored>()
    private val hidden = mutableSetOf<String>()

    /** While true, [create] succeeds and writes nothing. */
    var dropWrites: Boolean = false

    /** Applied to every listing after [hide]; the default keeps the order files were created in. */
    var listingOrder: (List<RemoteFile>) -> List<RemoteFile> = { it }

    fun hide(path: String) {
        hidden += path
    }

    fun unhide(path: String) {
        hidden -= path
    }

    /** Another backend holding the same files at the same times, for a test that runs one start twice. */
    fun copy(): InMemorySyncBackend = InMemorySyncBackend(id, clock).also { copy ->
        synchronized(files) { copy.files.putAll(files) }
    }

    /** The stored bytes of [path], or null; for assertions, never through the interface. */
    fun bytes(path: String): ByteArray? = synchronized(files) { files[path]?.bytes }

    override suspend fun probe(): BackendStatus = BackendStatus(reachable = true, authenticated = true, message = null)

    override suspend fun list(prefix: String): List<RemoteFile> {
        val listed = synchronized(files) {
            files.filter { (path, _) -> path.startsWith(prefix) && path !in hidden }
                .map { (path, stored) -> RemoteFile(path, stored.bytes.size.toLong(), stored.modifiedAt) }
        }
        return listingOrder(listed)
    }

    override suspend fun create(path: String, content: SyncContent) {
        val bytes = content.open().use { it.readBytes() }
        check(bytes.size.toLong() == content.length) {
            "Content of $path is ${bytes.size} bytes, not ${content.length}"
        }
        if (dropWrites) return
        synchronized(files) {
            files.remove(path)
            files[path] = Stored(bytes, clock())
        }
    }

    override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T {
        val bytes = synchronized(files) { files[path]?.bytes } ?: throw FileNotFoundException(path)
        return ByteArrayInputStream(bytes).use { block(it) }
    }

    override suspend fun delete(path: String) {
        synchronized(files) { files.remove(path) }
    }

    private class Stored(val bytes: ByteArray, val modifiedAt: Long)
}
