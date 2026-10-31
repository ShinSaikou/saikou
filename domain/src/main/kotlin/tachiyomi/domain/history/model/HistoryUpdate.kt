// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/history/model/HistoryUpdate.kt (modified: no)
package tachiyomi.domain.history.model

import java.util.Date

data class HistoryUpdate(
    val chapterId: Long,
    val readAt: Date,
    val sessionReadDuration: Long,
)
