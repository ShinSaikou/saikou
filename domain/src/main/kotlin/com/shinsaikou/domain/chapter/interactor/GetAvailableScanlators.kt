package com.shinsaikou.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.repository.ChapterRepository

/**
 * Every scanlator group an entry has chapters from, excluded ones included.
 *
 * The chapter list the detail screen reads is already scanlator filtered, so an excluded group's
 * chapters are gone from it. Deriving the list of groups from that list would make an exclusion
 * permanent: the row needed to clear it would have disappeared with the chapters. The unfiltered
 * `SELECT DISTINCT` behind [ChapterRepository.getScanlatorsByMangaIdAsFlow] is the only correct
 * source.
 */
@Inject
class GetAvailableScanlators(
    private val chapterRepository: ChapterRepository,
) {
    fun subscribe(mangaId: Long): Flow<Set<String>> =
        chapterRepository.getScanlatorsByMangaIdAsFlow(mangaId).map { it.toSet() }
}
