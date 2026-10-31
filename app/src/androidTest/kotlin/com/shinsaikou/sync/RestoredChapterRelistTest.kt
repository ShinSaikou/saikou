package com.shinsaikou.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.source.Source
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.merge.ChapterRecord
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Chapters
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * Phase 5 unit TG3, item 4 (R125 item 16, R113 carry-over 1): `SyncChaptersWithSource` on a device,
 * over a chapter a backup restore put there rather than a fresh add. A restore inserts its chapters
 * under the sync latch with no change-log row (phase 5 unit SC2), so the first edit the chapter sync
 * makes to one of them is that row's first logged change; it has to land on the restored row (one
 * row per url, never a second one beside it), move its version once, and so leave the other device's
 * one independent edit of the same chapter tied with it rather than outranked. The merge takes a
 * chapter's source values from the strictly higher version (`MergeEngine.mergeChapter`).
 *
 * The fixture source is this class's own [ListingSource] on [SOURCE_ID], served to the app's own
 * refresh ([AppGraph.refreshMangaFromSource], the manga screen's), so no request leaves the device.
 * Each method's entry has its own url and is soft-deleted afterwards.
 *
 * [aRestoredChapterTheSourceListsChangedIsOneRowMovedOnce]: the source lists the restored chapter
 * again under its url with a new name. [aRestoredDownloadedChapterTheSourceDropsAndListsAgainIsOneRowMovedOnceForEachEdit]
 * is R113 carry-over 1's shape: the restored chapter is downloaded, the source drops it (a soft
 * delete, one edit), then lists it again renamed (a relisting, one edit). R120 item 3 found the
 * double bump that carry-over describes was never there and R120 item 1 made the relisting one
 * update; this is the device's assertion of both.
 */
@RunWith(AndroidJUnit4::class)
class RestoredChapterRelistTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    private val entryUrl = "/fixture/relist-${System.nanoTime()}"
    private var entryId = -1L

    /** The fixture source: [chapters] for [entryUrl], whatever it is asked. */
    private inner class ListingSource(private val chapters: List<ChapterInfo>) : Source {
        override val id = SOURCE_ID
        override val name = "Saikou relist fixture"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo) = manga.copy(initialized = true)
        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> {
            check(manga.url == entryUrl) { "the fixture source lists no entry at ${manga.url}" }
            return chapters
        }
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = error("the fixture source serves no pages")
        override suspend fun getImage(page: PageInfo): ImageResult = error("the fixture source serves no pages")
    }

    @Before
    fun noSourceServesTheFixture() {
        assertNull("the relist fixture's source id is installed", graph.sourceManager.get(SOURCE_ID))
    }

    @After
    fun removeTheEntry() {
        if (entryId > 0) runBlocking { graph.mangaRepository.softDelete(entryId, System.currentTimeMillis()) }
    }

    @Test
    fun aRestoredChapterTheSourceListsChangedIsOneRowMovedOnce() {
        runBlocking { relistRestored() }
    }

    @Test
    fun aRestoredDownloadedChapterTheSourceDropsAndListsAgainIsOneRowMovedOnceForEachEdit() {
        runBlocking { dropAndRelistRestored() }
    }

    private suspend fun relistRestored() {
        restore()
        val before = row(chapterUrl(RELISTED))
        assertEquals("a restored chapter's latch is still raised", 0L, before.is_syncing)
        val seq = latestSeq()

        refresh(listing(relistedName = RENAMED))

        val after = assertOneRow(chapterUrl(RELISTED))
        assertEquals(RENAMED, after.name)
        assertEquals("the relisting moved the version other than once", before.version + 1, after.version)
        assertEquals("the relisting's change-log rows", listOf("upsert"), changesOf(after._id, seq))
        // Progress the restore wrote is the chapter's own, and the refresh sends source fields only.
        assertEquals(listOf(true, true, 7L), listOf(after.read, after.bookmark, after.last_page_read))
        assertNotOutranked(before, after, edits = 1)
    }

    private suspend fun dropAndRelistRestored() {
        restore()
        val before = row(chapterUrl(RELISTED))
        download(before)
        val seq = latestSeq()

        refresh(listing(relistedName = null))
        val dropped = row(chapterUrl(RELISTED))
        val tombstone = checkNotNull(dropped.deleted_at) { "a downloaded chapter the source dropped was not soft deleted" }
        assertEquals("the soft delete moved the version other than once", before.version + 1, dropped.version)

        refresh(listing(relistedName = RENAMED))

        val after = assertOneRow(chapterUrl(RELISTED))
        assertEquals("the relisting is not the restored row", before._id, after._id)
        assertEquals(RENAMED, after.name)
        assertNull("the relisting left the tombstone", after.deleted_at)
        assertTrue("the restore clock is not past the tombstone", (after.restored_at ?: 0L) > tombstone)
        assertEquals("the relisting moved the version other than once", dropped.version + 1, after.version)
        assertEquals("the drop's and the relisting's change-log rows", listOf("delete", "upsert"), changesOf(after._id, seq))
        assertNotOutranked(before, after, edits = 2)
    }

    /**
     * The other device made as many source edits of the same chapter, from the same synced row, as
     * this one did ([edits]): a different scanlator, each edit one version. This device's row must
     * not hold a higher version than that, and the two devices' merges must agree.
     */
    private fun assertNotOutranked(before: Chapters, after: Chapters, edits: Int) {
        val here = record(after)
        val other = record(before).copy(version = before.version + edits, scanlator = OTHER_GROUP)
        assertTrue(
            "the chapter sync's version ${here.version} outranks the other device's $edits edits at ${other.version}",
            here.version <= other.version,
        )
        val merge = MergeEngine()
        assertEquals("the two devices' merges disagree", merge.mergeChapter(here, other).value, merge.mergeChapter(other, here).value)
    }

    /** One backup entry on [SOURCE_ID] of [CHAPTERS] chapters, [RELISTED] read, bookmarked and on page 7. */
    private suspend fun restore() {
        val backup = Backup(
            backupManga = listOf(
                BackupManga(
                    source = SOURCE_ID,
                    url = entryUrl,
                    title = "Saikou relist fixture",
                    dateAdded = DATE,
                    favorite = true,
                    initialized = true,
                    chapters = (1..CHAPTERS).map { number ->
                        BackupChapter(
                            url = chapterUrl(number),
                            name = "Chapter $number",
                            read = number == RELISTED,
                            bookmark = number == RELISTED,
                            lastPageRead = if (number == RELISTED) 7L else 0L,
                            dateFetch = DATE,
                            dateUpload = DATE + number * DAY,
                            chapterNumber = number.toFloat(),
                            sourceOrder = (CHAPTERS - number).toLong(),
                        )
                    },
                ),
            ),
        )
        val file = File(context.cacheDir, "relist-${System.nanoTime()}.tachibk")
        file.writeBytes(ProtoBuf.encodeToByteArray(Backup.serializer(), backup))
        try {
            val options = RestoreOptions(appSettings = false, extensionStores = false, sourceSettings = false)
            val report = graph.backupImporter.import(file, options) {}
            assertTrue("the restore reported errors: ${report.errors}", report.errors.isEmpty())
            assertEquals(CHAPTERS, report.chaptersWritten)
        } finally {
            file.delete()
        }
        entryId = entry().id
        assertEquals("the restore logged its chapters", 0, changesAfterEntry())
    }

    /** The source's listing: every chapter as restored, [RELISTED] renamed to [relistedName], or left out when null. */
    private fun listing(relistedName: String?): List<ChapterInfo> = (CHAPTERS downTo 1).mapNotNull { number ->
        val name = if (number == RELISTED) relistedName ?: return@mapNotNull null else "Chapter $number"
        ChapterInfo(url = chapterUrl(number), name = name, chapterNumber = number.toFloat(), dateUpload = DATE + number * DAY)
    }

    private suspend fun refresh(chapters: List<ChapterInfo>) {
        graph.refreshMangaFromSource(ListingSource(chapters), entry(), fetchChapters = true, manualFetch = true)
    }

    private suspend fun entry(): Manga = checkNotNull(
        graph.mangaRepository.getFavorites().singleOrNull { it.source == SOURCE_ID && it.url == entryUrl },
    ) { "the restored entry is not a favourite" }

    /** A manifest row, so the source's drop soft deletes the chapter as it does a downloaded one. */
    private suspend fun download(chapter: Chapters) {
        graph.downloadManifestRepository.recordDownload(
            DownloadManifestEntry(
                id = 0,
                chapterId = chapter._id,
                mangaId = entryId,
                sourceId = SOURCE_ID,
                mangaTitle = "Saikou relist fixture",
                mangaUrl = entryUrl,
                chapterUrl = chapter.url,
                chapterName = chapter.name,
                scanlator = null,
                chapterDirName = chapter.name,
                pageCount = 1,
                sizeBytes = 0,
                dateDownloaded = DATE,
            ),
        )
    }

    /** Every row of the entry at [url], deleted or not. */
    private suspend fun rows(url: String): List<Chapters> =
        graph.database.chaptersQueries.syncGetByMangaId(entryId).awaitAsList().filter { it.url == url }

    private suspend fun row(url: String): Chapters = rows(url).single()

    private suspend fun assertOneRow(url: String): Chapters {
        val rows = rows(url)
        assertEquals("rows at $url: ${rows.map { it._id }}", 1, rows.size)
        return rows.single()
    }

    private suspend fun latestSeq(): Long = graph.database.sync_change_logQueries.getLatestSeq().awaitAsOne()

    /** The change types logged for chapter [id] after [seq], oldest first. */
    private suspend fun changesOf(id: Long, seq: Long): List<String> =
        graph.database.sync_change_logQueries.getAfter(seq, Long.MAX_VALUE).awaitAsList()
            .filter { it.entity_type == "chapter" && it.entity_id == id }
            .map { it.change_type }

    /** Change-log rows for any chapter of the entry, in the whole log. */
    private suspend fun changesAfterEntry(): Int {
        val ids = graph.database.chaptersQueries.syncGetByMangaId(entryId).awaitAsList().map { it._id }.toSet()
        return graph.database.sync_change_logQueries.getAfter(0, Long.MAX_VALUE).awaitAsList()
            .count { it.entity_type == "chapter" && it.entity_id in ids }
    }

    private fun record(row: Chapters) = ChapterRecord(
        key = ChapterKey(MangaKey(SOURCE_ID, entryUrl), row.url),
        read = row.read,
        bookmark = row.bookmark,
        lastPageRead = row.last_page_read,
        dateFetch = row.date_fetch,
        version = row.version,
        name = row.name,
        scanlator = row.scanlator,
        chapterNumber = row.chapter_number,
        dateUpload = row.date_upload,
        volume = row.volume,
        deletedAt = row.deleted_at,
        restoredAt = row.restored_at,
        bookmarkNotes = emptyMap(),
        bookmarkNotesModifiedAt = row.bookmark_notes_modified_at,
    )

    private fun chapterUrl(number: Int) = "$entryUrl/c$number"

    private companion object {
        /** The next device fixture id after R131 item 6's `...010L`; no extension carries it. */
        const val SOURCE_ID = 7_310_000_000_000_011L
        const val CHAPTERS = 5
        const val RELISTED = 3
        const val RENAMED = "Chapter 3: renamed by its source"
        const val OTHER_GROUP = "Other device group"
        const val DATE = 1_758_585_600_000L
        const val DAY = 86_400_000L
    }
}
