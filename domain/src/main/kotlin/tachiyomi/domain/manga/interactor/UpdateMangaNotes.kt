// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/interactor/UpdateMangaNotes.kt (modified: no)
package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class UpdateMangaNotes(
    private val mangaRepository: MangaRepository,
) {

    suspend operator fun invoke(mangaId: Long, notes: String): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                notes = notes,
            ),
        )
    }
}
