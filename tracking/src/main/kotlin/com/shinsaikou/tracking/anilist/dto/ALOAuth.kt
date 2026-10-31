// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/dto/ALOAuth.kt (modified: yes)
package com.shinsaikou.tracking.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modified: upstream v0.21.0's shape (21af65b10, triage E35): the token and when it expires, in epoch
 * millis, with no `token_type` or `expires_in`, which nothing read. The caller sets [expires] from
 * the tracker's injected clock, and [isExpired] takes the time to compare with, where upstream read
 * the system clock. The serial names are upstream's, so a grant an upstream backup with private
 * settings restores reads here (the keys this class lacks are ignored), and this one reads there.
 */
@Serializable
data class ALOAuth(
    @SerialName("access_token")
    val accessToken: String,
    val expires: Long,
) {
    /** Expired a minute early, as upstream counts it. */
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis + EARLY_MILLIS > expires

    /** Names no token. */
    override fun toString(): String = "ALOAuth(expires=$expires)"
}

// Not on a companion: the serializer plugin puts `serializer()` on this class's own.
private const val EARLY_MILLIS = 60_000L
