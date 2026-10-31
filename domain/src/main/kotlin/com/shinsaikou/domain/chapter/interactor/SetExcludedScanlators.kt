package com.shinsaikou.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetExcludedScanlators(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(mangaId: Long, scanlators: Set<String>) {
        mangaRepository.setExcludedScanlators(mangaId, scanlators)
    }
}
