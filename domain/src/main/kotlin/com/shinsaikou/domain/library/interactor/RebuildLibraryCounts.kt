package com.shinsaikou.domain.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Settings, Advanced, "Rebuild library counts" (phase 5 unit SC4): the library's chapter counts
 * written again from the chapters, for a summary that drifted. Returns false on failure.
 */
@Inject
class RebuildLibraryCounts(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(): Boolean = mangaRepository.rebuildLibrarySummary()
}
