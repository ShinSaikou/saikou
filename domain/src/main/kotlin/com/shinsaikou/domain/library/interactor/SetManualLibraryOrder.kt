package com.shinsaikou.domain.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.repository.MangaRepository

/** Writes the drag-and-drop order of the library; position i becomes manual_position i + 1. */
@Inject
class SetManualLibraryOrder(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(orderedMangaIds: List<Long>) {
        mangaRepository.setManualPositions(orderedMangaIds)
    }
}
