package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import com.shinsaikou.domain.chapter.repository.ScanlatorPriorityRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class GetScanlatorPriority(
    private val scanlatorPriorityRepository: ScanlatorPriorityRepository,
) {
    fun subscribe(mangaId: Long): Flow<List<ScanlatorPriority>> =
        scanlatorPriorityRepository.subscribeByMangaId(mangaId)

    /**
     * The title's ranking as `removeDuplicates` takes it, group to rank, empty when the title is
     * unranked. The rank is the stored priority as it is: the top group is 0, and a group with no
     * row is not in the map, which ranks it after every group that is.
     */
    suspend fun await(mangaId: Long): Map<String, Long> =
        scanlatorPriorityRepository.getByMangaId(mangaId).associate { it.scanlator to it.priority }
}
