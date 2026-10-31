// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/interactor/ResetViewerFlags.kt (modified: no)
package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaRepository

class ResetViewerFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(): Boolean {
        return mangaRepository.resetViewerFlags()
    }
}
