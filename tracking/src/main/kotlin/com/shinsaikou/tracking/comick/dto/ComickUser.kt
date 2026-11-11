// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/dto/ComickUser.kt (modified: yes)
package com.shinsaikou.tracking.comick.dto

import kotlinx.serialization.Serializable

/**
 * Modified: the package; [username] defaults to null when the answer leaves it out, and
 * [displayName] is upstream's fallback from the username to the id, which upstream wrote at each
 * caller.
 */
@Serializable
data class ComickUser(
    val id: String,
    val username: String? = null,
) {
    /** The name settings shows: the username, else the id. */
    fun displayName(): String = username?.takeIf { it.isNotBlank() } ?: id
}
