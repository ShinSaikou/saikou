package com.shinsaikou.backup.importer

import java.io.File
import java.security.MessageDigest

/**
 * The checkpoint's identity of "the same file". A hash of the private copy, not of the picked URI,
 * because the grant behind the URI may be gone by the time a run resumes.
 */
internal fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
