// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt (modified: yes)
package tachiyomi.data.chapter

import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.Chapter

/**
 * Extracted from upstream's private `ChapterRepositoryImpl.mapChapter` so the fresh repositories
 * under `com.shinsaikou.data` can read chapter rows too (05-data 4.1). Modified: the tuple gains
 * `volume` and `deletedAt`, then schema version 2's two clocks, which the domain model does not
 * carry (phase 3 unit C).
 */
object ChapterMapper {
    @Suppress("UNUSED_PARAMETER")
    fun mapChapter(
        id: Long,
        mangaId: Long,
        url: String,
        name: String,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        chapterNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
        lastModifiedAt: Long,
        version: Long,
        isSyncing: Long,
        memo: JsonObject,
        volume: Double?,
        deletedAt: Long?,
        bookmarkNotesModifiedAt: Long?,
        restoredAt: Long?,
    ): Chapter = Chapter(
        id = id,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        lastModifiedAt = lastModifiedAt,
        version = version,
        memo = memo,
        volume = volume,
        deletedAt = deletedAt,
    )
}
