// Vendored from upstream v0.20.4: core/archive/src/main/kotlin/tachiyomi/core/archive/ArchiveInputStream.kt (modified: yes)
// Modifications: read(ByteArray, Int, Int) honours its offset and length. Upstream wraps the
// caller's array and then calls clear(), which resets position to 0 and limit to the array's
// capacity, so libarchive writes from index 0 and may return more bytes than were asked for.
// Every upstream caller buffers first, so off is always 0 and len always the whole array and the
// bug never shows. Skia does not: BitmapFactory.decodeStream asks for fewer bytes than its
// scratch array holds, memcpys the returned count, and overruns its own buffer. That is a
// SIGSEGV inside libhwui, which is how a cbz page killed the reader process.
package tachiyomi.core.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import tachiyomi.core.archive.ArchiveEntry as UpstreamArchiveEntry

internal class ArchiveInputStream(buffer: Long, size: Long) : InputStream() {
    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenMemoryUnsafe(archive, buffer, size)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    /**
     * Reused across calls so a decode that reads in small chunks does not allocate a direct buffer
     * per chunk. Grown, never shrunk, and only ever touched from the reading thread: an
     * [InputStream] has no concurrent contract.
     */
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(SCRATCH_BYTES)

    override fun read(): Int {
        read(oneByteBuffer)
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (scratch.capacity() < len) scratch = ByteBuffer.allocateDirect(len)
        scratch.clear()
        scratch.limit(len)
        Archive.readData(archive, scratch)
        scratch.flip()
        if (!scratch.hasRemaining()) return -1
        val read = scratch.remaining()
        scratch.get(b, off, read)
        return read
    }

    private fun read(buffer: ByteBuffer) {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip()
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    private companion object {
        /** One page of a comic archive reads in 8 KB chunks through Skia; this covers them all. */
        const val SCRATCH_BYTES = 16 * 1024
    }

    fun getNextEntry(): UpstreamArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            UpstreamArchiveEntry(name, isFile)
        }
    }
}
