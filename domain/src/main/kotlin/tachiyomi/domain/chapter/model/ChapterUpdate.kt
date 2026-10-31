// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/chapter/model/ChapterUpdate.kt (modified: yes)
package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject

/**
 * Same coalesce semantics as [tachiyomi.domain.manga.model.MangaUpdate]: null means "leave unchanged".
 * [clearVolume] writes a null volume in the same statement, for a chapter whose name lost its volume
 * marker (phase 4 unit VG); [volume] is then ignored. [clearDeletedAt] writes a null deleted_at the
 * same way, restoring a soft-deleted chapter the source lists again (phase 4 unit CR); [deletedAt] is
 * then ignored.
 */
data class ChapterUpdate(
    val id: Long,
    val mangaId: Long? = null,
    val read: Boolean? = null,
    val bookmark: Boolean? = null,
    val lastPageRead: Long? = null,
    val dateFetch: Long? = null,
    val sourceOrder: Long? = null,
    val url: String? = null,
    val name: String? = null,
    val dateUpload: Long? = null,
    val chapterNumber: Double? = null,
    val scanlator: String? = null,
    val version: Long? = null,
    val memo: JsonObject? = null,
    val volume: Double? = null,
    val deletedAt: Long? = null,
    val clearVolume: Boolean = false,
    val clearDeletedAt: Boolean = false,
)

fun Chapter.toChapterUpdate(): ChapterUpdate {
    return ChapterUpdate(
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
        version = version,
        memo = memo,
        volume = volume,
        deletedAt = deletedAt,
    )
}
