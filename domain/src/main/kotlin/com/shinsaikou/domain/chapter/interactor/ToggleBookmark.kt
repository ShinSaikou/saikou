package com.shinsaikou.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

@Inject
class ToggleBookmark(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun await(chapterId: Long, bookmark: Boolean) {
        try {
            chapterRepository.update(ChapterUpdate(id = chapterId, bookmark = bookmark))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
