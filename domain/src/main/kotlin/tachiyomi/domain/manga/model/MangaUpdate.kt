// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/model/MangaUpdate.kt (modified: yes)
package tachiyomi.domain.manga.model

import com.shinsaikou.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject

/**
 * A null field means "leave unchanged": the update query coalesces every column. No update can set
 * a nullable column back to NULL; clearing [deletedAt] uses MangaRepository.undelete instead.
 */
data class MangaUpdate(
    val id: Long,
    val source: Long? = null,
    val favorite: Boolean? = null,
    val lastUpdate: Long? = null,
    val nextUpdate: Long? = null,
    val fetchInterval: Int? = null,
    val dateAdded: Long? = null,
    val viewerFlags: Long? = null,
    val chapterFlags: Long? = null,
    val coverLastModified: Long? = null,
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
    val updateStrategy: UpdateStrategy? = null,
    val initialized: Boolean? = null,
    val version: Long? = null,
    val notes: String? = null,
    val memo: JsonObject? = null,
    val manualPosition: Long? = null,
    val deletedAt: Long? = null,
)

fun Manga.toMangaUpdate(): MangaUpdate {
    return MangaUpdate(
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
        nextUpdate = nextUpdate,
        fetchInterval = fetchInterval,
        dateAdded = dateAdded,
        viewerFlags = viewerFlags,
        chapterFlags = chapterFlags,
        coverLastModified = coverLastModified,
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        updateStrategy = updateStrategy,
        initialized = initialized,
        version = version,
        notes = notes,
        memo = memo,
        manualPosition = manualPosition,
        deletedAt = deletedAt,
    )
}
