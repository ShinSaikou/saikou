package com.shinsaikou.tracking

import tachiyomi.domain.track.model.Track
import java.util.Collections

/**
 * [FakeOAuthTracker] that can compare the owner's list with the app, as MyAnimeList does (phase 4
 * unit TC), in test sources only (R39). [list] answers `readList` and [entry] answers `readEntry`;
 * both may throw or suspend, and every call is kept. [sent] answers a send of a chapter number, by
 * default [base]'s, which keeps it in `remoteEdits`. Its statuses are MyAnimeList's ids: reading 1,
 * completed 2, rereading 7, so a list entry at status 1 is no reread.
 */
class FakeComparingTracker(
    val base: FakeOAuthTracker = FakeOAuthTracker(id = 1L, name = "MyAnimeList", loggedIn = true),
) : OAuthTracker by base, ListComparingTracker {

    var list: suspend (Set<Long>) -> ListRead = { ListRead.Entries(emptyMap()) }
    var entry: suspend (Track) -> EntryRead = { EntryRead.Gone }

    var sent: suspend (Track, Int) -> Track = { track, number -> base.setRemoteLastChapterRead(track, number) }

    val listReads: MutableList<Set<Long>> = Collections.synchronizedList(mutableListOf())
    val entryReads: MutableList<Track> = Collections.synchronizedList(mutableListOf())

    override suspend fun readList(remoteIds: Set<Long>): ListRead {
        listReads += remoteIds
        return list(remoteIds)
    }

    override suspend fun readEntry(track: Track): EntryRead {
        entryReads += track
        return entry(track)
    }

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int): Track = sent(track, chapterNumber)

    override fun getReadingStatus(): Long = 1L

    override fun getCompletionStatus(): Long = 2L

    override fun getRereadingStatus(): Long = 7L
}
