package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.chapter.ChapterReplacements
import com.shinsaikou.domain.chapter.VolumeRecognition
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.source.toDomainChapter
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.model.ChapterInfo
import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.chapter.service.ChapterSanitizer
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga

/**
 * The one place that turns a source chapter list into database rows. The manga detail refresh, the
 * library update pass and the backup importer's optional refetch all go through it.
 *
 * A removed chapter with a manifest row is soft deleted, so its id, and with it the manifest's
 * match to it, survive a refresh that misses it, a renumbering or a delisting upstream. Every other
 * removed chapter is hard deleted, and its sync tombstone comes from a trigger. A soft-deleted
 * chapter the source lists again, by url, is restored rather than inserted as a new row: its one
 * update clears deleted_at ([ChapterUpdate.clearDeletedAt]) beside the source's current fields, so
 * the relisting moves its version once, as the soft delete did (phase 4 unit CR).
 *
 * With "After fetching new chapter" in upstream's `mark_duplicate_read_chapter_read`, a new chapter
 * whose number a read chapter already has is inserted read, as upstream's sync marks it; a row it
 * replaces still decides its progress.
 *
 * A chapter's volume is read from its name beside its number ([VolumeRecognition], phase 4 unit VG).
 * A name that loses its marker clears the stored volume: the update coalesces every column, so a
 * null there would keep the old one, and [ChapterUpdate.clearVolume] writes the null instead. It
 * rides in the chapter's one update, so the rename and the clear move its version once: the sync
 * merge takes a chapter's source values from the higher version.
 *
 * An update carries the source's fields only ([sourceUpdate], phase 4 unit BF). The chapters are
 * read before the transaction, and an update built from the whole row read then wrote its read
 * flag, bookmark, last page and version back over a progress commit the reader made in between.
 * The chapter's memo is one of the source's fields, as upstream's refresh writes `memo = chapter.memo`
 * (phase 5 unit FX, R128 carry-over 3): [ShouldUpdateDbChapter] compares it, so a memo the update
 * did not write was compared again, and updated again, on every refresh.
 *
 * An entry's first chapter list, read after it joined the library without one (added from a
 * source's listing, many at once, or restored from a backup without chapters), is its back
 * catalogue, not news. Its chapters uploaded before the entry was added, or with no upload date,
 * take the date added as their fetch date and are not returned, so they reach neither the Updates
 * tab (`date_fetch > date_added`), the new chapter count, the notification nor auto-download. A
 * chapter uploaded after the add is new as usual. An entry added from its own page already reads
 * this way in upstream: its chapters were fetched before the add. Upstream has no such rule (v0.20.4 and
 * v0.21.0: browse's add fetches no chapters, and the library update counts the whole list).
 *
 * A chapter a source lists under a new url, with the number of a row it no longer lists, takes
 * that row's progress and its reading history. The history would otherwise go with the old row: by
 * the delete's cascade, or out of History, which shows live chapters only, for a soft-deleted row.
 * Both are read in the transaction that removes the row, so a page the reader wrote to it after the
 * chapters were read is carried too. Where the number is one removed row's and one new chapter's,
 * a reader still open on the old row writes to the new one from then on ([ChapterReplacements]);
 * with two on either side, which took whose place is a guess, and the reader is not sent there.
 *
 * A new chapter from a scanlator the owner excluded for the entry is stored but not returned, as
 * upstream v0.20.4's sync leaves it out: it is hidden from the entry's list, so it is not news to
 * count, notify or download either.
 */
@Inject
class SyncChaptersWithSource(
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val transactionRunner: TransactionRunner,
    private val downloadManifestRepository: DownloadManifestRepository,
    private val libraryPreferences: LibraryPreferences,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val historyRepository: HistoryRepository,
    private val chapterReplacements: ChapterReplacements,
) {

    /** Returns the chapters that are new to the database, not marked read and not from an excluded scanlator. */
    suspend fun await(
        rawSourceChapters: List<ChapterInfo>,
        manga: Manga,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): List<Chapter> {
        if (rawSourceChapters.isEmpty() && manga.source != SourceIds.LOCAL) {
            throw NoChaptersException()
        }

        val now = System.currentTimeMillis()

        val sourceChapters = rawSourceChapters
            .distinctBy { it.url }
            .mapIndexed { index, info ->
                val chapter = info.toDomainChapter(manga.id).copy(
                    sourceOrder = index.toLong(),
                    name = with(ChapterSanitizer) { info.name.sanitize(manga.title) },
                )
                val number = ChapterRecognition.parseChapterNumber(manga.title, chapter.name, chapter.chapterNumber)
                val volume = VolumeRecognition.parseVolumeNumber(manga.title, chapter.name)
                chapter.copy(chapterNumber = number, volume = volume)
            }

        val dbChapters = getChaptersByMangaId.await(manga.id)
        val dbChaptersByUrl = dbChapters.associateBy { it.url }
        val sourceUrls = sourceChapters.mapTo(mutableSetOf()) { it.url }

        val removed = dbChapters.filterNot { it.url in sourceUrls }
        val downloadedIds = if (removed.isEmpty()) {
            emptySet()
        } else {
            downloadManifestRepository.getByMangaId(manga.id).mapTo(mutableSetOf()) { it.chapterId }
        }
        val (softDeleted, hardDeleted) = removed.partition { it.id in downloadedIds }

        // Only asked for when the source lists a url no live row has.
        val deletedByUrl = if (sourceChapters.all { it.url in dbChaptersByUrl }) {
            emptyMap()
        } else {
            chapterRepository.getDeletedChaptersByMangaId(manga.id).associateBy { it.url }
        }

        val newChapters = mutableListOf<Chapter>()
        val changed = mutableListOf<Chapter>()
        val restored = mutableSetOf<Long>()
        val volumeCleared = mutableSetOf<Long>()

        for (sourceChapter in sourceChapters) {
            val dbChapter = dbChaptersByUrl[sourceChapter.url]
            val deleted = if (dbChapter == null) deletedByUrl[sourceChapter.url] else null
            if (deleted != null) {
                // Restored with the source's current fields, in the one update that clears deletedAt.
                restored += deleted.id
                changed += deleted.updatedFrom(sourceChapter)
                if (deleted.volume != null && sourceChapter.volume == null) volumeCleared += deleted.id
            } else if (dbChapter == null) {
                newChapters += sourceChapter
            } else if (shouldUpdateDbChapter.await(dbChapter, sourceChapter)) {
                changed += dbChapter.updatedFrom(sourceChapter)
                if (dbChapter.volume != null && sourceChapter.volume == null) volumeCleared += dbChapter.id
            }
        }

        // A renamed or re-uploaded chapter keeps the progress of the row it replaces, and keeps its
        // original fetch date so it does not reappear in the Updates feed.
        val carriedOver = removed
            .filter { it.isRecognizedNumber }
            .associateBy { it.chapterNumber }

        val markDuplicateAsRead = LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW in
            libraryPreferences.markDuplicateReadChapterAsRead.get()
        val readChapterNumbers = if (markDuplicateAsRead) {
            dbChapters.filter { it.read && it.isRecognizedNumber }.mapTo(mutableSetOf()) { it.chapterNumber }
        } else {
            emptySet()
        }

        // A library entry with no chapter, live or soft deleted, is on its first list: the back
        // catalogue up to its date added is filed under that date rather than counted as new.
        val backlogUntil = manga.dateAdded.takeIf {
            manga.favorite && it > 0L && dbChapters.isEmpty() && deletedByUrl.isEmpty()
        }
        val backlogUrls = mutableSetOf<String>()
        // The row each new chapter takes its progress from, by the new chapter's url. The progress
        // itself is read in the transaction.
        val carriedFrom = mutableMapOf<String, Chapter>()
        // The row each new chapter replaces, by the new chapter's url. Only the first new chapter of
        // a number takes it, so a reading history moves once.
        val replaced = mutableMapOf<String, Chapter>()

        val toInsert = newChapters.map { raw ->
            // Read before the stamp below: a chapter with no upload date cannot be told to be new.
            val backlogFetch = backlogUntil?.takeIf { raw.dateUpload <= it }
            if (backlogFetch != null) backlogUrls += raw.url
            // A chapter the source dates at zero is stamped with the moment it was first seen.
            val chapter = if (raw.dateUpload == 0L) raw.copy(dateUpload = now) else raw
            val previous = carriedOver[chapter.chapterNumber].takeIf { chapter.isRecognizedNumber }
            if (previous == null) {
                val duplicateOfRead = chapter.isRecognizedNumber && chapter.chapterNumber in readChapterNumbers
                chapter.copy(dateFetch = backlogFetch ?: now, read = chapter.read || duplicateOfRead)
            } else {
                if (replaced.values.none { it.id == previous.id }) replaced[chapter.url] = previous
                carriedFrom[chapter.url] = previous
                chapter
            }
        }

        if (toInsert.isEmpty() && changed.isEmpty() && removed.isEmpty()) {
            // Upstream v0.20.4's clause, which this copy had dropped: a predicted next update that has
            // fallen behind the window is moved on. Kept, it stayed in the past, so the release
            // period rule never skipped the entry and every automatic run fetched it again.
            if (manualFetch || manga.fetchInterval == 0 || manga.nextUpdate < fetchWindow.first) {
                updateManga.awaitUpdateFetchInterval(manga, window = fetchWindow)
            }
            return emptyList()
        }

        // The replacements a reader open on the old row can follow, by the old url: a number one
        // removed row and one new chapter share.
        val removedPerNumber = removed.filter { it.isRecognizedNumber }.groupingBy { it.chapterNumber }.eachCount()
        val newPerNumber = toInsert.filter { it.isRecognizedNumber }.groupingBy { it.chapterNumber }.eachCount()
        val successors = carriedFrom.entries
            .filter { (_, previous) ->
                removedPerNumber[previous.chapterNumber] == 1 && newPerNumber[previous.chapterNumber] == 1
            }
            .associate { (url, previous) -> previous.url to url }

        // Recorded inside the transaction, so a transaction that does not commit takes them back.
        var committed = false
        val inserted = try {
            transactionRunner.inTransaction {
                // The replaced rows' progress and history are read here, in the transaction that removes
                // them. Read with the chapters above, a page the reader wrote to one since was lost.
                val carried = currentRows(carriedFrom)
                // The reading history each replacing chapter takes over, by its url.
                val movedHistory = if (replaced.isEmpty()) emptyMap() else historyToMove(manga.id, replaced)
                // A hard-deleted row's history goes with it by the cascade. A soft-deleted row keeps its
                // own, which would count the reading time twice, so it is removed here.
                val softDeletedIds = softDeleted.mapTo(HashSet()) { it.id }
                movedHistory.values
                    .filter { it.chapterId in softDeletedIds }
                    .forEach { historyRepository.resetHistory(it.id) }
                if (hardDeleted.isNotEmpty()) {
                    chapterRepository.removeChaptersWithIds(hardDeleted.map { it.id })
                }
                if (softDeleted.isNotEmpty()) {
                    chapterRepository.softDelete(softDeleted.map { it.id }, now)
                }
                val insertedRows = if (toInsert.isNotEmpty()) {
                    chapterRepository.addAll(
                        toInsert.map { chapter -> carried[chapter.url]?.let { chapter.carry(it) } ?: chapter },
                    )
                } else {
                    emptyList()
                }
                for (row in insertedRows) {
                    val history = movedHistory[row.url] ?: continue
                    val readAt = checkNotNull(history.readAt)
                    historyRepository.upsertHistory(HistoryUpdate(row.id, readAt, history.readDuration))
                }
                if (changed.isNotEmpty()) {
                    chapterRepository.updateAll(
                        changed.map {
                            it.sourceUpdate(clearVolume = it.id in volumeCleared, clearDeletedAt = it.id in restored)
                        },
                    )
                }
                chapterReplacements.record(manga.id, successors)
                insertedRows
            }.also { committed = true }
        } finally {
            if (!committed) chapterReplacements.forget(manga.id, successors)
        }

        if (inserted.isNotEmpty()) {
            updateManga.awaitUpdateLastUpdate(manga.id)
        }
        updateManga.awaitUpdateFetchInterval(manga, window = fetchWindow)

        val news = inserted.filterNot { it.read || it.url in backlogUrls }
        if (news.isEmpty()) return news
        val excluded = getExcludedScanlators.await(manga.id)
        return news.filterNot { it.scanlator in excluded }
    }

    /** Each row of [carriedFrom] as it is now, by the same url; as it was read where it is gone. */
    private suspend fun currentRows(carriedFrom: Map<String, Chapter>): Map<String, Chapter> {
        if (carriedFrom.isEmpty()) return emptyMap()
        val current = chapterRepository.getChaptersByIds(carriedFrom.values.map { it.id }.distinct())
            .associateBy { it.id }
        return carriedFrom.mapValues { (_, previous) -> current[previous.id] ?: previous }
    }

    /** This new chapter with [previous]'s progress, and its fetch date, so it stays out of Updates. */
    private fun Chapter.carry(previous: Chapter) = copy(
        read = previous.read,
        bookmark = previous.bookmark,
        lastPageRead = previous.lastPageRead,
        dateFetch = previous.dateFetch,
    )

    /** The history of each row in [replaced] that has a read time, keyed by the url replacing it. */
    private suspend fun historyToMove(mangaId: Long, replaced: Map<String, Chapter>): Map<String, History> {
        val history = historyRepository.getHistoryByMangaId(mangaId).associateBy { it.chapterId }
        return replaced.mapNotNull { (url, previous) ->
            history[previous.id]?.takeIf { it.readAt != null }?.let { url to it }
        }.toMap()
    }

    /**
     * The fields of this chapter that [updatedFrom] took from the source, and nothing else (phase 4
     * unit BF, R120 carry-over 1): name, url, scanlator, number, order, upload date, volume and memo
     * (phase 5 unit FX). The read flag, bookmark, last page, version, tombstone, fetch date and manga
     * are left null, so the update keeps what the row holds when it runs: a progress commit the
     * reader made after this refresh read the chapters is not written back over, and the version
     * moves by the trigger alone. The memo is no sync field (`ChapterRecord` carries none), and the
     * version trigger does not read it, so a memo alone moves neither the version nor the change log.
     */
    private fun Chapter.sourceUpdate(clearVolume: Boolean, clearDeletedAt: Boolean) = ChapterUpdate(
        id = id,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        volume = volume,
        memo = memo,
        clearVolume = clearVolume,
        clearDeletedAt = clearDeletedAt,
    )

    /**
     * The memo is taken here, where upstream's loop takes it (`memo = chapter.memo`), rather than in
     * [Chapter.copyFrom], which stays upstream's: the backup importer's `mergeChapter` calls it too, and
     * a restore writes the file's memo, as upstream's does.
     */
    private fun Chapter.updatedFrom(sourceChapter: Chapter): Chapter {
        // A source that reports no upload date must not push the stored one back to zero.
        val dateUpload = if (sourceChapter.dateUpload == 0L) dateUpload else sourceChapter.dateUpload
        return copyFrom(sourceChapter).copy(
            sourceOrder = sourceChapter.sourceOrder,
            dateUpload = dateUpload,
            memo = sourceChapter.memo,
        )
    }
}
