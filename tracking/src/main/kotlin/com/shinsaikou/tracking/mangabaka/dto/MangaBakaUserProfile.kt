// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/dto/MangaBakaUserProfile.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modified: the package; [ratingSteps] is nullable, as `GET /v1/my/profile` documents it (null reads
 * as 1, its default), and the two names default to null when the answer leaves them out.
 */
@Serializable
data class MangaBakaUserProfileResponse(
    val data: MangaBakaUserProfile,
)

@Serializable
data class MangaBakaUserProfile(
    // incomplete DTO since this is the only part we need
    val id: String,
    @SerialName("rating_steps")
    val ratingSteps: Int? = null,
    val nickname: String? = null,
    @SerialName("preferred_username")
    val preferredUsername: String? = null,
) {
    /** The name settings shows: upstream's order, the nickname, then the username, then the id. */
    fun displayName(): String =
        nickname?.takeIf { it.isNotBlank() } ?: preferredUsername?.takeIf { it.isNotBlank() } ?: id
}
