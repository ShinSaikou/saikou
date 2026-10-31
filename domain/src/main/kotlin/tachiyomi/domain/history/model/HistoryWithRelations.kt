// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/history/model/HistoryWithRelations.kt (modified: yes)
package tachiyomi.domain.history.model

import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

/** Modified: [displayTitle], the entry's edit over [title], the source's (phase 5 unit ED). */
data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val title: String,
    val displayTitle: String,
    val chapterNumber: Double,
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
)
