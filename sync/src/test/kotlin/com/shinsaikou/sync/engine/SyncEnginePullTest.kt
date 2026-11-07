package com.shinsaikou.sync.engine

import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The pull (phase 3 unit E2, the plan's pull bullet), over segments: two devices on one in-memory
 * backend, each on a real database. Full exports are stage 2's, so a reader here starts from
 * cursor 0 for the other device, as it will once that device's first export has applied.
 */
class SyncEnginePullTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend, exported = true)
    private val b = SyncTestDevice(backend, exported = true)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    /** A syncs, and B starts reading A from its first segment. */
    private fun aPushesAndBReads() {
        a.sync()
        b.startReading(a.deviceId)
    }

    @Test
    fun `a pull applies every synced table, raises no latch it leaves, and pushes nothing back`() {
        a.buildLibrary()
        aPushesAndBReads()

        val result = b.sync().shouldNotBeNull()

        result.segmentsApplied shouldBe 1
        result.segmentsPushed shouldBe 0
        b.library() shouldBe a.library()
        b.logCount() shouldBe 0
        b.latchesRaised() shouldBe 0
        b.preferences.pullCursors.get() shouldBe mapOf(a.deviceId to a.preferences.lastPushedSeq.get())
        runBlocking { backend.list(SyncLayout.devicePrefix(b.deviceId)) }.shouldBeEmpty()
    }

    @Test
    fun `a pull of rows it already holds writes nothing`() {
        a.buildLibrary()
        aPushesAndBReads()
        b.sync()
        val before = b.library()
        b.startReading(a.deviceId)

        val again = b.sync().shouldNotBeNull()

        again.segmentsApplied shouldBe 1
        again.rowsWritten shouldBe 0
        b.library() shouldBe before
        b.logCount() shouldBe 0
    }

    @Test
    fun `a pulled entry counts as fetched when any of its details arrived, and a row already here keeps its own flag`() {
        a.addFavourite(1)
        a.exec("UPDATE mangas SET description = 'About 1', initialized = 1 WHERE _id = 1")
        a.addFavourite(2)
        a.exec("UPDATE mangas SET genre = 'Drama', initialized = 1 WHERE _id = 2")
        a.addFavourite(3)
        a.addFavourite(4)
        a.exec("UPDATE mangas SET author = 'Author 4', initialized = 1 WHERE _id = 4")
        b.exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added, memo)
            VALUES (40, ${SyncTestDevice.SOURCE}, '/series/4', 'Title 4', 0, 0, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        aPushesAndBReads()

        b.sync()

        assertSoftly {
            b.long("SELECT initialized FROM mangas WHERE url = '/series/1'") shouldBe 1L
            b.long("SELECT initialized FROM mangas WHERE url = '/series/2'") shouldBe 1L
            b.long("SELECT initialized FROM mangas WHERE url = '/series/3'") shouldBe 0L
            b.long("SELECT initialized FROM mangas WHERE _id = 40") shouldBe 0L
        }
    }

    @Test
    fun `a manga is matched through the source aliases, as a restore matches it`() {
        a.addFavourite(1, source = 100)
        a.addChapter(10, mangaId = 1)
        b.exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added, memo)
            VALUES (5, 200, '/series/1', 'Old title', 0, 0, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        b.exec("INSERT INTO source_alias(old_source_id, new_source_id, date_added) VALUES (100, 200, 0)")
        aPushesAndBReads()

        b.sync()

        b.long("SELECT count(*) FROM mangas") shouldBe 1
        b.long("SELECT favorite FROM mangas WHERE _id = 5") shouldBe 1
        b.chapterId(5, "/series/1/chapter/10").shouldNotBeNull()
    }

    @Test
    fun `the favourite clock stored is the merged one, not the time the apply wrote the favourite`() {
        listOf(a, b).forEach { device ->
            device.exec(
                """
                INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                    cover_last_modified, date_added, memo)
                VALUES (1, ${SyncTestDevice.SOURCE}, '/series/1', 'Title', 0, 0, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
                """,
            )
        }
        a.exec("UPDATE mangas SET favorite = 1 WHERE _id = 1")
        // Well before the apply's own time, which update_last_favorited_at_mangas would stamp.
        a.exec("UPDATE mangas SET favorite_modified_at = 1234 WHERE _id = 1")
        aPushesAndBReads()

        b.sync()

        b.long("SELECT favorite FROM mangas WHERE _id = 1") shouldBe 1
        b.long("SELECT favorite_modified_at FROM mangas WHERE _id = 1") shouldBe 1234
    }

    @Test
    fun `tombstones and restores are written back as the merge returns them`() {
        listOf(a, b).forEach { device ->
            device.addFavourite(1)
            device.addChapter(10, mangaId = 1)
            device.addChapter(11, mangaId = 1)
        }
        a.sync()
        b.exec("UPDATE chapters SET restored_at = 6000 WHERE _id IN (10, 11)")
        // Chapter 10's tombstone is newer than B's restore; chapter 11's is older, so the restore beats it.
        a.exec("UPDATE chapters SET deleted_at = 7000 WHERE _id = 10")
        a.exec("UPDATE chapters SET deleted_at = 5000 WHERE _id = 11")
        a.sync()
        b.startReading(a.deviceId, seq = 3)

        b.sync()

        b.rows("SELECT _id, deleted_at, restored_at FROM chapters ORDER BY _id", 3) shouldBe listOf(
            listOf("10", "7000", "6000"),
            listOf("11", null, "6000"),
        )
    }

    @Test
    fun `a chapter_removed line tombstones the chapter at its removedAt, unless a newer restore beats it`() {
        listOf(a, b).forEach { device ->
            device.addFavourite(1)
            device.addChapter(10, mangaId = 1)
            device.addChapter(11, mangaId = 1)
        }
        a.sync()
        b.sync()
        a.exec("DELETE FROM chapters WHERE _id IN (10, 11)")
        val removedAt = a.long(
            "SELECT changed_at FROM sync_change_log WHERE key_chapter_url = '/series/1/chapter/10'",
        )!!
        b.exec("UPDATE chapters SET restored_at = ${removedAt + 60_000} WHERE _id = 11")
        a.sync()
        b.startReading(a.deviceId, seq = 3)

        b.sync().shouldNotBeNull().segmentsPushed shouldBe 0

        b.rows("SELECT _id, deleted_at FROM chapters ORDER BY _id", 2) shouldBe listOf(
            listOf("10", removedAt.toString()),
            listOf("11", null),
        )
    }

    @Test
    fun `a chapter this device dropped and has not pushed is stored as its tombstone when a pull brings it back`() {
        listOf(a, b).forEach { device ->
            device.addFavourite(1)
            device.addChapter(10, mangaId = 1)
            device.addChapter(11, mangaId = 1)
        }
        // B's library update drops chapter 10 while A reads it; A's segment reaches B first.
        b.exec("DELETE FROM chapters WHERE _id = 10")
        val removedAt = b.long("SELECT changed_at FROM sync_change_log WHERE key_chapter_url = '/series/1/chapter/10'")!!
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        aPushesAndBReads()

        b.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        a.startReading(b.deviceId)

        // Stored as the removal B is pushing, with A's read; not live, which would keep it on B only.
        b.rows("SELECT url, read, deleted_at FROM chapters WHERE url = '/series/1/chapter/10'", 3) shouldBe
            listOf(listOf("/series/1/chapter/10", "1", removedAt.toString()))
        a.sync()
        a.rows("SELECT read, deleted_at FROM chapters WHERE _id = 10", 2) shouldBe listOf(listOf("1", removedAt.toString()))
    }

    @Test
    fun `memberships to a category that merges to deleted are dropped`() {
        listOf(a, b).forEach { device ->
            device.addFavourite(1)
            device.addCategory(1, "Reading")
            device.exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 1)")
        }
        a.sync()
        b.sync()
        a.exec("DELETE FROM mangas_categories WHERE category_id = 1")
        a.exec("UPDATE categories SET deleted_at = 9000000000000 WHERE _id = 1")
        a.sync()
        b.startReading(a.deviceId, seq = 3)

        b.sync().shouldNotBeNull().segmentsPushed shouldBe 0

        b.long("SELECT deleted_at FROM categories WHERE _id = 1") shouldBe 9_000_000_000_000
        b.long("SELECT count(*) FROM mangas_categories") shouldBe 0
    }

    @Test
    fun `the category key backfill runs before an apply, so an unkeyed category matches by its name`() {
        a.addCategory(1, "Reading")
        // As the migration to version 2 left it, before the start-up sweep committed.
        b.exec("INSERT INTO categories(_id, name, sort, flags) VALUES (7, 'reading ', 0, 0)")
        aPushesAndBReads()

        b.sync()

        b.rows("SELECT _id, sync_key FROM categories WHERE _id > 0", 2) shouldBe
            listOf(listOf("7", CategorySyncKey.of("Reading")))
    }

    @Test
    fun `a newer format stops the pull before any row and moves no cursor`() {
        a.addFavourite(1)
        a.sync()
        b.addFavourite(2)
        b.startReading(a.deviceId)
        val bLog = b.logCount()
        // A segment from a later build of A, after the one this build can read.
        backend.hide(SyncLayout.segmentPath(a.deviceId, 0, 1))
        runBlocking {
            backend.create(SyncLayout.segmentPath(a.deviceId, 0, 2), SyncContent(newerFormatFile(a.deviceId)))
        }

        shouldThrow<NewerFormatException> { b.sync() }.found shouldBe 4

        b.preferences.pullCursors.get() shouldBe mapOf(a.deviceId to 0L)
        b.preferences.lastPushedSeq.get() shouldBe 0
        b.logCount() shouldBe bLog
        b.mangaId(SyncTestDevice.SOURCE, "/series/1") shouldBe null
    }

    @Test
    fun `each segment moves its device's cursor, stops at a listing gap, and the cursors survive a restart`() {
        a.addFavourite(1)
        a.sync()
        a.addFavourite(2)
        a.sync()
        a.addFavourite(3)
        a.sync()
        b.startReading(a.deviceId)
        backend.hide(SyncLayout.segmentPath(a.deviceId, 1, 2))

        val first = b.sync().shouldNotBeNull()

        first.segmentsApplied shouldBe 1
        first.stoppedAtGap shouldBe true
        b.preferences.pullCursors.get() shouldBe mapOf(a.deviceId to 1L)
        b.mangaId(SyncTestDevice.SOURCE, "/series/3") shouldBe null
        SyncPreferences(MapPreferenceStore(b.preferenceValues)).pullCursors.get() shouldBe mapOf(a.deviceId to 1L)

        backend.unhide(SyncLayout.segmentPath(a.deviceId, 1, 2))
        val second = b.sync().shouldNotBeNull()

        second.segmentsApplied shouldBe 2
        second.stoppedAtGap shouldBe false
        b.preferences.pullCursors.get() shouldBe mapOf(a.deviceId to 3L)
        b.mangaId(SyncTestDevice.SOURCE, "/series/3") shouldNotBe null
    }

    @Test
    fun `a device never read stays unread until its first export, since segments start above -1`() {
        a.addFavourite(1)
        a.sync()

        val result = b.sync().shouldNotBeNull()

        result.segmentsApplied shouldBe 0
        result.stoppedAtGap shouldBe true
        b.preferences.pullCursors.get() shouldBe emptyMap()
    }

    /** A gzip JSON Lines file whose header names format 3 and whose one row this build cannot know. */
    private fun newerFormatFile(deviceId: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use { writer ->
            writer.write(
                """{"format":4,"deviceId":"$deviceId","scope":{"kind":"segment","afterSeq":0,"lastSeq":2,"rawRows":2},""" +
                    """"counts":{"manga_v2":1}}""",
            )
            writer.write("\n")
            writer.write("""{"entity":"manga_v2","key":{"source":1,"url":"/series/9"}}""")
            writer.write("\n")
        }
        return bytes.toByteArray()
    }
}
