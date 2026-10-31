package com.shinsaikou.tracking

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.repository.TrackRepository

/**
 * The track sheet's offer when the service is ahead of the app: "MyAnimeList is at chapter N: mark
 * read up to N". It is "Compare with MyAnimeList" on one entry, so the two never disagree on what
 * counts as ahead or which chapters a mark reaches.
 *
 * [find] lays the pair's stored row, as the sheet's refresh on open has just read it from the
 * service, over Compare's rules ([CompareTrackProgress.compare]). [accept] is Compare's "Mark as
 * read" on that entry ([ApplyTrackChoice]): it reads the entry again under the queue's lock and marks
 * only while the service still shows the number the owner was offered. Writes nothing to the service.
 */
@Inject
class RemoteProgressOffer(
    private val mangaRepository: MangaRepository,
    private val trackRepository: TrackRepository,
    private val compareTrackProgress: CompareTrackProgress,
    private val applyTrackChoice: ApplyTrackChoice,
) {

    /**
     * The entry to offer for [mangaId] on [tracker], or null: the tracker cannot compare, the manga
     * has no row for it or is not in the library (a mark there would not sync, as Compare leaves it
     * out), or the service is not ahead with an unread chapter to mark.
     */
    suspend fun find(tracker: Tracker, mangaId: Long): ComparedEntry? {
        if (tracker !is ListComparingTracker) return null
        return withIOContext {
            val track = trackRepository.getTracksByMangaId(mangaId).firstOrNull { it.trackerId == tracker.id }
                ?: return@withIOContext null
            val manga = mangaRepository.getMangaById(mangaId).takeIf { it.favorite } ?: return@withIOContext null
            val remote = RemoteRead(mapOf(mangaId to RemoteAnswer.Listed(track.toListEntry())))
            compareTrackProgress.compare(tracker, listOf(CompareLink(manga, track)), remote).entries.single()
                .takeIf { it.section == CompareSection.REMOTE_AHEAD && CompareChoice.MARK_READ in it.choices }
        }
    }

    /** Marks [entry]'s chapters read up to the service's number, as Compare's Apply does for one row. */
    suspend fun accept(tracker: ListComparingTracker, entry: ComparedEntry): ApplyResult =
        applyTrackChoice.run(tracker, listOf(ApplyRow(entry, CompareChoice.MARK_READ))).results.single()
}
