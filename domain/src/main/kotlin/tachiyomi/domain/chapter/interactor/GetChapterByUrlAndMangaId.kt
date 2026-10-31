// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/chapter/interactor/GetChapterByUrlAndMangaId.kt (modified: no)
package tachiyomi.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

class GetChapterByUrlAndMangaId(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(url: String, sourceId: Long): Chapter? {
        return try {
            chapterRepository.getChapterByUrlAndMangaId(url, sourceId)
        } catch (e: Exception) {
            null
        }
    }
}
