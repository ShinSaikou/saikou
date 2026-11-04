package com.shinsaikou.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.interactor.GetLibraryManga

/**
 * Puts every library entry with a chosen interval back on its estimate, the write an entry's
 * Automatic chip makes. The library is read once and only those entries are written, so what this
 * costs follows how many intervals were chosen.
 */
@Inject
class ClearCustomIntervals(
    private val getLibraryManga: GetLibraryManga,
    private val updateManga: UpdateManga,
) {

    suspend fun await() {
        getLibraryManga.await()
            .filter { it.manga.fetchInterval < 0 }
            .forEach { updateManga.awaitUpdateCustomInterval(it.manga, 0) }
    }
}
