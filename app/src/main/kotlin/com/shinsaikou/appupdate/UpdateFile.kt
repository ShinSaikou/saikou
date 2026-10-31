package com.shinsaikou.appupdate

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The file did not hash to the release's digest; it has been deleted. */
class DigestMismatchException : IOException("The download does not match the release's digest")

/** Writing a downloaded update to disk. */
object UpdateFile {

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Copies [input] to [target], hashing as it writes, and keeps the file only when [expected]
     * accepts the hash: a mismatch deletes it and throws [DigestMismatchException], and so does any
     * other failure, a cancellation included, so a half-written or refused file never stays behind.
     * [onProgress] gets the bytes written so far and [total] (-1 when unknown) after each chunk.
     */
    suspend fun save(
        input: InputStream,
        total: Long,
        target: File,
        expected: AssetDigest,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val digest = AssetDigest.newSha256()
        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var done = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
            if (!expected.accepts(digest.digest())) throw DigestMismatchException()
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }
}
