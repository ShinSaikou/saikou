package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.source.toChapterInfo
import com.shinsaikou.domain.source.toMangaInfo
import com.shinsaikou.source.Source
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Clock

/**
 * One entry's details and chapters fetched from its source and stored (phase 4 unit MA), after
 * upstream's `UpdateMangaFromRemote`: the manga screen's refresh, and migration's re-fetch of its
 * target, call it.
 *
 * [fetchDetails] and [fetchChapters] are upstream's two flags: with one on, one
 * [Source.getMangaUpdate] call asks for that half; with both on, the details come first and the
 * chapters are asked with the row as the details left it, because the chapter sanitizer strips the
 * title from each chapter name and a stale title leaves the prefix behind. Each call carries the
 * entry's stored chapters, in source order, as upstream's call does: the extension ABI takes them as
 * the entry's existing chapters. [manualFetch] is upstream's too: it counts the fetch as the owner's
 * for the fetch interval.
 *
 * A failure is thrown. The new chapters are returned and handed to nothing: only the manga screen's
 * refresh the owner asked for gives them to auto-download (R97 item 3), and it does that itself.
 */
@Inject
class RefreshMangaFromSource(
    private val getMangaWithChapters: GetMangaWithChapters,
    private val updateManga: UpdateManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val fetchInterval: FetchInterval,
    private val libraryPreferences: LibraryPreferences,
    private val dispatchers: Dispatchers,
) {

    /**
     * [manga] is the row as stored when the fetch was asked for. Returns the row the chapters were
     * asked with (as the details left it when [fetchDetails] is on), and the chapters the fetch
     * inserted, empty when [fetchChapters] is off.
     */
    suspend operator fun invoke(
        source: Source,
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
    ): Result = withContext(dispatchers.io) {
        if (!fetchDetails && !fetchChapters) return@withContext Result(manga, emptyList())
        // Read once: a details update leaves the chapters as they are.
        val stored = getMangaWithChapters.awaitChapters(manga.id)
            .sortedBy { it.sourceOrder }
            .map { it.toChapterInfo() }
        var current = manga
        if (fetchDetails) {
            val details = checkNotNull(
                source.getMangaUpdate(manga.toMangaInfo(), stored, fetchDetails = true, fetchChapters = false).manga,
            )
            updateManga.awaitUpdateFromSource(
                localManga = manga,
                remote = details,
                manualFetch = manualFetch,
                updateTitles = libraryPreferences.updateMangaTitles.get(),
            )
            current = getMangaWithChapters.awaitManga(manga.id)
        }
        if (!fetchChapters) return@withContext Result(current, emptyList())
        // The window decides which fetch dates count as "new" for the updates tab, so it is taken
        // once per fetch rather than per chapter.
        val zone = TimeZone.currentSystemDefault()
        val window = fetchInterval.getWindow(Clock.System.now().toLocalDateTime(zone).date, zone)
        val chapters = checkNotNull(
            source.getMangaUpdate(current.toMangaInfo(), stored, fetchDetails = false, fetchChapters = true).chapters,
        )
        Result(
            manga = current,
            newChapters = syncChaptersWithSource.await(
                rawSourceChapters = chapters,
                manga = current,
                manualFetch = manualFetch,
                fetchWindow = window,
            ),
        )
    }

    data class Result(val manga: Manga, val newChapters: List<Chapter>)
}
