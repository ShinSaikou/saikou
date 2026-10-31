package com.shinsaikou.sync

import android.content.Context
import com.shinsaikou.di.AppGraph
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import tachiyomi.domain.chapter.model.ChapterUpdate
import java.io.File

/**
 * Phase 5 unit TG3, item 1: the `restore_then_join` scenario of the two-device suite, a device
 * that restores a backup and then joins (R140 item 6's carried item, SC2's restore scenario).
 *
 * The backup is built here from fixture rows on [SyncPairLibrary.SOURCE_ID], never from anyone's
 * file: [OVERLAP] of the pair library's entries, the same rows the seed writes but with the reads,
 * bookmarks and pages of a device that read further ([BACKUP_READ], [BACKUP_BOOKMARK],
 * [BACKUP_PAGES]), and [RESTORE_ONLY] entries the pair library does not hold, each of
 * [RESTORE_ONLY_CHAPTERS] chapters, so the other device can only learn them from the restoring
 * device's export. The three pair categories come with it, flat, as every backup file holds them.
 *
 * [progress] is the other device's own further progress on the same [OVERLAP] entries, on other
 * chapters ([OTHER_READ], [OTHER_BOOKMARK], [OTHER_PAGES]), so the check can tell each side's edit
 * apart. [restore] restores the file through the app's own importer, as a restore onto a new phone
 * would. [check] asserts, on either device after the join, that every chapter of both is there, read
 * where either side read it, on the higher of the two pages, and bookmarked where either side
 * bookmarked it.
 *
 * A restore inserts its chapters under the latch with no change-log row each (phase 5 unit SC2), so
 * only the restoring device's full export carries them; the scenario is the device proof of that.
 */
object SyncPairRestore {

    /** The pair library's entries the backup holds too, by their seed index. */
    val OVERLAP = 0 until 6

    /** Entries only the backup holds, by the index [SyncPairLibrary.entryUrl] gives them past the pair library's. */
    val RESTORE_ONLY = SyncPairLibrary.ENTRIES until SyncPairLibrary.ENTRIES + 2
    const val RESTORE_ONLY_CHAPTERS = 6

    /** In the backup, on each [OVERLAP] entry: one chapter read past the seed's, a bookmark, two pages. */
    const val BACKUP_READ = 4
    const val BACKUP_BOOKMARK = 6
    val BACKUP_PAGES = mapOf(5 to 8L, 8 to 12L)

    /** On the other device, on the same entries: another chapter read, another bookmark, a higher page. */
    const val OTHER_READ = 7
    const val OTHER_BOOKMARK = 9
    val OTHER_PAGES = mapOf(5 to 20L)

    /** On each [RESTORE_ONLY] entry: the first two chapters read, one bookmark, one page. */
    private const val RESTORE_ONLY_READ = 2
    private const val RESTORE_ONLY_BOOKMARK = 3
    private val RESTORE_ONLY_PAGES = mapOf(4 to 5L)

    /** As [SyncPairLibrary]'s, so the backup's rows of an [OVERLAP] entry are the seed's own. */
    private const val DATE = 1_758_585_600_000L
    private const val DAY = 86_400_000L

    private const val RESTORE_FILE = "sync-pair-restore.tachibk"

    /** One chapter as both devices must hold it after the join. */
    private data class Expected(val read: Boolean, val bookmark: Boolean, val page: Long)

    /** Every chapter of [OVERLAP] and [RESTORE_ONLY] by url, as the join must leave it on both devices. */
    private fun expected(): Map<String, Expected> = buildMap {
        for (entry in OVERLAP) {
            for (number in 0 until SyncPairLibrary.CHAPTERS) {
                put(
                    SyncPairLibrary.chapterUrl(entry, number),
                    Expected(
                        read = number < SyncPairLibrary.READ || number == BACKUP_READ || number == OTHER_READ,
                        bookmark = number == BACKUP_BOOKMARK || number == OTHER_BOOKMARK,
                        page = maxOf(BACKUP_PAGES[number] ?: 0L, OTHER_PAGES[number] ?: 0L),
                    ),
                )
            }
        }
        for (entry in RESTORE_ONLY) {
            for (number in 0 until RESTORE_ONLY_CHAPTERS) {
                put(
                    SyncPairLibrary.chapterUrl(entry, number),
                    Expected(
                        read = number < RESTORE_ONLY_READ,
                        bookmark = number == RESTORE_ONLY_BOOKMARK,
                        page = RESTORE_ONLY_PAGES[number] ?: 0L,
                    ),
                )
            }
        }
    }

    /** The other device's further progress on the [OVERLAP] entries, through the chapter repository. */
    suspend fun progress(graph: AppGraph): Map<String, Any?> {
        val updates = mutableListOf<ChapterUpdate>()
        for (entry in OVERLAP) {
            val chapters = graph.chapterRepository.getChapterByMangaId(entryId(graph, entry)).associateBy { it.url }
            fun id(number: Int) = checkNotNull(chapters[SyncPairLibrary.chapterUrl(entry, number)]) {
                "no chapter $number of entry $entry"
            }.id
            updates += ChapterUpdate(id = id(OTHER_READ), read = true)
            updates += ChapterUpdate(id = id(OTHER_BOOKMARK), bookmark = true)
            OTHER_PAGES.forEach { (number, page) -> updates += ChapterUpdate(id = id(number), lastPageRead = page) }
        }
        graph.chapterRepository.updateAll(updates)
        return linkedMapOf("progressed" to updates.size)
    }

    /**
     * The backup: [OVERLAP] and [RESTORE_ONLY] as favourites in the pair categories by the seed's
     * turn, restored with library entries and categories and no settings. The file is the test's
     * own, written to the app's cache and removed afterwards.
     */
    suspend fun restore(graph: AppGraph, context: Context): Map<String, Any?> {
        val file = File(context.cacheDir, RESTORE_FILE)
        file.writeBytes(ProtoBuf.encodeToByteArray(Backup.serializer(), backup()))
        try {
            val options = RestoreOptions(appSettings = false, extensionStores = false, sourceSettings = false)
            val report = graph.backupImporter.import(file, options) {}
            assertTrue("the restore reported errors: ${report.errors}", report.errors.isEmpty())
            return linkedMapOf(
                "mangaInserted" to report.mangaInserted,
                "chaptersWritten" to report.chaptersWritten,
                "categoriesCreated" to report.categoriesCreated,
                "restoreFlag" to graph.syncRestorePreferences.restoredSinceSync.get(),
            )
        } finally {
            file.delete()
        }
    }

    private fun backup(): Backup = Backup(
        backupManga = OVERLAP.map { entry ->
            entry(entry, SyncPairLibrary.CHAPTERS) { number ->
                Triple(
                    number < SyncPairLibrary.READ || number == BACKUP_READ,
                    number == BACKUP_BOOKMARK,
                    BACKUP_PAGES[number] ?: 0L,
                )
            }
        } + RESTORE_ONLY.map { entry ->
            entry(entry, RESTORE_ONLY_CHAPTERS) { number ->
                Triple(number < RESTORE_ONLY_READ, number == RESTORE_ONLY_BOOKMARK, RESTORE_ONLY_PAGES[number] ?: 0L)
            }
        },
        backupCategories = SyncPairLibrary.CATEGORIES.mapIndexed { index, name ->
            BackupCategory(name = name, order = index.toLong(), id = index + 1L, flags = 0)
        },
    )

    /** One entry of [chapters] chapters, each read, bookmarked and on the page [state] gives its number. */
    private fun entry(entry: Int, chapters: Int, state: (Int) -> Triple<Boolean, Boolean, Long>): BackupManga = BackupManga(
        source = SyncPairLibrary.SOURCE_ID,
        url = SyncPairLibrary.entryUrl(entry),
        title = "Saikou sync pair entry $entry",
        dateAdded = DATE,
        favorite = true,
        initialized = true,
        categories = listOf((entry % SyncPairLibrary.CATEGORIES.size).toLong()),
        chapters = (0 until chapters).map { number ->
            val (read, bookmark, page) = state(number)
            BackupChapter(
                url = SyncPairLibrary.chapterUrl(entry, number),
                name = "Chapter ${number + 1}",
                read = read,
                bookmark = bookmark,
                lastPageRead = page,
                dateFetch = DATE,
                dateUpload = DATE + number * DAY,
                chapterNumber = (number + 1).toFloat(),
                sourceOrder = (chapters - 1 - number).toLong(),
            )
        },
    )

    /**
     * Every chapter of [OVERLAP] and [RESTORE_ONLY] as [expected] has it, one live row per url, and
     * nothing more on those entries. Reports what it checked, for the orchestrator's expectation.
     */
    suspend fun check(graph: AppGraph): Map<String, Any?> {
        val expected = expected()
        var read = 0
        var bookmarked = 0
        var paged = 0
        for (entry in OVERLAP + RESTORE_ONLY) {
            val chapters = graph.chapterRepository.getChapterByMangaId(entryId(graph, entry))
            val urls = chapters.map { it.url }
            assertEquals("entry $entry holds a chapter twice: $urls", urls.size, urls.toSet().size)
            val mine = expected.keys.filter { it.startsWith(SyncPairLibrary.entryUrl(entry) + "/") }.toSet()
            assertEquals("entry $entry's chapters", mine, urls.toSet())
            for (chapter in chapters) {
                val want = expected.getValue(chapter.url)
                assertEquals("${chapter.url} read", want.read, chapter.read)
                assertEquals("${chapter.url} bookmark", want.bookmark, chapter.bookmark)
                assertEquals("${chapter.url} last page", want.page, chapter.lastPageRead)
                if (chapter.read) read++
                if (chapter.bookmark) bookmarked++
                if (chapter.lastPageRead > 0) paged++
            }
        }
        return linkedMapOf(
            "checked" to expected.size,
            "read" to read,
            "bookmarked" to bookmarked,
            "paged" to paged,
        )
    }

    /** The local id of the favourite at [SyncPairLibrary.entryUrl] of [entry]. */
    private suspend fun entryId(graph: AppGraph, entry: Int): Long =
        checkNotNull(
            graph.mangaRepository.getFavorites().firstOrNull {
                it.source == SyncPairLibrary.SOURCE_ID && it.url == SyncPairLibrary.entryUrl(entry)
            },
        ) { "entry $entry is not a favourite on this device" }.id
}
