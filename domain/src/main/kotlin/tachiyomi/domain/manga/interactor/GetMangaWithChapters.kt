// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/interactor/GetMangaWithChapters.kt (modified: yes)
package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Modified (phase 4 unit missing-entry): [subscribe] emits null while no row has the id, where
 * upstream's threw from the collector ("ResultSet returned null for mangas.sq:getMangaById") and took
 * the process with it. An entry intent can outlive its entry, and the manga screen's row can be
 * deleted while the screen is open.
 */
class GetMangaWithChapters(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    suspend fun subscribe(id: Long, applyScanlatorFilter: Boolean = false): Flow<Pair<Manga, List<Chapter>>?> {
        return combine(
            mangaRepository.getMangaByIdOrNullAsFlow(id),
            chapterRepository.getChapterByMangaIdAsFlow(id, applyScanlatorFilter),
        ) { manga, chapters ->
            manga?.let { Pair(it, chapters) }
        }
    }

    suspend fun awaitManga(id: Long): Manga {
        return mangaRepository.getMangaById(id)
    }

    suspend fun awaitChapters(id: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        return chapterRepository.getChapterByMangaId(id, applyScanlatorFilter)
    }
}
