package com.shinsaikou.sync.engine

import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.format.CategoryRow
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.ExportDone
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The full export (phase 3 unit E2, the plan's full export bullet): a device's first push is its
 * whole library as `full-<exportId>/part-<n>` and `done.json`, taken after the pull; it fixes its
 * `lastSeq` from the log, prunes the log up to it and moves `lastPushedSeq` there; and a pusher that
 * restarts resumes the same export id. A reader whose cursor is -1 starts from it (R81 item 1).
 */
class SyncEngineExportTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    private fun exports(device: SyncTestDevice, on: InMemorySyncBackend = backend): List<String> =
        on.paths().mapNotNull { (SyncLayout.parse(it) as? RemoteName.Done)?.takeIf { d -> d.deviceId == device.deviceId } }
            .map { it.exportId }

    private fun done(device: SyncTestDevice, exportId: String, on: InMemorySyncBackend = backend): ExportDone =
        SyncLayout.decodeDone(on.bytes(SyncLayout.exportDonePath(device.deviceId, exportId))!!)

    /** [count] chapters of manga [mangaId], ids from [firstId], each in no category of its own. */
    private fun SyncTestDevice.addChapters(mangaId: Long, firstId: Long, count: Int) = exec(
        """
        WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${count - 1})
        INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo)
        SELECT $firstId + i, $mangaId, '/series/$mangaId/chapter/' || i, 'Chapter ' || i, 0, 0, 0, i, i,
            2000, 3000, CAST('{}' AS BLOB)
        FROM n
        """,
    )

    /** Three favourites of 2,000 chapters each, the third in a category of its own. */
    private fun SyncTestDevice.buildLargeLibrary() {
        addCategory(1, "First")
        addCategory(2, "Third")
        for (id in 1L..3L) {
            addFavourite(id)
            addChapters(id, firstId = id * 10_000, count = 2_000)
        }
        exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 1), (3, 2)")
    }

    @Test
    fun `a first push is a full export of the whole library, and later changes are segments after it`() {
        a.buildLibrary()
        val latest = a.latestSeq()

        val result = a.sync().shouldNotBeNull()

        result.exported shouldBe true
        result.segmentsPushed shouldBe 0
        val exportId = exports(a).single()
        done(a, exportId) shouldBe ExportDone(format = SyncFormat.CURRENT, parts = 1, lastSeq = latest)
        a.logCount() shouldBe 0
        a.preferences.lastPushedSeq.get() shouldBe latest
        a.preferences.exportedBackend.get() shouldBe backend.id
        a.preferences.exportProgress.get().shouldBeNull()
        val (header, rows) = backend.rowFile(SyncLayout.exportPartPath(a.deviceId, exportId, 1))
        header.scope shouldBe SyncScope.ExportPart(exportId = exportId, part = 1)
        rows.filterIsInstance<CategoryRow>().map { it.name } shouldContainExactly listOf("Parent", "Reading")
        rows.first() shouldBe rows.filterIsInstance<CategoryRow>().first()

        // B has never read A, so its cursor is -1, which only an export passes.
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        b.preferences.pullCursors.get()[a.deviceId] shouldBe latest
        b.library() shouldBe a.library()

        a.exec("UPDATE chapters SET last_page_read = 9 WHERE _id = 11")
        val next = a.sync().shouldNotBeNull()
        next.exported shouldBe false
        next.segmentsPushed shouldBe 1
        backend.bytes(SyncLayout.segmentPath(a.deviceId, latest, a.latestSeqPushed())).shouldNotBeNull()

        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1
        b.library() shouldBe a.library()
    }

    @Test
    fun `the export carries favourites, tombstones and manga once in the library, never one only browsed`() {
        a.addFavourite(1)
        a.addFavourite(2)
        a.exec("UPDATE mangas SET deleted_at = 5000, favorite = 0 WHERE _id = 2")
        a.addFavourite(3)
        a.exec("UPDATE mangas SET favorite = 0 WHERE _id = 3")
        // Only browsed: never in the library, so no favourite clock. The flip's trigger has no guard,
        // so the clock it stamps is cleared by a write that leaves the favourite alone.
        a.addFavourite(4)
        a.exec("UPDATE mangas SET is_syncing = 1 WHERE _id = 4")
        a.exec("UPDATE mangas SET favorite = 0 WHERE _id = 4")
        a.exec("UPDATE mangas SET favorite_modified_at = NULL, is_syncing = 0 WHERE _id = 4")

        a.sync()

        val exportId = exports(a).single()
        val (_, rows) = backend.rowFile(SyncLayout.exportPartPath(a.deviceId, exportId, 1))
        rows.filterIsInstance<MangaRow>().map { it.key.url } shouldContainExactly
            listOf("/series/1", "/series/2", "/series/3")
    }

    @Test
    fun `a library over one part's lines is split into parts of whole manga, each self-contained`() {
        a.buildLargeLibrary()

        a.sync()

        val exportId = exports(a).single()
        done(a, exportId).parts shouldBe 2
        val (_, first) = backend.rowFile(SyncLayout.exportPartPath(a.deviceId, exportId, 1))
        val (secondHeader, second) = backend.rowFile(SyncLayout.exportPartPath(a.deviceId, exportId, 2))
        first.filterIsInstance<CategoryRow>().map { it.key } shouldContainExactly
            listOf(CategorySyncKey.of("First"), CategorySyncKey.of("Third"))
        first.filterIsInstance<MangaRow>().map { it.key.url } shouldContainExactly listOf("/series/1", "/series/2")
        first.filterIsInstance<ChapterRow>().size shouldBe 4_000
        secondHeader.scope shouldBe SyncScope.ExportPart(exportId = exportId, part = 2)
        // The second part opens with the one category its set names.
        second.first() shouldBe first.filterIsInstance<CategoryRow>()[1]
        second.filterIsInstance<MangaRow>().map { it.key.url } shouldContainExactly listOf("/series/3")
        second.filterIsInstance<MangaCategoriesRow>().single().categories shouldBe setOf(CategorySyncKey.of("Third"))

        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        b.long("SELECT count(*) FROM chapters") shouldBe 6_000
        b.library() shouldBe a.library()
    }

    /** Fails the first write of any path [failOn] names, and counts every write by path. */
    private class FlakyBackend(private val delegate: InMemorySyncBackend, var failOn: (String) -> Boolean) :
        SyncBackend by delegate {
        val writes = mutableMapOf<String, Int>()

        override suspend fun create(path: String, content: SyncContent) {
            writes[path] = (writes[path] ?: 0) + 1
            if (failOn(path)) {
                failOn = { false }
                throw IOException("Connection reset writing $path")
            }
            delegate.create(path, content)
        }
    }

    @Test
    fun `a pusher that stops part way resumes the same export id at the next part`() {
        a.buildLargeLibrary()
        val flaky = FlakyBackend(backend) { it.endsWith("/part-2.jsonl.gz") }

        shouldThrow<IOException> { runBlocking { a.engine.run(flaky) } }

        val progress = a.preferences.exportProgress.get().shouldNotBeNull()
        progress.parts shouldBe 1
        a.preferences.exportedBackend.get() shouldBe ""
        exports(a) shouldBe emptyList()
        val lastSeq = progress.lastSeq
        a.logCount() shouldBe 0

        // A later write is logged after the export's seq and travels as a segment once it is done.
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 10000")
        runBlocking { a.engine.run(flaky) }.shouldNotBeNull().exported shouldBe true

        exports(a) shouldBe listOf(progress.exportId)
        done(a, progress.exportId) shouldBe ExportDone(format = SyncFormat.CURRENT, parts = 2, lastSeq = lastSeq)
        flaky.writes[SyncLayout.exportPartPath(a.deviceId, progress.exportId, 1)] shouldBe 1
        flaky.writes[SyncLayout.exportPartPath(a.deviceId, progress.exportId, 2)] shouldBe 2
        a.preferences.lastPushedSeq.get() shouldBe a.latestSeqPushed()
        backend.bytes(SyncLayout.segmentPath(a.deviceId, lastSeq, a.latestSeqPushed())).shouldNotBeNull()

        b.sync()
        b.library() shouldBe a.library()
    }

    @Test
    fun `a device whose last export went to another backend exports again there`() {
        a.buildLibrary()
        a.sync()
        val first = done(a, exports(a).single())
        val other = InMemorySyncBackend(id = "other")

        val result = runBlocking { a.engine.run(other) }.shouldNotBeNull()

        result.exported shouldBe true
        a.preferences.exportedBackend.get() shouldBe "other"
        // Nothing was logged since the first export, so this one ends one past it.
        done(a, exports(a, on = other).single(), on = other).lastSeq shouldBe first.lastSeq + 1
        a.preferences.lastPushedSeq.get() shouldBe first.lastSeq + 1
    }
}

/** The highest seq this device has pushed, which after a push is the log's counter. */
private fun SyncTestDevice.latestSeqPushed(): Long = preferences.lastPushedSeq.get()
