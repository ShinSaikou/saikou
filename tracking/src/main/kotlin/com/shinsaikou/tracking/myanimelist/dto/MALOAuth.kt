// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/dto/MALOAuth.kt (modified: yes)
package com.shinsaikou.tracking.myanimelist.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modified: [createdAt] is stamped by the caller from the tracker's injected clock when the token
 * endpoint answers (it never sends one), where upstream defaulted it to the system clock, and
 * [isExpired] takes the time to compare with. Upstream's `adjustedExpiresIn` body property is gone:
 * with a backing field it was serialized into the stored token as a key of its own, which this
 * class still ignores when it reads a token upstream stored. The serial names are upstream's, so a token
 * restored from an upstream backup with private settings reads here, and this one reads there.
 */
@Serializable
data class MALOAuth(
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("created_at")
    @EncodeDefault
    val createdAt: Long = 0L,
) {
    // Assumes expired a minute earlier
    fun isExpired(nowEpochSeconds: Long) = createdAt + (expiresIn - 60) < nowEpochSeconds

    /** Names neither token. */
    override fun toString(): String = "MALOAuth(tokenType=$tokenType, expiresIn=$expiresIn, createdAt=$createdAt)"
}
