package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.chapter.repository.ScanlatorPriorityRepository
import dev.zacsweers.metro.Inject

@Inject
class SetScanlatorPriority(
    private val scanlatorPriorityRepository: ScanlatorPriorityRepository,
) {
    suspend fun await(mangaId: Long, orderedScanlators: List<String>) {
        scanlatorPriorityRepository.setPriorities(mangaId, orderedScanlators)
    }

    /** The scanlator dialog's "Reset order": the title is unranked again. */
    suspend fun clear(mangaId: Long) {
        scanlatorPriorityRepository.clear(mangaId)
    }
}
