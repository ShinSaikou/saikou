// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/dto/ComickOAuth.kt (modified: yes)
package com.shinsaikou.tracking.comick.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Modified: the stored grant keeps upstream's serial names and shape (the access token, when it
 * expires in epoch seconds, the refresh token and the granted scopes), so a grant an upstream backup
 * with private settings restores reads here, and this one reads there. [isExpired] takes the time to
 * compare with, where upstream read the system clock. The token endpoint's answer is its own class
 * ([ComickTokenResponse]), where upstream decoded it as the stored grant itself.
 */
@Serializable
data class ComickOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_at")
    val expiresAt: Long,
    @SerialName("refresh_token")
    val refreshToken: String,
    val scope: String,
) {
    /** Expired a minute early, as upstream counts it. */
    fun isExpired(nowEpochSeconds: Long): Boolean = nowEpochSeconds + EARLY_SECONDS >= expiresAt

    /** Names neither token. */
    override fun toString(): String = "ComickOAuth(expiresAt=$expiresAt, scope=$scope)"
}

/**
 * The token endpoint's answer to a code exchange and to a refresh. Upstream read Comick's
 * `expires_at`; the standard `expires_in` is preferred when Comick sends it, so a device clock that
 * is off does not keep an expired token or refresh a live one.
 *
 * [refreshToken] is absent when the owner left `offline_access` off, which the code exchange
 * refuses. A refresh answer without one keeps the refresh token it was sent; one with a new token
 * rotates it. [scope] is the granted scopes, space separated.
 */
@Serializable
data class ComickTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    @SerialName("expires_at")
    val expiresAt: Long? = null,
    val scope: String? = null,
) {
    /** The granted scopes; none when the answer names none. */
    fun scopes(): Set<String> = scope?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /**
     * The grant to store, expiring [expiresIn] seconds after [nowEpochSeconds], else at [expiresAt].
     * A refresh answer passes the grant it replaces as [previous], whose refresh token and scopes
     * stand in for any this answer leaves out. Throws [SerializationException] for an answer that
     * names no expiry, or no refresh token with none to keep: it is not a grant.
     */
    fun toOAuth(nowEpochSeconds: Long, previous: ComickOAuth? = null): ComickOAuth {
        val expiry = expiresIn?.let { nowEpochSeconds + it } ?: expiresAt
            ?: throw SerializationException("The token answer names no expiry")
        val refresh = refreshToken?.takeIf { it.isNotBlank() } ?: previous?.refreshToken
            ?: throw SerializationException("The token answer carries no refresh token")
        return ComickOAuth(accessToken, expiry, refresh, scope ?: previous?.scope.orEmpty())
    }

    /** Names neither token. */
    override fun toString(): String = "ComickTokenResponse(expiresIn=$expiresIn, expiresAt=$expiresAt, scope=$scope)"
}

// Not on a companion: the serializer plugin puts `serializer()` on this class's own.
private const val EARLY_SECONDS = 60L
