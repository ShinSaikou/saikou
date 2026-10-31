// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/history/interactor/GetTotalReadDuration.kt (modified: no)
package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.repository.HistoryRepository

class GetTotalReadDuration(
    private val repository: HistoryRepository,
) {

    suspend fun await(): Long {
        return repository.getTotalReadDuration()
    }
}
