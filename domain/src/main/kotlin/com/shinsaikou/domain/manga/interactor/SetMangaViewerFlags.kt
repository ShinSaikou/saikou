package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.manga.model.withReaderOrientation
import com.shinsaikou.domain.manga.model.withReadingMode
import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Writes the per-manga reader overrides packed into `mangas.viewer`. Returns the repository's own
 * success flag, the same convention SetMangaChapterFlags uses.
 */
@Inject
class SetMangaViewerFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetReadingMode(id: Long, mode: ReadingMode): Boolean {
        val manga = mangaRepository.getMangaById(id)
        return mangaRepository.update(
            MangaUpdate(id = id, viewerFlags = manga.viewerFlags.withReadingMode(mode)),
        )
    }

    suspend fun awaitSetOrientation(id: Long, orientation: ReaderOrientation): Boolean {
        val manga = mangaRepository.getMangaById(id)
        return mangaRepository.update(
            MangaUpdate(id = id, viewerFlags = manga.viewerFlags.withReaderOrientation(orientation)),
        )
    }
}
