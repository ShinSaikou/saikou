package com.shinsaikou.source

import java.security.MessageDigest

/**
 * Identity helpers. The host never assigns ids to live sources; these exist for the Local source
 * constant and for data-layer code (source_alias, backup import) that must reason about upstream ids.
 */
object SourceIds {
    /** Upstream's LocalSource.ID. The Local source in :sources:local reports exactly this. */
    const val LOCAL: Long = 0L

    /**
     * Reproduces HttpSource.generateId from the extension ABI byte for byte: MD5 of
     * "<name.lowercase()>/<lang>/<versionId>", first 8 bytes big-endian, sign bit cleared.
     * Only name is lowercased; lang is used as-is. Use it for source_alias rows and backup
     * reasoning, never to label a live source. A unit test in :extensions asserts it agrees with
     * the vendored HttpSource for a synthetic name/lang/versionId.
     */
    fun deriveHttpSourceId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        var id = 0L
        for (i in 0 until 8) {
            id = (id shl 8) or (bytes[i].toLong() and 0xff)
        }
        return id and Long.MAX_VALUE
    }
}
