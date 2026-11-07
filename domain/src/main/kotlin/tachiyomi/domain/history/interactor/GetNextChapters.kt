// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/history/interactor/GetNextChapters.kt (modified: yes)
// Modified: a merged entry's next chapters come from its merged list (R163).
package tachiyomi.domain.history.interactor

import com.shinsaikou.domain.merged.MergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import kotlin.math.max

class GetNextChapters(
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
    private val historyRepository: HistoryRepository,
    private val getMergedGroup: GetMergedGroup,
    private val getMergedChapters: GetMergedChapters,
) {

    suspend fun await(onlyUnread: Boolean = true): List<Chapter> {
        val history = historyRepository.getLastHistory() ?: return emptyList()
        return await(history.mangaId, history.chapterId, onlyUnread)
    }

    suspend fun await(mangaId: Long, onlyUnread: Boolean = true): List<Chapter> {
        val manga = getManga.await(mangaId) ?: return emptyList()
        val group = getMergedGroup.await(mangaId)
        val chapters = if (group == null) {
            getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
                .sortedWith(getChapterSort(manga, sortDescending = false))
        } else {
            val main = getManga.await(group.mainMangaId) ?: manga
            getMergedChapters.await(group).sortedWith(MergedChapters.sort(main, sortDescending = false))
        }

        return if (onlyUnread) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }
    }

    suspend fun await(
        mangaId: Long,
        fromChapterId: Long,
        onlyUnread: Boolean = true,
    ): List<Chapter> {
        val chapters = await(mangaId, onlyUnread)
        val currChapterIndex = chapters.indexOfFirst { it.id == fromChapterId }
        val nextChapters = chapters.subList(max(0, currChapterIndex), chapters.size)

        if (onlyUnread) {
            return nextChapters
        }

        // The "next chapter" is either:
        // - The current chapter if it isn't completely read
        // - The chapters after the current chapter if the current one is completely read
        val fromChapter = chapters.getOrNull(currChapterIndex)
        return if (fromChapter != null && !fromChapter.read) {
            nextChapters
        } else {
            nextChapters.drop(1)
        }
    }
}
