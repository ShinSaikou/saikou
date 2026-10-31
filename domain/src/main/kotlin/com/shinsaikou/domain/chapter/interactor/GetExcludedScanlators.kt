package com.shinsaikou.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class GetExcludedScanlators(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(mangaId: Long): Set<String> = mangaRepository.getExcludedScanlators(mangaId)

    fun subscribe(mangaId: Long): Flow<Set<String>> = mangaRepository.getExcludedScanlatorsAsFlow(mangaId)
}
