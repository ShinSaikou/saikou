package com.shinsaikou.migration

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.downloads.MigrationDownloads
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.tracking.DelayedTrackingScheduler
import com.shinsaikou.tracking.DelayedTrackingStore
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.domain.migration.models.MigrationFlag
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.util.Date
import kotlin.time.Clock

/**
 * Moves or copies what Saikou knows about one entry onto another (phase 4 unit MA), after upstream's
 * `MigrateMangaUseCase`. [replace] is upstream's Migrate (the old entry leaves the library); without it
 * the call is upstream's Copy (the old entry's rows are only read).
 *
 * The order, and where it differs from upstream's:
 *
 * 1. The target's chapters are fetched first, chapters only, outside any transaction (upstream's
 *    `updateMangaFromRemote(target, fetchChapters = true)`). A target source that is not loaded
 *    throws [SourceNotInstalledException], where upstream returns silently. A target that is the entry
 *    itself is refused before the fetch.
 * 2. Then, non-cancellable, one transaction re-reads both rows by id and refuses, before any write,
 *    a target row that is gone, and on [replace] an entry that is no longer a live favourite (a
 *    pull or another migration may have moved it since it was shown). Every write is made from
 *    the rows read inside it: the chapters, with a [ChapterUpdate] holding only `read` (raised),
 *    `bookmark` and `dateFetch` (on chapters of the same recognised number, as upstream writes them,
 *    false bookmarks included), never upstream's whole row, which in Saikou would rewrite source values
 *    and `version`; with the chapters, the reading history, by recognised number too, which
 *    upstream does not carry ([historyCarries]); the categories; each old track copied in
 *    `AddTracks.bind`'s shape (a plain insert, or `rebind` when the target's pair names another
 *    remote id, since `rebind` moves the tracker-set clock even for the same id); the owner's edited
 *    title and description, which belong to the entry rather than to a source; a tombstoned target
 *    undeleted; and last the favourite pair, as upstream writes it, so a target that enters the
 *    library logs every chapter it holds in one pass, with the copied read and bookmark flags and
 *    fetch dates (the library-add trigger the chapters with progress, `MangaRepository.updateAll`
 *    the rest; phase 4 unit NB). On [replace]
 *    the target also takes the old entry's `manualPosition` and its place in a merged group (R163),
 *    so migrating a merged title's main entry keeps the title. A failure anywhere rolls every write
 *    back. On a target already in the library, a copied fetch date reaches the sync log only on a
 *    chapter logged for another reason: no trigger tracks `date_fetch` alone, so another device
 *    keeps its own fetch date for the rest.
 * 3. After the commit, still non-cancellable: the custom cover, on this device only; then, with
 *    [MigrationFlag.DOWNLOADS], the old entry's downloaded chapters moved onto the target's chapters
 *    of the same number, or copied on a Copy ([MigrationDownloads], unit migrate-downloads); then,
 *    with [MigrationFlag.REMOVE_DOWNLOAD], its queue rows removed and then what is left of its
 *    downloads deleted, upstream's order, whether or not its source is installed (upstream asks for the
 *    source; [DownloadManifest.deleteManga] finds the folders without it); then, on a Migrate that
 *    did not carry the downloads and did not delete them, the old entry's downloaded chapters read
 *    for [Result.leftBehindDownloads]. Next, the merged group's read marks are reconciled, since
 *    the copy raised them without the mark-read path. Last, a tracker push queued
 *    for the old entry against the remote id a track was copied with is queued for the target too,
 *    and the retry worker is scheduled (R98 item 5); the old entry's own push stays queued, and the
 *    worker sends it to the same remote entry. A failure of any of these steps is logged and the
 *    migration stands, so a throw always means nothing was written (R114 carry-over 1, unit MB).
 *
 * Every other failure is thrown: upstream's catch-all is not kept, so a caller always learns that an
 * entry was not written. A migration that wrote returns the old entry's downloaded chapters a
 * Migrate left on this device ([Result.leftBehindDownloads]) for the caller to offer to delete:
 * with [MigrationFlag.DOWNLOADS], the ones that could not be moved; without it, every one of them,
 * since none was carried (unit migrate-kept-downloads; upstream says nothing then). [Result.carried]
 * tells the caller which it is. Nothing here reaches auto-download, the delete after reading or a
 * tracker service: the copy raises read flags without the mark-read path, and the re-fetch's new
 * chapters go nowhere (R97 item 3, R99 item 7).
 *
 * Not carried: bookmark notes and a scanlator ranking, which name one source's chapters and groups,
 * and excluded scanlators, which upstream does not carry either.
 *
 * The history a Migrate moves leaves the old chapters, and a deleted history row does not travel by
 * sync (Q6): another device gets the history on the target's chapters and keeps the old entry's
 * until it is removed there, and its reading total counts the moved time on both until then.
 */
@Inject
class MigrateManga(
    private val sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val refreshMangaFromSource: RefreshMangaFromSource,
    private val transactionRunner: TransactionRunner,
    private val mangaRepository: MangaRepository,
    private val setMangaDetailsOverride: SetMangaDetailsOverride,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val trackRepository: TrackRepository,
    private val historyRepository: HistoryRepository,
    private val coverCache: CoverCache,
    private val downloadQueue: DownloadQueue,
    private val downloadManifest: DownloadManifest,
    private val migrationDownloads: MigrationDownloads,
    private val delayedTrackingStore: DelayedTrackingStore,
    private val delayedTrackingScheduler: DelayedTrackingScheduler,
    private val mergedGroupRepository: MergedGroupRepository,
    private val reconcileMergedRead: ReconcileMergedRead,
    private val dispatchers: Dispatchers,
) {

    /**
     * What a migration that wrote leaves its caller: [leftBehindDownloads] are the old entry's
     * downloaded chapters a Migrate left on this device and [MigrationFlag.REMOVE_DOWNLOAD] did not
     * delete (the step unticked, or failed). [carried] is [MigrationFlag.DOWNLOADS] in the flags:
     * when true they are the chapters the transfer did not move (no match, or a move that failed);
     * when false nothing was carried, and they are every downloaded chapter of the old entry, as
     * [MigrationDownloads.downloadedChapters] counts them. A Copy leaves none behind either way: the
     * old entry stays in the library with its downloads.
     */
    data class Result(val leftBehindDownloads: List<Long>, val carried: Boolean)

    /**
     * Migrates [current] onto [target], or copies it when [replace] is false. [flags] defaults to
     * the stored `migration_flags`; a caller running several migrations passes the set it read once.
     */
    suspend operator fun invoke(
        current: Manga,
        target: Manga,
        replace: Boolean,
        flags: Set<MigrationFlag> = sourcePreferences.migrationFlags.get(),
    ): Result = withContext(dispatchers.io) {
        check(target.id != current.id) { "Entry ${current.id} cannot be migrated onto itself" }
        val targetSource = sourceManager.get(target.source) ?: throw SourceNotInstalledException(target.source)

        refreshMangaFromSource(targetSource, target, fetchChapters = true)

        withNonCancellableContext {
            val written = transactionRunner.inTransaction { write(current.id, target.id, replace, flags) }
            if (MigrationFlag.CUSTOM_COVER in flags) copyCustomCover(written.current, written.target)
            val deleteDownloads = MigrationFlag.REMOVE_DOWNLOAD in flags
            val carried = MigrationFlag.DOWNLOADS in flags
            // A Copy whose old downloads are deleted next moves them: a copy would only be deleted.
            val notMoved = if (carried) {
                transferDownloads(written.current, written.target, move = replace || deleteDownloads)
            } else {
                emptyList()
            }
            val removed = deleteDownloads && removeDownloads(written.current.id)
            val leftBehind = when {
                !replace || removed -> emptyList()
                carried -> notMoved
                // Read after a Delete downloaded that failed, so only what it left is counted.
                else -> keptDownloads(written.current.id)
            }
            fileStep("reconcile the merged read marks of entry ${written.target.id}") {
                reconcileMergedRead.await(written.target.id)
            }
            queueTrackerPushes(written.current.id, written.target.id, written.tracks)
            Result(leftBehindDownloads = leftBehind, carried = carried)
        }
    }

    /**
     * Deletes the files and rows of [chapterIds], the [Result.leftBehindDownloads] of a migration,
     * when the owner chooses Delete them at its end.
     */
    suspend fun deleteLeftBehindDownloads(chapterIds: List<Long>) {
        withNonCancellableContext { migrationDownloads.delete(chapterIds) }
    }

    /** The two rows as the transaction read them, and the tracks it copied onto the target. */
    private class Written(val current: Manga, val target: Manga, val tracks: List<Track>)

    /** Step 2: the guards and every database write. */
    private suspend fun write(
        currentId: Long,
        targetId: Long,
        replace: Boolean,
        flags: Set<MigrationFlag>,
    ): Written {
        val current = mangaRepository.getMangaById(currentId)
        val target = mangaRepository.getMangaById(targetId)
        if (replace) {
            check(current.favorite && current.deletedAt == null) { "Entry $currentId is no longer in the library" }
        }

        if (MigrationFlag.CHAPTER in flags) {
            val previous = chapterRepository.getChapterByMangaId(current.id)
            val chapters = chapterRepository.getChapterByMangaId(target.id)
            val updates = chapterUpdates(previous, chapters)
            if (updates.isNotEmpty()) chapterRepository.updateAll(updates)
            carryHistory(previous, chapters, current.id, replace)
        }

        if (MigrationFlag.CATEGORY in flags) {
            val categoryIds = categoryRepository.getCategoriesByMangaId(current.id).map { it.id }
            mangaRepository.setMangaCategories(target.id, categoryIds)
        }

        val bound = trackRepository.getTracksByMangaId(target.id).associateBy { it.trackerId }
        val copied = trackRepository.getTracksByMangaId(current.id).map { it.copy(mangaId = target.id) }
        copied.forEach { track ->
            val pair = bound[track.trackerId]
            if (pair != null && pair.remoteId != track.remoteId) {
                trackRepository.rebind(track)
            } else {
                trackRepository.insert(track)
            }
        }

        // The owner's edits of the title and description (phase 5 unit ED), through their one writer.
        check(setMangaDetailsOverride.awaitCarry(from = current, to = target)) {
            "Could not write the edited details of entry ${target.id}"
        }

        if (replace) mergedGroupRepository.replaceMember(current.id, target.id)

        val currentUpdate = MangaUpdate(
            id = current.id,
            favorite = false,
            dateAdded = 0,
        )
            .takeIf { replace }
        val targetUpdate = MangaUpdate(
            id = target.id,
            favorite = true,
            chapterFlags = current.chapterFlags,
            viewerFlags = current.viewerFlags,
            dateAdded = if (replace) current.dateAdded else Clock.System.now().toEpochMilliseconds(),
            notes = if (MigrationFlag.NOTES in flags) current.notes else null,
            manualPosition = if (replace) current.manualPosition else null,
        )
        // A tombstone on the target (a removal another device synced here) would keep the entry out
        // of the library after the favourite flip, since no MangaUpdate clears deletedAt. The
        // undelete clears it and stamps the restore clock, as a restore's library add does (R87),
        // and comes first so the flip logs the entry as live.
        if (target.deletedAt != null) mangaRepository.undelete(target.id)

        // The repository logs and answers false where it could have thrown; the throw rolls back
        // every write above with it.
        check(mangaRepository.updateAll(listOfNotNull(currentUpdate, targetUpdate))) {
            "Could not write the favourite of entries $currentId and $targetId"
        }
        return Written(current, target, copied)
    }

    /**
     * The old entry's history onto the target's chapters, so History and Resume follow the entry
     * to its new source. A Migrate moves it, reading time included, so the reading total stays the
     * same; a Copy leaves it on the old entry and copies the read dates only, since the reading
     * time copied would count twice. A row whose chapter has no match stays where it is.
     */
    private suspend fun carryHistory(previous: List<Chapter>, chapters: List<Chapter>, currentId: Long, replace: Boolean) {
        val carries = historyCarries(previous, historyRepository.getHistoryByMangaId(currentId), chapters)
        if (carries.isEmpty()) return
        carries.forEach { carry ->
            historyRepository.mergeHistory(carry.chapterId, carry.readAt, if (replace) carry.readDuration else 0L)
        }
        if (replace) historyRepository.deleteHistoryByChapterIds(carries.flatMap { it.fromChapterIds })
    }

    /** Step 3, the cover: a file on this device, which neither sync nor a backup carries. */
    private fun copyCustomCover(current: Manga, target: Manga) {
        fileStep("copy the custom cover of entry ${current.id}") {
            val cover = coverCache.getCustomCoverFile(current.id)
            if (cover.exists()) cover.inputStream().use { coverCache.setCustomCoverToCache(target, it) }
        }
    }

    /**
     * Step 3, the downloads carried over: the old entry's downloaded chapters that did not follow.
     * A failure before any chapter was looked at leaves all of them where they were, unreported.
     */
    private suspend fun transferDownloads(current: Manga, target: Manga, move: Boolean): List<Long> {
        var leftBehind = emptyList<Long>()
        fileStep("${if (move) "move" else "copy"} the downloads of entry ${current.id} onto entry ${target.id}") {
            leftBehind = migrationDownloads.transfer(current, target, move).leftBehind
        }
        return leftBehind
    }

    /**
     * Step 3, a Migrate that did not carry the downloads: every downloaded chapter of the old entry
     * stays on this device. A failure to read them is logged and reports none.
     */
    private suspend fun keptDownloads(currentId: Long): List<Long> {
        var kept = emptyList<Long>()
        fileStep("read the downloads of entry $currentId") {
            kept = migrationDownloads.downloadedChapters(currentId)
        }
        return kept
    }

    /**
     * Step 3, the downloads deleted, upstream's `DownloadManager.deleteManga` order: the queue, then the
     * files. True when both steps ran.
     */
    private suspend fun removeDownloads(currentId: Long): Boolean {
        var removed = false
        fileStep("delete the downloads of entry $currentId") {
            downloadQueue.removeManga(currentId)
            downloadManifest.deleteManga(currentId)
            removed = true
        }
        return removed
    }

    /**
     * Step 3, the tracker queue: an old entry's push recorded against the remote id a track was
     * copied with is the target's progress too, so it is queued for the target and the worker is
     * asked for, as any writer that queues a push does (R98 item 5). A queue write that misses the
     * disk loses only the target's copy: the old entry's push stays queued for the same remote entry.
     */
    private suspend fun queueTrackerPushes(currentId: Long, targetId: Long, copied: List<Track>) {
        fileStep("queue the tracker pushes of entry $currentId for entry $targetId") {
            var queued = false
            try {
                copied.forEach { track ->
                    val pending = delayedTrackingStore.current(currentId, track.trackerId) ?: return@forEach
                    if (pending.remoteId != track.remoteId) return@forEach
                    delayedTrackingStore.add(targetId, track.trackerId, track.remoteId, pending.chapterNumber)
                    queued = true
                }
            } finally {
                if (queued) delayedTrackingScheduler.schedule()
            }
        }
    }

    private inline fun fileStep(what: String, step: () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Migration could not $what" }
        }
    }

    /** What one target chapter takes of the old entry's history, and the old chapters it comes from. */
    private class HistoryCarry(
        val chapterId: Long,
        val fromChapterIds: List<Long>,
        val readAt: Date,
        val readDuration: Long,
    )

    private companion object {

        /**
         * The old entry's history by recognised number, each number onto one target chapter, the
         * copy its source lists first, so a number read in two groups' copies is not counted twice.
         * Several old rows of one number fold into one: the latest read date and the reading times
         * added up. A row with no read date, on a chapter with no recognised number, or of a number
         * the target lacks, is left out.
         */
        fun historyCarries(previous: List<Chapter>, history: List<History>, chapters: List<Chapter>): List<HistoryCarry> {
            val numberOf = previous.filter { it.isRecognizedNumber }.associate { it.id to it.chapterNumber }
            val targetOf = chapters.filter { it.isRecognizedNumber }
                .groupBy { it.chapterNumber }
                .mapValues { (_, copies) -> copies.minWith(compareBy({ it.sourceOrder }, { it.id })).id }
            return history
                .mapNotNull { row ->
                    val readAt = row.readAt?.takeIf { it.time > 0 } ?: return@mapNotNull null
                    val target = numberOf[row.chapterId]?.let(targetOf::get) ?: return@mapNotNull null
                    Triple(target, row, readAt)
                }
                .groupBy { it.first }
                .map { (chapterId, rows) ->
                    HistoryCarry(
                        chapterId = chapterId,
                        fromChapterIds = rows.map { it.second.chapterId },
                        readAt = rows.maxOf { it.third },
                        readDuration = rows.sumOf { it.second.readDuration },
                    )
                }
        }

        /**
         * Upstream's chapter copy, as the fields it changes: every chapter of the target at or below
         * the highest read chapter of the old entry is raised to read, and a chapter whose
         * recognised number the old entry has takes that chapter's bookmark and fetch date. A
         * chapter neither touches is left out.
         */
        fun chapterUpdates(previous: List<Chapter>, chapters: List<Chapter>): List<ChapterUpdate> {
            val maxChapterRead = previous.filter { it.read }.maxOfOrNull { it.chapterNumber }
            return chapters.mapNotNull { chapter ->
                if (!chapter.isRecognizedNumber) return@mapNotNull null
                val match = previous.find { it.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber }
                val raise = maxChapterRead != null && chapter.chapterNumber <= maxChapterRead && !chapter.read
                if (match == null && !raise) return@mapNotNull null
                ChapterUpdate(
                    id = chapter.id,
                    read = true.takeIf { raise },
                    bookmark = match?.bookmark,
                    dateFetch = match?.dateFetch,
                )
            }
        }
    }
}
