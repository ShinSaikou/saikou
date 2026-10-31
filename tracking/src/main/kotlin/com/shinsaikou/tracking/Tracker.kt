package com.shinsaikou.tracking

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.shinsaikou.source.Source
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/**
 * One search hit from a tracking service, before it becomes a [Track] row. [score] is the service's
 * mean score for the entry on its own scale, or -1 when it has none, as upstream's search sets it.
 */
data class TrackSearchResult(
    val trackerId: Long,
    val remoteId: Long,
    val title: String,
    val trackingUrl: String,
    val coverUrl: String,
    val summary: String,
    val publishingStatus: String,
    val publishingType: String,
    val startDate: String,
    val totalChapters: Long,
    val authors: List<String>,
    val artists: List<String>,
    val score: Double = -1.0,
)

/**
 * One tracking service. Fresh, and deliberately narrower than upstream's pair of models: there is one
 * [Track] type, the immutable domain row, and every call returns the row to store rather than
 * mutating a bean.
 *
 * Implementations extend [BaseTracker]. Until one is contributed the multibinding set is empty,
 * which is why every screen that would list trackers hides itself when [TrackerManager.trackers]
 * is empty.
 */
interface Tracker {
    val id: Long
    val name: String
    val supportsReadingDates: Boolean
    val supportsPrivateTracking: Boolean

    /**
     * Whether this build can sign in to the service: false when a key the service needs, such as
     * an OAuth client id, was blank at build time. A tracker that is not configured stays in the
     * graph, so restored rows and the backup validator still see its id, but its sign-in is not
     * offered (phase 3 unit T1, challenge 5 T2 item 5).
     */
    val isConfigured: Boolean

    @DrawableRes
    fun getLogo(): Int

    fun getStatusList(): List<Long>

    @StringRes
    fun getStatus(status: Long): Int?

    fun getReadingStatus(): Long

    fun getRereadingStatus(): Long

    fun getCompletionStatus(): Long

    fun getScoreList(): List<String>

    /** The score on a common ten-point scale, so screens can render every service the same way. */
    fun get10PointScore(track: Track): Double

    fun indexToScore(index: Int): Double

    fun displayScore(track: Track): String

    suspend fun search(query: String): List<TrackSearchResult>

    /** Creates or adopts the remote entry for [track]; returns the row to store. */
    suspend fun bind(track: Track, hasReadChapters: Boolean = false): Track

    suspend fun update(track: Track, didReadChapter: Boolean = false): Track

    suspend fun refresh(track: Track): Track

    suspend fun setRemoteStatus(track: Track, status: Long): Track

    suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int): Track

    suspend fun setRemoteScore(track: Track, scoreString: String): Track

    suspend fun setRemoteStartDate(track: Track, epochMillis: Long): Track

    suspend fun setRemoteFinishDate(track: Track, epochMillis: Long): Track

    suspend fun setRemotePrivate(track: Track, private: Boolean): Track

    suspend fun login(username: String, password: String)

    fun logout()

    val isLoggedIn: Boolean

    val isLoggedInFlow: Flow<Boolean>

    /**
     * Set when the service refused the stored sign-in, as a token endpoint's 400 or 401 on refresh
     * does (Q13): the credentials are kept, nothing is pushed, and settings offers "Sign in again".
     * A new sign-in or a sign-out clears it.
     */
    val isAuthExpired: Boolean

    val isAuthExpiredFlow: Flow<Boolean>

    fun getUsername(): String

    fun getDisplayUsername(): String
}

/** A tracker whose remote entry can be removed, not only unlinked (upstream's DeletableTracker). */
interface DeletableTracker : Tracker {
    suspend fun delete(track: Track)
}

/**
 * A tracker that can read the owner's list with its progress, writing nothing (phase 4 unit TC):
 * what "Compare with MyAnimeList" reads. Beside [DeletableTracker], so [Tracker] and its fakes stay
 * as they are.
 */
interface ListComparingTracker : Tracker {

    /**
     * The owner's list entries whose remote id is in [remoteIds], or why the list could not be used.
     * Any failure other than the ones [ListRead.Unreadable] names is thrown.
     */
    suspend fun readList(remoteIds: Set<Long>): ListRead

    /**
     * [track]'s entry as the service holds it now, with the service's dates only. Writes nothing:
     * never [Tracker.refresh], which adds an entry missing from the list back.
     */
    suspend fun readEntry(track: Track): EntryRead
}

/**
 * One entry of the owner's list as [ListComparingTracker.readList] read it: [chaptersRead],
 * the service's chapter count ([totalChapters], 0 when it has none) and the tracker's status id,
 * the rereading status when the entry is being reread.
 */
data class ListEntry(val remoteId: Long, val chaptersRead: Int, val totalChapters: Long, val status: Long)

/** What [ListComparingTracker.readList] answered. */
sealed interface ListRead {

    /** The list's entries for the remote ids asked for, by remote id; an id not on the list is absent. */
    data class Entries(val byRemoteId: Map<Long, ListEntry>) : ListRead

    /**
     * The list answered in a form the comparison cannot trust ([reason] says which, and names no
     * title or token); the caller reads each entry with [ListComparingTracker.readEntry] instead.
     */
    data class Unreadable(val reason: String) : ListRead
}

/** What [ListComparingTracker.readEntry] answered. */
sealed interface EntryRead {

    /** On the owner's list: the stored row with the service's status, chapters, score, dates and count laid over. */
    data class OnList(val track: Track) : EntryRead

    /** Not on the owner's list: the stored row with the service's chapter count. */
    data class OffList(val track: Track) : EntryRead

    /** The service answered 404 for the entry: it no longer has it. */
    data object Gone : EntryRead
}

/** A tracker that binds itself to the manga's own source (upstream's EnhancedTracker). */
interface EnhancedTracker : Tracker {
    fun accept(source: Source): Boolean

    suspend fun match(manga: Manga): TrackSearchResult?

    fun isTrackFrom(track: Track, manga: Manga, source: Source?): Boolean
}
