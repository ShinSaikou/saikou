package com.shinsaikou.appupdate

import java.security.MessageDigest

/**
 * What a release asset's `digest` field asks of the downloaded file. The download always hashes
 * with SHA-256, the one algorithm GitHub publishes, and asks [accepts] about the result.
 */
sealed interface AssetDigest {

    /** Whether a file whose SHA-256 is [sha256] may go on to the signature check. */
    fun accepts(sha256: ByteArray): Boolean

    /** No digest published (an asset uploaded before GitHub hashed them): the signature check still runs. */
    data object Absent : AssetDigest {
        override fun accepts(sha256: ByteArray): Boolean = true
    }

    /** The lowercase hex GitHub published. */
    data class Sha256(val hex: String) : AssetDigest {
        override fun accepts(sha256: ByteArray): Boolean = MessageDigest.isEqual(hex.toByteArray(), sha256.toHex().toByteArray())
    }

    /** A digest this build cannot read: the download is refused rather than taken unchecked. */
    data class Unreadable(val field: String) : AssetDigest {
        override fun accepts(sha256: ByteArray): Boolean = false
    }

    companion object {
        private val SHA256 = Regex("""sha256:([0-9a-fA-F]{64})""")

        fun parse(field: String?): AssetDigest {
            if (field.isNullOrBlank()) return Absent
            val match = SHA256.matchEntire(field.trim()) ?: return Unreadable(field)
            return Sha256(match.groupValues[1].lowercase())
        }

        /** A fresh SHA-256, what the download feeds as it writes. */
        fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
