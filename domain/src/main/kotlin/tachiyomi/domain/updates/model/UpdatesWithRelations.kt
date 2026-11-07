// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/updates/model/UpdatesWithRelations.kt (modified: yes)
package tachiyomi.domain.updates.model

import tachiyomi.domain.manga.model.MangaCover

/**
 * Modified: [displayTitle], the entry's edit over [mangaTitle], the source's (phase 5 unit ED), and
 * [mergedMainId], the main entry a merged title's row stands under, whose title and cover it then
 * carries. [mangaId] and [sourceId] stay the owner of [chapterId].
 */
data class UpdatesWithRelations(
    val mangaId: Long,
    val mangaTitle: String,
    val displayTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val sourceId: Long,
    val dateFetch: Long,
    val coverData: MangaCover,
    val mergedMainId: Long? = null,
) {
    /** The entry the row opens: the merged title's main entry, or the row's own. */
    val titleId: Long get() = mergedMainId ?: mangaId
}
