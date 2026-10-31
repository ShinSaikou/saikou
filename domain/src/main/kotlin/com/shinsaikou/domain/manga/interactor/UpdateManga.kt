package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.source.model.MangaInfo
import dev.zacsweers.metro.Inject
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Clock

/** Every write to a manga row that is not a flag change goes through here. */
@Inject
class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    /**
     * Applies details fetched from a source. The title is replaced only when the manga is not a
     * favourite or [updateTitles] ("Update library manga titles to match source") is on, as upstream's
     * UpdateMangaFromRemote gates it: a manual refresh does not override the setting, so
     * [manualFetch] plays no part in the title. Cover, credits, description, genre, status, update
     * strategy and memo are replaced; the row becomes initialized, and coverLastModified is bumped
     * when the cover url changed.
     */
    suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remote: MangaInfo,
        manualFetch: Boolean,
        updateTitles: Boolean,
    ): Boolean {
        val remoteTitle = remote.title.takeIf { it.isNotBlank() }
        val title = if (remoteTitle != null && (!localManga.favorite || updateTitles)) {
            remoteTitle
        } else {
            null
        }

        val coverLastModified = if (remote.thumbnailUrl != null && remote.thumbnailUrl != localManga.thumbnailUrl) {
            Clock.System.now().toEpochMilliseconds()
        } else {
            null
        }

        return mangaRepository.update(
            MangaUpdate(
                id = localManga.id,
                title = title,
                coverLastModified = coverLastModified,
                author = remote.author ?: localManga.author,
                artist = remote.artist ?: localManga.artist,
                description = remote.description ?: localManga.description,
                genre = remote.genres() ?: localManga.genre,
                thumbnailUrl = remote.thumbnailUrl ?: localManga.thumbnailUrl,
                status = remote.status.code.toLong(),
                updateStrategy = remote.updateStrategy,
                initialized = true,
                memo = remote.memo,
            ),
        )
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        dateTime: LocalDateTime = Clock.System.now().toLocalDateTime(timeZone),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime.date, timeZone),
    ): Boolean {
        return mangaRepository.update(fetchInterval.toMangaUpdate(manga, dateTime, timeZone, window))
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, lastUpdate = Clock.System.now().toEpochMilliseconds()),
        )
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(id = mangaId, coverLastModified = Clock.System.now().toEpochMilliseconds()),
        )
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = if (favorite) Clock.System.now().toEpochMilliseconds() else 0L
        return mangaRepository.update(MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded))
    }

    /**
     * [awaitUpdateFavorite] for several entries in one transaction, as browse's selection adds them,
     * so a hundred entries are one write and one library refresh rather than a hundred. They share
     * one date added.
     */
    suspend fun awaitUpdateFavorites(mangaIds: List<Long>, favorite: Boolean): Boolean {
        val dateAdded = if (favorite) Clock.System.now().toEpochMilliseconds() else 0L
        return mangaRepository.updateAll(mangaIds.map { MangaUpdate(id = it, favorite = favorite, dateAdded = dateAdded) })
    }
}
