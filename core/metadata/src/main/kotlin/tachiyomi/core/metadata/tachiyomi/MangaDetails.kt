// Vendored from upstream v0.20.4: core-metadata/src/main/java/tachiyomi/core/metadata/tachiyomi/MangaDetails.kt (modified: no)
package tachiyomi.core.metadata.tachiyomi

import kotlinx.serialization.Serializable

@Serializable
class MangaDetails(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Int? = null,
)
