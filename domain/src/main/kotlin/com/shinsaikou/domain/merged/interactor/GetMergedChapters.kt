package com.shinsaikou.domain.merged.interactor

import com.shinsaikou.domain.merged.MergedChapters
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

/**
 * A group's chapters as one list: [MergedChapters.visible] with the sources installed now, which is
 * the one thing the pure function cannot know.
 */
@Inject
class GetMergedChapters(
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
) {

    /** Every member's chapters through [visible], in no order: each caller sorts for its own list. */
    suspend fun await(group: MergedGroup, applyScanlatorFilter: Boolean = true): List<Chapter> =
        visible(group.members.flatMap { chapterRepository.getChapterByMangaId(it.mangaId, applyScanlatorFilter) }, group)

    fun visible(chapters: List<Chapter>, group: MergedGroup): List<Chapter> =
        MergedChapters.visible(chapters, group, installedSources(group))

    /** Whether [sourceId] serves chapters now. A source no provider loads is ranked last. */
    fun isInstalled(sourceId: Long): Boolean = sourceManager.get(sourceId).let { it != null && it !is StubSource }

    private fun installedSources(group: MergedGroup): Set<Long> =
        group.members.mapTo(HashSet()) { it.source }.filterTo(HashSet(), ::isInstalled)
}
