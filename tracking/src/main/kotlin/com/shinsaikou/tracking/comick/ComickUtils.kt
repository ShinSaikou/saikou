// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/ComickUtil.kt (modified: yes)
package com.shinsaikou.tracking.comick

import java.util.Base64

// Modified: the HID mapping is upstream's, with [hidToLongOrNull] beside it so a title whose HID
// does not decode is skipped rather than failing a whole search; the status mappings read a status
// this code does not know as reading, as MyAnimeList's, AniList's and MangaBaka's do, where upstream
// threw `NotImplementedError`, an `Error` the retry worker would not catch; and upstream's
// `ComickMissingScopesException` is `TrackerPermissionMissingException`, which names the scope left
// out so settings can say which one to turn on.

/** The length of a Comick HID: eight URL-safe base64 characters, six bytes. */
private const val HID_LENGTH = 8

/**
 * Deterministic HID to Long, for the row's remote id: the HID's six bytes read big-endian, so every
 * HID is a Long in 0 until 2^48 and [toHid] gives it back.
 */
internal fun String.asHidToLong(): Long {
    require(length == HID_LENGTH) { "HID must be $HID_LENGTH characters for decoding, got $length" }
    return Base64.getUrlDecoder()
        .decode(this)
        .fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
}

/** [asHidToLong], or null for a string that is not a HID. */
internal fun String.hidToLongOrNull(): Long? = runCatching { asHidToLong() }.getOrNull()

/** The HID a remote id was decoded from. */
internal fun Long.toHid(): String {
    require(this in 0 until (1L shl 48)) { "Long not in 48-bit range for HID conversion: $this" }
    return Base64.getUrlEncoder()
        .encodeToString(
            ByteArray(6) {
                (this shr (8 * (5 - it))).toByte()
            },
        )
}

internal fun Long.toApiListStatus(): Int = when (this) {
    Comick.READING -> 1
    Comick.COMPLETED -> 2
    Comick.ON_HOLD -> 3
    Comick.DROPPED -> 4
    Comick.PLAN_TO_READ -> 5
    else -> 1
}

internal fun Long.fromApiListStatus(): Long = when (this) {
    1L -> Comick.READING
    2L -> Comick.COMPLETED
    3L -> Comick.ON_HOLD
    4L -> Comick.DROPPED
    5L -> Comick.PLAN_TO_READ
    else -> Comick.READING
}
