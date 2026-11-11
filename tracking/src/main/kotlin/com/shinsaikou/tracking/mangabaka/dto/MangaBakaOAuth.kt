// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/dto/MangaBakaOAuth.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modified: the stored grant keeps upstream's serial names and shape (the access token, the refresh
 * token and when the access token expires, in epoch seconds), so a grant an upstream backup with
 * private settings restores reads here, and this one reads there. [expiresAt] is set by the caller
 * from the tracker's injected clock and the token endpoint's `expires_in`
 * ([MangaBakaTokenResponse.toOAuth]), where upstream read MangaBaka's own `expires_at`, so a device
 * clock that is off does not keep an expired token or refresh a live one; [isExpired] takes the time
 * to compare with, where upstream read the system clock.
 */
@Serializable
data class MangaBakaOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_at")
    val expiresAt: Long,
) {
    /** Expired a minute early, as upstream counts it. */
    fun isExpired(nowEpochSeconds: Long): Boolean = nowEpochSeconds + EARLY_SECONDS > expiresAt

    /** Names neither token. */
    override fun toString(): String = "MangaBakaOAuth(expiresAt=$expiresAt)"
}

/**
 * The token endpoint's answer to a code exchange and to a refresh. MangaBaka sends `token_type`,
 * `expires_at`, `scope` and an `id_token` too, which nothing here reads. Every refresh rotates
 * [refreshToken], so it is required: an answer without one cannot be stored.
 */
@Serializable
data class MangaBakaTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
) {
    /** The grant to store, expiring [expiresIn] seconds after [nowEpochSeconds]. */
    fun toOAuth(nowEpochSeconds: Long): MangaBakaOAuth = MangaBakaOAuth(accessToken, refreshToken, nowEpochSeconds + expiresIn)

    /** Names neither token. */
    override fun toString(): String = "MangaBakaTokenResponse(expiresIn=$expiresIn)"
}

// Not on a companion: the serializer plugin puts `serializer()` on this class's own.
private const val EARLY_SECONDS = 60L
