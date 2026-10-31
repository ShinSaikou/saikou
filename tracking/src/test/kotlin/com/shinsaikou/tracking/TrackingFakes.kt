package com.shinsaikou.tracking

import com.shinsaikou.domain.database.TransactionRunner
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.time.ZoneOffset
import java.util.Date

/**
 * A tracker shaped like upstream's `DummyTracker` (`app/src/main/java/eu/kanade/test/DummyTracker.kt`),
 * in test sources only (R39): its state is constructor values, and it records what it was asked.
 * [refreshed] and [updated] decide what the service answers; either may throw, and
 * [expireOnFailure] sets [isAuthExpired] on the way out as a token endpoint's refusal does.
 * [beforeUpdate] runs inside [update] before the answer, so a test can hold a push on the wire.
 * Every `setRemote*` is recorded and throws [remoteFailure] when set; none stores a row, where
 * `BaseTracker`'s store the service's answer.
 */
open class FakeTracker(
    override val id: Long,
    override val name: String = "Fake tracker $id",
    override val supportsReadingDates: Boolean = false,
    override val supportsPrivateTracking: Boolean = false,
    override val isConfigured: Boolean = true,
    override var isLoggedIn: Boolean = true,
    override var isAuthExpired: Boolean = false,
    val valStatuses: List<Long> = (1L..6L).toList(),
    val valReadingStatus: Long = 1L,
    val valRereadingStatus: Long = 6L,
    val valCompletionStatus: Long = 2L,
    val valScoreList: List<String> = (0..10).map(Int::toString),
    var refreshed: (Track) -> Track = { it },
    var updated: (Track) -> Track = { it },
    var bound: (Track, Boolean) -> Track = { track, _ -> track },
    var expireOnFailure: Boolean = false,
    var beforeUpdate: suspend (Track) -> Unit = {},
) : Tracker {

    val refreshes = mutableListOf<Track>()
    val updates = mutableListOf<Pair<Track, Boolean>>()
    val remoteLastChapterReads = mutableListOf<Pair<Track, Int>>()
    val remoteStartDates = mutableListOf<Pair<Track, Long>>()
    val remoteStatuses = mutableListOf<Pair<Track, Long>>()
    val remoteScores = mutableListOf<Pair<Track, String>>()
    val remoteFinishDates = mutableListOf<Pair<Track, Long>>()
    val remotePrivates = mutableListOf<Pair<Track, Boolean>>()
    var remoteFailure: Exception? = null

    override val isLoggedInFlow: Flow<Boolean> get() = flowOf(isLoggedIn)

    override val isAuthExpiredFlow: Flow<Boolean> get() = flowOf(isAuthExpired)

    override fun getLogo(): Int = 0

    override fun getStatusList(): List<Long> = valStatuses

    override fun getStatus(status: Long): Int? = null

    override fun getReadingStatus(): Long = valReadingStatus

    override fun getRereadingStatus(): Long = valRereadingStatus

    override fun getCompletionStatus(): Long = valCompletionStatus

    override fun getScoreList(): List<String> = valScoreList

    override fun get10PointScore(track: Track): Double = track.score

    override fun indexToScore(index: Int): Double = getScoreList()[index].toDouble()

    override fun displayScore(track: Track): String = track.score.toString()

    override suspend fun search(query: String): List<TrackSearchResult> = emptyList()

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = bound(track, hasReadChapters)

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        updates += track to didReadChapter
        beforeUpdate(track)
        return answer { updated(track) }
    }

    override suspend fun refresh(track: Track): Track {
        refreshes += track
        return answer { refreshed(track) }
    }

    override suspend fun setRemoteStatus(track: Track, status: Long): Track {
        remoteStatuses += track to status
        remoteFailure?.let { throw it }
        return track.copy(status = status)
    }

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int): Track {
        remoteLastChapterReads += track to chapterNumber
        remoteFailure?.let { throw it }
        return track.copy(lastChapterRead = chapterNumber.toDouble())
    }

    override suspend fun setRemoteScore(track: Track, scoreString: String): Track {
        remoteScores += track to scoreString
        remoteFailure?.let { throw it }
        return track.copy(score = scoreString.toDoubleOrNull() ?: track.score)
    }

    override suspend fun setRemoteStartDate(track: Track, epochMillis: Long): Track {
        remoteStartDates += track to epochMillis
        remoteFailure?.let { throw it }
        return track.copy(startDate = epochMillis)
    }

    override suspend fun setRemoteFinishDate(track: Track, epochMillis: Long): Track {
        remoteFinishDates += track to epochMillis
        remoteFailure?.let { throw it }
        return track.copy(finishDate = epochMillis)
    }

    override suspend fun setRemotePrivate(track: Track, private: Boolean): Track {
        remotePrivates += track to private
        remoteFailure?.let { throw it }
        return track.copy(private = private)
    }

    override suspend fun login(username: String, password: String) = Unit

    override fun logout() {
        isLoggedIn = false
    }

    override fun getUsername(): String = "username"

    override fun getDisplayUsername(): String = "UserName"

    private inline fun answer(block: () -> Track): Track = try {
        block()
    } catch (e: Exception) {
        if (expireOnFailure) isAuthExpired = true
        throw e
    }
}

/** A [TrackerSignInNotifier] that records the tracker ids it was told about, in order. */
class FakeSignInNotifier : TrackerSignInNotifier {
    val expired = mutableListOf<Long>()
    val signedIn = mutableListOf<Long>()

    override fun expired(tracker: Tracker) {
        expired += tracker.id
    }

    override fun signedIn(tracker: Tracker) {
        signedIn += tracker.id
    }
}

/**
 * A [FakeTracker] whose entries can be removed from the service, as MyAnimeList's (upstream's
 * `DeletableTracker`). [deleted] runs inside [delete] and may throw; a test reads the local state
 * there to see what had happened before the service was asked.
 */
class FakeDeletableTracker(
    id: Long,
    var deleted: suspend (Track) -> Unit = {},
) : FakeTracker(id), DeletableTracker {

    val deletes = mutableListOf<Track>()

    override suspend fun delete(track: Track) {
        deletes += track
        deleted(track)
    }
}

/** Track rows in a map by id, as `manga_sync` holds them; an insert of an existing id replaces it. */
class FakeTrackRepository(vararg tracks: Track) : TrackRepository {

    val rows: MutableMap<Long, Track> = tracks.associateByTo(linkedMapOf()) { it.id }
    val inserted = mutableListOf<Track>()

    override suspend fun getTrackById(id: Long): Track? = rows[id]

    override suspend fun getTracksByMangaId(mangaId: Long): List<Track> = rows.values.filter { it.mangaId == mangaId }

    override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(rows.values.toList())

    override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
        flowOf(rows.values.filter { it.mangaId == mangaId })

    override suspend fun insert(track: Track) {
        inserted += track
        rows[track.id] = track
    }

    override suspend fun insertAll(tracks: List<Track>) = tracks.forEach { insert(it) }

    val rebound = mutableListOf<Track>()

    override suspend fun rebind(track: Track) {
        rebound += track
        delete(track.mangaId, track.trackerId)
        rows[track.id] = track
    }

    override suspend fun insertIfBoundTo(track: Track, boundRemoteId: Long): Boolean {
        val bound = rows.values.firstOrNull { it.mangaId == track.mangaId && it.trackerId == track.trackerId }
        if (bound?.remoteId != boundRemoteId) return false
        insert(track)
        return true
    }

    override suspend fun deleteIfBoundTo(mangaId: Long, trackerId: Long, boundRemoteId: Long): Boolean {
        val bound = rows.values.firstOrNull { it.mangaId == mangaId && it.trackerId == trackerId }
        if (bound?.remoteId != boundRemoteId) return false
        delete(mangaId, trackerId)
        return true
    }

    private fun delete(mangaId: Long, trackerId: Long) {
        rows.values.removeAll { it.mangaId == mangaId && it.trackerId == trackerId }
    }
}

/**
 * Track rows as `manga_sync` stores them. Its `insert` is a plain `INSERT` that lands on
 * `UNIQUE (manga_id, sync_id) ON CONFLICT REPLACE`
 * (`data/src/main/sqldelight/tachiyomi/data/manga_sync.sq` line 24), so an insert of a pair already
 * bound deletes the bound row and adds the new one under a fresh `_id`, whatever id the row it was
 * given carries; `_id` is an `INTEGER PRIMARY KEY`, so the fresh id is above every id used so far.
 * `:data`'s `RepositoryTest` shows the id change on the real schema. [FakeTrackRepository] keeps a
 * row's id instead, which is what every test not about the replacement needs.
 */
class ReplacingTrackRepository(vararg tracks: Track) : TrackRepository {

    val rows: MutableMap<Long, Track> = tracks.associateByTo(linkedMapOf()) { it.id }
    private var lastId = rows.keys.maxOrNull() ?: 0L

    override suspend fun getTrackById(id: Long): Track? = rows[id]

    override suspend fun getTracksByMangaId(mangaId: Long): List<Track> = rows.values.filter { it.mangaId == mangaId }

    override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(rows.values.toList())

    override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
        flowOf(rows.values.filter { it.mangaId == mangaId })

    override suspend fun insert(track: Track) {
        rows.values.removeAll { it.mangaId == track.mangaId && it.trackerId == track.trackerId }
        val id = ++lastId
        rows[id] = track.copy(id = id)
    }

    override suspend fun insertAll(tracks: List<Track>) = tracks.forEach { insert(it) }

    /** A delete and an insert of the pair, one transaction on the real schema. */
    override suspend fun rebind(track: Track) {
        delete(track.mangaId, track.trackerId)
        insert(track)
    }

    /** `manga_sync.sq`'s `insertIfBoundTo`: an insert taken only while the pair names [boundRemoteId]. */
    override suspend fun insertIfBoundTo(track: Track, boundRemoteId: Long): Boolean {
        val bound = rows.values.firstOrNull { it.mangaId == track.mangaId && it.trackerId == track.trackerId }
        if (bound?.remoteId != boundRemoteId) return false
        insert(track)
        return true
    }

    /** `manga_sync.sq`'s `deleteIfBoundTo`: a delete taken only while the pair names [boundRemoteId]. */
    override suspend fun deleteIfBoundTo(mangaId: Long, trackerId: Long, boundRemoteId: Long): Boolean {
        val bound = rows.values.firstOrNull { it.mangaId == mangaId && it.trackerId == trackerId }
        if (bound?.remoteId != boundRemoteId) return false
        delete(mangaId, trackerId)
        return true
    }

    private fun delete(mangaId: Long, trackerId: Long) {
        rows.values.removeAll { it.mangaId == mangaId && it.trackerId == trackerId }
    }
}

fun track(
    id: Long,
    trackerId: Long,
    mangaId: Long = MANGA_ID,
    lastChapterRead: Double = 0.0,
    totalChapters: Long = 0,
    status: Long = 1,
    startDate: Long = 0,
) = Track(
    id = id,
    mangaId = mangaId,
    trackerId = trackerId,
    remoteId = 1000 + id,
    libraryId = null,
    title = "Entry $id",
    lastChapterRead = lastChapterRead,
    totalChapters = totalChapters,
    status = status,
    score = 0.0,
    remoteUrl = "https://tracker.example.invalid/entry/$id",
    startDate = startDate,
    finishDate = 0,
    private = false,
)

const val MANGA_ID = 42L

/**
 * Runs [block] with the JVM's default zone at the fixed [offset] (`+09:00`, `-05:00`), as a device
 * east or west of UTC runs, and puts the old zone back. `TimeZone.currentSystemDefault()` and a
 * `SimpleDateFormat` made inside [block] both read it.
 */
inline fun <T> inZone(offset: String, block: () -> T): T {
    val previous = java.util.TimeZone.getDefault()
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.of(offset)))
    try {
        return block()
    } finally {
        java.util.TimeZone.setDefault(previous)
    }
}

/** The stored form of [date] (`yyyy-MM-dd`) on a device at [offset]: that day's midnight there. */
fun localMidnight(date: String, offset: String): Long =
    LocalDate.parse(date).atStartOfDayIn(TimeZone.of(offset)).toEpochMilliseconds()

/**
 * A preference store over one map, as the platform's is over one file: every preference with a key
 * reads what any other wrote, and [changes] follows the map. `InMemoryPreferenceStore` keeps each
 * value inside its preference, so a second instance over the same data sees none of it.
 */
class MapPreferenceStore(val values: MutableMap<String, Any> = mutableMapOf()) : PreferenceStore {

    private val version = MutableStateFlow(0)

    override fun getString(key: String, defaultValue: String): Preference<String> =
        MapPreference(key, defaultValue, { it as String }, { it })

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        MapPreference(key, defaultValue, { it as Long }, { it })

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        MapPreference(key, defaultValue, { it as Int }, { it })

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        MapPreference(key, defaultValue, { it as Float }, { it })

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        MapPreference(key, defaultValue, { it as Boolean }, { it })

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = unsupported()

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = MapPreference(key, defaultValue, { deserializer(it as String) }, serializer)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = unsupported()

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = unsupported()

    override fun getAll(): Map<String, *> = values.toMap()

    /** Writes as a committing writer would, into the same map. */
    fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        version.value++
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by the tracking tests")

    private inner class MapPreference<T>(
        private val key: String,
        private val defaultValue: T,
        private val read: (Any) -> T,
        private val write: (T) -> Any,
    ) : Preference<T> {
        override fun key(): String = key
        override fun get(): T = values[key]?.let(read) ?: defaultValue
        override fun set(value: T) {
            values[key] = write(value)
            version.value++
        }
        override fun isSet(): Boolean = key in values
        override fun delete() {
            values.remove(key)
            version.value++
        }
        override fun defaultValue(): T = defaultValue
        override fun changes(): Flow<T> = version.map { get() }.distinctUntilChanged()
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = MutableStateFlow(get())
    }
}

/**
 * A library for the compare tests (phase 4 unit TC): manga, their chapters and history rows in
 * lists, behind mocked repositories that read them as the real ones do. [chapterRepository] returns
 * a manga's live rows (no `deletedAt`), every scanlator's, and applies `updateAll` with the
 * coalescing rule (a null field leaves its column); [chapterFailure] makes its reads throw. Each
 * read of a manga or its chapters and each `updateAll` is recorded in [calls] with whether
 * [transactions] had one open.
 */
class FakeLibrary(
    private val transactions: RecordingTransactionRunner? = null,
    private val calls: MutableList<Pair<String, Boolean>> = mutableListOf(),
) {

    val manga = linkedMapOf<Long, Manga>()
    val chapters = mutableListOf<Chapter>()
    val history = mutableListOf<History>()
    val chapterUpdates = mutableListOf<List<ChapterUpdate>>()
    var chapterFailure: Exception? = null

    /** Records [call] with whether [transactions] had a transaction open. */
    private fun record(call: String) {
        calls += call to (transactions?.open == true)
    }

    val mangaRepository = mockk<MangaRepository>().also { repository ->
        coEvery { repository.getFavorites() } answers { manga.values.filter { it.favorite } }
        coEvery { repository.getMangaById(any()) } answers {
            record("manga")
            manga.getValue(firstArg())
        }
    }

    val chapterRepository = mockk<ChapterRepository>().also { repository ->
        coEvery { repository.getChapterByMangaId(any(), any()) } answers {
            record("chapters")
            chapterFailure?.let { throw it }
            chapters.filter { it.mangaId == firstArg<Long>() && it.deletedAt == null }
        }
        coEvery { repository.updateAll(any()) } answers {
            record("updateAll")
            val updates = firstArg<List<ChapterUpdate>>()
            chapterUpdates += updates
            updates.forEach { update ->
                val index = chapters.indexOfFirst { it.id == update.id }
                if (index >= 0) {
                    val chapter = chapters[index]
                    chapters[index] = chapter.copy(
                        read = update.read ?: chapter.read,
                        bookmark = update.bookmark ?: chapter.bookmark,
                        lastPageRead = update.lastPageRead ?: chapter.lastPageRead,
                        chapterNumber = update.chapterNumber ?: chapter.chapterNumber,
                    )
                }
            }
        }
    }

    val historyRepository = mockk<HistoryRepository>().also { repository ->
        coEvery { repository.getHistoryByMangaId(any()) } answers {
            val ids = chapters.filter { it.mangaId == firstArg<Long>() }.map { it.id }.toSet()
            history.filter { it.chapterId in ids }
        }
    }

    val getFavorites = GetFavorites(mangaRepository)

    fun addManga(id: Long, title: String = "Title $id", favorite: Boolean = true): Manga =
        Manga.create().copy(id = id, title = title, favorite = favorite).also { manga[id] = it }

    /** A chapter of [mangaId] numbered [number], with the next free id unless [id] is given. */
    fun addChapter(
        mangaId: Long,
        number: Double,
        read: Boolean = false,
        scanlator: String? = null,
        lastPageRead: Long = 0,
        id: Long = (chapters.maxOfOrNull { it.id } ?: 0) + 1,
    ): Chapter = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        chapterNumber = number,
        read = read,
        scanlator = scanlator,
        lastPageRead = lastPageRead,
        name = "Chapter $number",
        url = "/chapter/$id",
    ).also { chapters += it }

    /** Chapters of [mangaId] numbered [numbers], read up to and including [readThrough]. */
    fun addChapters(mangaId: Long, numbers: Iterable<Number>, readThrough: Double = -1.0): List<Chapter> =
        numbers.map { addChapter(mangaId, it.toDouble(), read = it.toDouble() <= readThrough) }

    fun addHistory(chapterId: Long) {
        history += History(id = history.size + 1L, chapterId = chapterId, readAt = Date(1_000L), readDuration = 60_000L)
    }

    fun chapter(id: Long): Chapter = chapters.single { it.id == id }
}

/**
 * A transaction boundary that counts the transactions opened and knows whether one is open now
 * (phase 4 unit TR's, moved here from `AddTracksTest` for unit TC).
 */
class RecordingTransactionRunner : TransactionRunner {
    var opened = 0
    var open = false

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        opened++
        open = true
        try {
            return block()
        } finally {
            open = false
        }
    }
}

/**
 * [delegate] with each read of a pair and each write recorded in [calls], with whether
 * [transactions] had a transaction open (TR's shape for `AddTracksTest`, phase 4 unit TC).
 */
class RecordingTrackRepository(
    private val delegate: TrackRepository,
    private val transactions: RecordingTransactionRunner,
    private val calls: MutableList<Pair<String, Boolean>>,
) : TrackRepository by delegate {

    override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
        calls += "read" to transactions.open
        return delegate.getTracksByMangaId(mangaId)
    }

    override suspend fun insert(track: Track) {
        calls += "insert" to transactions.open
        delegate.insert(track)
    }

    override suspend fun insertIfBoundTo(track: Track, boundRemoteId: Long): Boolean {
        calls += "insertIfBoundTo" to transactions.open
        return delegate.insertIfBoundTo(track, boundRemoteId)
    }

    override suspend fun deleteIfBoundTo(mangaId: Long, trackerId: Long, boundRemoteId: Long): Boolean {
        calls += "deleteIfBoundTo" to transactions.open
        return delegate.deleteIfBoundTo(mangaId, trackerId, boundRemoteId)
    }

    override suspend fun rebind(track: Track) {
        calls += "rebind" to transactions.open
        delegate.rebind(track)
    }
}
