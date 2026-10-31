// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/chapter/model/Chapter.kt (modified: yes)
package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.extensions.EMPTY

data class Chapter(
    val id: Long,
    val mangaId: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val dateFetch: Long,
    val sourceOrder: Long,
    val url: String,
    val name: String,
    val dateUpload: Long,
    val chapterNumber: Double,
    val scanlator: String?,
    val lastModifiedAt: Long,
    val version: Long,
    val memo: JsonObject,
    /** Volume number, null = not recognised. Nullable because volume 0 is a real value. Fresh field, migration 1. */
    val volume: Double?,
    /** Epoch millis tombstone, null = live. Fresh field, migration 1. */
    val deletedAt: Long?,
) {
    val isRecognizedNumber: Boolean
        get() = chapterNumber >= 0f

    fun copyFrom(other: Chapter): Chapter {
        return copy(
            name = other.name,
            url = other.url,
            dateUpload = other.dateUpload,
            chapterNumber = other.chapterNumber,
            scanlator = other.scanlator?.ifBlank { null },
            volume = other.volume,
        )
    }

    companion object {
        fun create() = Chapter(
            id = -1,
            mangaId = -1,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            dateFetch = 0,
            sourceOrder = 0,
            url = "",
            name = "",
            dateUpload = -1,
            chapterNumber = -1.0,
            scanlator = null,
            lastModifiedAt = 0,
            version = 1,
            memo = JsonObject.EMPTY,
            volume = null,
            deletedAt = null,
        )
    }
}
