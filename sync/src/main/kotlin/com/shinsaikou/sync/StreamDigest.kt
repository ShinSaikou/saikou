package com.shinsaikou.sync

import java.io.InputStream
import java.security.MessageDigest

/**
 * A stream's length and SHA-256, which both backends compare a written file's read-back with before
 * a write counts as stored (R88 item 6, phase 3 unit H). Printed in a failure's message, never
 * stored.
 */
internal data class StreamDigest(val length: Long, val sha256: String) {
    override fun toString(): String = "$length bytes (SHA-256 $sha256)"

    companion object {
        private const val BUFFER = 64 * 1024

        /** Reads [stream] to its end, never holding more than one buffer of it. */
        fun of(stream: InputStream): StreamDigest {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER)
            var length = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                length += read
            }
            return StreamDigest(length, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
