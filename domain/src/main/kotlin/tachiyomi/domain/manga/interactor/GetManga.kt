// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/interactor/GetManga.kt (modified: yes)
package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Modified (phase 4 unit BF): [subscribeOrNull] emits null while no row has the id, for a collector
 * whose entry can leave while it lives (ruling R119 item 1). [subscribe] by id is upstream's, whose
 * collector throws when the row is absent.
 */
class GetManga(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(id: Long): Manga? {
        return try {
            mangaRepository.getMangaById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    suspend fun subscribe(id: Long): Flow<Manga> {
        return mangaRepository.getMangaByIdAsFlow(id)
    }

    fun subscribeOrNull(id: Long): Flow<Manga?> {
        return mangaRepository.getMangaByIdOrNullAsFlow(id)
    }

    fun subscribe(url: String, sourceId: Long): Flow<Manga?> {
        return mangaRepository.getMangaByUrlAndSourceIdAsFlow(url, sourceId)
    }
}
