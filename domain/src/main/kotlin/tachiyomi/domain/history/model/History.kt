// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/history/model/History.kt (modified: no)
package tachiyomi.domain.history.model

import java.util.Date

data class History(
    val id: Long,
    val chapterId: Long,
    val readAt: Date?,
    val readDuration: Long,
) {
    companion object {
        fun create() = History(
            id = -1L,
            chapterId = -1L,
            readAt = null,
            readDuration = -1L,
        )
    }
}
