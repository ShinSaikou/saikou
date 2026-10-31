package com.shinsaikou.tracking

import com.shinsaikou.tracking.DelayedTrackingStore.DelayedTrackingItem
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.math.floor

/**
 * The reads of "Compare with MyAnimeList" (phase 4 unit TC), in the three steps the screen runs:
 * [links] before any network call, [readRemote] (the owner's list, or one entry at a time when the
 * list cannot be used, and a confirmation of each linked entry the list lacks) beside the sync with
 * the other device, and [compare], the local pass once both are done. Nothing here writes, in the
 * library or on the service, and no lock is taken: what was read is checked again under the lock
 * when the owner applies a choice ([ApplyTrackChoice]).
 */
@Inject
class CompareTrackProgress(
    private val getFavorites: GetFavorites,
    private val trackRepository: TrackRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val store: DelayedTrackingStore,
) {

    /**
     * The library entries tracked on [trackerId], with the pair's stored row. An entry outside the
     * library is left out: a chapter written there never enters the sync log, whose trigger requires
     * a favourite, so a choice on it would hold on one device only.
     */
    suspend fun links(trackerId: Long): List<CompareLink> = withIOContext {
        val favorites = getFavorites.await().associateBy { it.id }
        trackRepository.getTracksAsFlow().first()
            .filter { it.trackerId == trackerId }
            .mapNotNull { track -> favorites[track.mangaId]?.let { CompareLink(it, track) } }
    }

    /**
     * What [tracker] holds for each of [links]. The list is read once; each linked entry it lacks is
     * read on its own before it is called missing, since the list can hide adult entries or be
     * staler than the entry. When the list cannot be used, every linked entry is read on its own
     * instead. [onChecking] is told how many entries are about to be read one at a time.
     *
     * A failure of one entry ([CompareFailure.RowOnly]) is that entry's answer; any other failure,
     * the list read's included, is thrown, and nothing read is returned.
     */
    suspend fun readRemote(
        tracker: ListComparingTracker,
        links: List<CompareLink>,
        onChecking: (Int) -> Unit = {},
    ): RemoteRead {
        val read = tracker.readList(links.mapTo(mutableSetOf()) { it.track.remoteId })
        val listed = when (read) {
            is ListRead.Entries -> read.byRemoteId
            is ListRead.Unreadable -> {
                logcat(LogPriority.INFO) {
                    "Compare: the list read was unusable (${read.reason}); read ${links.size} entries one at a time"
                }
                emptyMap()
            }
        }
        val alone = links.filter { it.track.remoteId !in listed }
        if (alone.isNotEmpty()) onChecking(alone.size)
        val answers = links.associate { link ->
            link.manga.id to (listed[link.track.remoteId]?.let(RemoteAnswer::Listed) ?: readOne(tracker, link.track))
        }
        return RemoteRead(answers, (read as? ListRead.Unreadable)?.reason)
    }

    /**
     * The local pass: each link's live chapters, every scanlator's, read straight from the
     * repository (a failure is thrown, never read as no chapters), the queue once, and the history of
     * the entries where the app is ahead; then [CompareRules.entry] for each.
     */
    suspend fun compare(tracker: Tracker, links: List<CompareLink>, remote: RemoteRead): Comparison = withIOContext {
        val queue = store.getItems()
        val entries = links.map { link ->
            val answer = remote.answers.getValue(link.manga.id)
            val chapters = chapterRepository.getChapterByMangaId(link.manga.id)
            val waiting = queue.firstOrNull {
                it.mangaId == link.manga.id && it.trackerId == link.track.trackerId && it.remoteId == link.track.remoteId
            }
            val history = if (CompareRules.isAppAhead(chapters, answer)) {
                historyRepository.getHistoryByMangaId(link.manga.id).mapTo(mutableSetOf()) { it.chapterId }
            } else {
                emptySet()
            }
            CompareRules.entry(link, answer, chapters, waiting, history, tracker.getRereadingStatus())
        }
        Comparison(entries, remote.listUnreadable)
    }

    private suspend fun readOne(tracker: ListComparingTracker, track: Track): RemoteAnswer = try {
        when (val read = tracker.readEntry(track)) {
            is EntryRead.OnList -> RemoteAnswer.Listed(read.track.toListEntry())
            is EntryRead.OffList -> RemoteAnswer.OffList(read.track.totalChapters)
            EntryRead.Gone -> RemoteAnswer.Gone
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when (CompareFailures.classify(e)) {
            is CompareFailure.RowOnly -> RemoteAnswer.Failed(e)
            is CompareFailure.Stop -> throw e
        }
    }
}

/** A library entry tracked on the compared tracker: the manga and the pair's stored row. */
data class CompareLink(val manga: Manga, val track: Track)

/** What the service holds for one link. */
sealed interface RemoteAnswer {

    /** On the owner's list, from the list or from a read of the entry alone. */
    data class Listed(val entry: ListEntry) : RemoteAnswer

    /** Not on the owner's list; the service counts [totalChapters] for the entry (0: none). */
    data class OffList(val totalChapters: Long) : RemoteAnswer

    /** The service no longer has the entry. */
    data object Gone : RemoteAnswer

    /** The entry alone could not be read ([CompareFailure.RowOnly]). */
    data class Failed(val error: Throwable) : RemoteAnswer
}

/**
 * [answers] by manga id; [listUnreadable] is why the list was read one entry at a time, or null
 * when it was read whole.
 */
data class RemoteRead(val answers: Map<Long, RemoteAnswer>, val listUnreadable: String? = null)

/** Where an entry that differs is listed, in the order the screen lists them. */
enum class CompareSection { REMOTE_AHEAD, APP_AHEAD, NOT_ON_LIST, CANT_COMPARE }

/**
 * What the owner can choose for a row. Every row with a choice starts on [DONT_CHANGE]; nothing
 * marks chapters unread.
 */
enum class CompareChoice {
    DONT_CHANGE,

    /** Mark the listed chapters read in the app, forward only (MyAnimeList is ahead). */
    MARK_READ,

    /** Send the app's number, capped at the service's count ([ComparedEntry.target]). */
    UPDATE_REMOTE,

    /** Send the app's number below the service's ([ComparedEntry.app]). */
    SET_REMOTE_BACK,

    /** Cancel the update waiting in the queue ([ComparedEntry.waitingUpdate]). */
    KEEP_REMOTE,

    /** Put the entry back on the owner's list with the stored row's status, score and dates. */
    ADD_BACK,

    /** Remove the pair's row in the app; the owner's list is not changed. */
    REMOVE_TRACKING,
}

/** A line on a row and in its choice dialog; it changes no default. */
enum class CompareWarning {
    /** The service counts chapters and the entry's last chapter is not that count. */
    NUMBERING,

    /** The service shows a reread. */
    REREAD,

    /** No read copy of the app's highest read chapter has a history row. */
    NOT_OPENED,
}

/** Why a row is under "Can't compare". */
enum class CantCompareReason { NO_CHAPTERS, NO_NUMBERS, COULD_NOT_CHECK }

/** One chapter "Mark as read" would mark: its row id and its number. */
data class ChapterMark(val id: Long, val number: Double)

/**
 * One compared entry, and everything the owner was shown for it, which Apply checks again.
 *
 * [section] is null when the app and the service agree. [maxRead] is the highest recognised number
 * among the read live chapters, unrounded, -1 when none is read; [app] is it rounded down, 0 when
 * none is read; [last] is the highest recognised number among the live chapters, -1 when none;
 * [target] is the number "Update MyAnimeList" sends. [toMark] is captured now: Apply marks only
 * among it. [waitingUpdate] is this device's queued push the row names, if it shows one.
 */
data class ComparedEntry(
    val manga: Manga,
    val track: Track,
    val remote: RemoteAnswer,
    val section: CompareSection?,
    val maxRead: Double,
    val app: Int,
    val last: Double,
    val target: Int,
    val toMark: List<ChapterMark>,
    val warnings: Set<CompareWarning>,
    val waitingUpdate: DelayedTrackingItem?,
    val choices: List<CompareChoice>,
    val cantCompare: CantCompareReason?,
) {
    /** The choice every row starts on: [CompareChoice.DONT_CHANGE], or null when there is none. */
    val defaultChoice: CompareChoice? get() = if (choices.isEmpty()) null else CompareChoice.DONT_CHANGE

    /** The service's list entry, when the entry is on the owner's list. */
    val listed: ListEntry? get() = (remote as? RemoteAnswer.Listed)?.entry

    /** The service's chapter count, 0 when it has none or did not say. */
    val count: Long
        get() = when (remote) {
            is RemoteAnswer.Listed -> remote.entry.totalChapters
            is RemoteAnswer.OffList -> remote.totalChapters
            else -> 0
        }
}

/** A comparison's entries, every compared one; [rows] are the ones listed, in the screen's order. */
data class Comparison(val entries: List<ComparedEntry>, val listUnreadable: String? = null) {

    /** The entries that differ or cannot be compared: by section, then by title. */
    val rows: List<ComparedEntry> by lazy {
        entries.filter { it.section != null }
            .sortedWith(compareBy<ComparedEntry>({ it.section }, { it.manga.displayTitle.lowercase() }, { it.manga.id }))
    }

    val agreeing: Int get() = entries.count { it.section == null }
}

/**
 * The comparison's rules (the design's section 6), a pure function over plain values.
 *
 * The app's number is the highest read recognised chapter rounded down, the number Saikou's own
 * pushes leave on the service (each push is a chapter's own number, upward only, sent rounded down),
 * not the last chapter of the unbroken read run `AddTracks` seeds with.
 */
object CompareRules {

    /**
     * The one entry of [link] with the service's [remote] answer, its live [chapters], the queue's
     * [waiting] entry for the pair if any, and the chapter ids [historyChapterIds] has a history row
     * for (read only where the app is ahead). [rereadingStatus] is the tracker's rereading status.
     */
    fun entry(
        link: CompareLink,
        remote: RemoteAnswer,
        chapters: List<Chapter>,
        waiting: DelayedTrackingItem?,
        historyChapterIds: Set<Long>,
        rereadingStatus: Long,
    ): ComparedEntry {
        val numbers = numbers(chapters)
        val base = ComparedEntry(
            manga = link.manga,
            track = link.track,
            remote = remote,
            section = null,
            maxRead = numbers.maxRead,
            app = numbers.app,
            last = numbers.last,
            target = numbers.app,
            toMark = emptyList(),
            warnings = emptySet(),
            waitingUpdate = null,
            choices = emptyList(),
            cantCompare = null,
        )
        return when (remote) {
            is RemoteAnswer.Listed -> listedEntry(base, remote.entry, chapters, waiting, historyChapterIds, rereadingStatus)
            is RemoteAnswer.OffList -> base.copy(
                section = CompareSection.NOT_ON_LIST,
                waitingUpdate = waiting,
                choices = listOf(CompareChoice.DONT_CHANGE, CompareChoice.ADD_BACK, CompareChoice.REMOVE_TRACKING),
            )
            RemoteAnswer.Gone -> base.copy(
                section = CompareSection.NOT_ON_LIST,
                choices = listOf(CompareChoice.DONT_CHANGE, CompareChoice.REMOVE_TRACKING),
            )
            is RemoteAnswer.Failed -> base.copy(
                section = CompareSection.CANT_COMPARE,
                cantCompare = CantCompareReason.COULD_NOT_CHECK,
            )
        }
    }

    /** Whether [remote] is on the list below the number the app would send: where history is read. */
    fun isAppAhead(chapters: List<Chapter>, remote: RemoteAnswer): Boolean {
        val listed = (remote as? RemoteAnswer.Listed)?.entry ?: return false
        val numbers = numbers(chapters)
        return numbers.last >= 0 && target(numbers.app, listed.totalChapters) > listed.chaptersRead
    }

    /** [maxRead], [app] and [last] of the design's 6.1, over live [chapters]. */
    fun numbers(chapters: List<Chapter>): Numbers {
        val recognised = chapters.filter { it.isRecognizedNumber }
        val maxRead = recognised.filter { it.read }.maxOfOrNull { it.chapterNumber } ?: -1.0
        val last = recognised.maxOfOrNull { it.chapterNumber } ?: -1.0
        val app = if (maxRead < 0) 0 else floor(maxRead).toInt()
        return Numbers(maxRead, app, last)
    }

    /** The unrounded highest read, the app's number, and the highest recognised number. */
    data class Numbers(val maxRead: Double, val app: Int, val last: Double)

    /** The number "Update MyAnimeList" sends: [app], or the service's [count] when it is lower. */
    fun target(app: Int, count: Long): Int = if (count > 0 && app > count) count.toInt() else app

    /**
     * The chapters "Mark as read" marks for an entry whose unrounded highest read is [maxRead] and
     * whose service number is [remoteChapters]: live, unread, recognised, above [maxRead] and at most
     * [remoteChapters], every scanlator's copy.
     */
    fun toMark(chapters: List<Chapter>, maxRead: Double, remoteChapters: Int): List<Chapter> = chapters.filter {
        !it.read && it.isRecognizedNumber && it.chapterNumber > maxRead && it.chapterNumber <= remoteChapters
    }

    private fun listedEntry(
        base: ComparedEntry,
        listed: ListEntry,
        chapters: List<Chapter>,
        waiting: DelayedTrackingItem?,
        historyChapterIds: Set<Long>,
        rereadingStatus: Long,
    ): ComparedEntry {
        val remoteChapters = listed.chaptersRead
        // Rows 1 and 2: nothing the app can compare; with the service at 0 there is nothing to differ.
        if (chapters.isEmpty() || base.last < 0) {
            if (remoteChapters == 0) return base
            val reason = if (chapters.isEmpty()) CantCompareReason.NO_CHAPTERS else CantCompareReason.NO_NUMBERS
            return base.copy(section = CompareSection.CANT_COMPARE, cantCompare = reason)
        }
        val count = listed.totalChapters
        val target = target(base.app, count)
        val numbering = count > 0 && floor(base.last).toLong() != count
        return when {
            // Row 6: agree.
            remoteChapters in target..base.app -> base.copy(target = target)
            // Row 7: the app is ahead.
            target > remoteChapters -> {
                val shownWaiting = waiting?.takeIf { it.chapterNumber > remoteChapters }
                val warnings = buildSet {
                    if (numbering) add(CompareWarning.NUMBERING)
                    if (listed.status == rereadingStatus) add(CompareWarning.REREAD)
                    val openedTop = chapters.any {
                        it.read && it.chapterNumber == base.maxRead && it.id in historyChapterIds
                    }
                    if (!openedTop) add(CompareWarning.NOT_OPENED)
                }
                base.copy(
                    section = CompareSection.APP_AHEAD,
                    target = target,
                    warnings = warnings,
                    waitingUpdate = shownWaiting,
                    choices = listOfNotNull(
                        CompareChoice.DONT_CHANGE,
                        CompareChoice.UPDATE_REMOTE,
                        CompareChoice.KEEP_REMOTE.takeIf { shownWaiting != null },
                    ),
                )
            }
            // Rows 8 and 9: the service is ahead.
            else -> {
                val toMark = toMark(chapters, base.maxRead, remoteChapters).map { ChapterMark(it.id, it.chapterNumber) }
                base.copy(
                    section = CompareSection.REMOTE_AHEAD,
                    target = target,
                    toMark = toMark,
                    warnings = if (numbering) setOf(CompareWarning.NUMBERING) else emptySet(),
                    choices = listOfNotNull(
                        CompareChoice.DONT_CHANGE,
                        CompareChoice.MARK_READ.takeIf { toMark.isNotEmpty() },
                        CompareChoice.SET_REMOTE_BACK,
                    ),
                )
            }
        }
    }
}

/** A list entry as the service's read of one entry gave it. */
internal fun Track.toListEntry(): ListEntry = ListEntry(remoteId, lastChapterRead.toInt(), totalChapters, status)
