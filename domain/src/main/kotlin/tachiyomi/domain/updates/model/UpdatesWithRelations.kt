// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/updates/model/UpdatesWithRelations.kt (modified: yes)
package tachiyomi.domain.updates.model

import tachiyomi.domain.manga.model.MangaCover

/** Modified: [displayTitle], the entry's edit over [mangaTitle], the source's (phase 5 unit ED). */
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
)
