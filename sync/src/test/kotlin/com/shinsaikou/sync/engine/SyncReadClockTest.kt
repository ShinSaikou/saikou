package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * An unread and a removed bookmark across two devices (R165 item 1), over two real databases. The
 * owner's marks are plain SQL updates, so `update_chapter_read_modified_at` and
 * `update_chapter_bookmark_modified_at` stamp them as they do for the app's own writes; the apply
 * stores the merged clocks under the latch, so a pulled mark is never stamped again here.
 */
class SyncReadClockTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @BeforeEach
    fun shareReadAndBookmarkedChapters() {
        a.addFavourite(1)
        (1L..3L).forEach { a.addChapter(it, mangaId = 1) }
        a.exec("UPDATE chapters SET read = 1, bookmark = 1")
        a.sync()
        b.sync()
        b.flags() shouldBe a.flags()
        b.flags().map { it.drop(1) }.toSet() shouldBe setOf(listOf("1", "1"))
    }

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    @Test
    fun `an unread and an unbookmark on B reach A and stay on both`() {
        b.exec("UPDATE chapters SET read = 0 WHERE url = '${url(1)}'")
        b.exec("UPDATE chapters SET bookmark = 0 WHERE url = '${url(2)}'")

        syncBothWays()

        for (device in listOf(a, b)) {
            device.flags() shouldBe listOf(
                listOf(url(1), "0", "1"),
                listOf(url(2), "1", "0"),
                listOf(url(3), "1", "1"),
            )
        }
        // The apply stored B's clocks as they were: the pull wrote under the latch, and no trigger
        // stamped the merged values again.
        a.clocks() shouldBe b.clocks()
        a.long("SELECT read_modified_at FROM chapters WHERE url = '${url(1)}'")!! shouldBeGreaterThan 0L
        converged()
    }

    @Test
    fun `an accidental mark as read undone on one device is undone on both, beside the other's page commits`() {
        b.exec("UPDATE chapters SET read = 0")
        a.exec("UPDATE chapters SET last_page_read = 5 WHERE url = '${url(3)}'")

        syncBothWays()

        for (device in listOf(a, b)) {
            device.long("SELECT count(*) FROM chapters WHERE read = 1") shouldBe 0L
            device.long("SELECT last_page_read FROM chapters WHERE url = '${url(3)}'") shouldBe 5L
        }
        converged()
    }

    @Test
    fun `a pulled unread logs nothing on the device that pulls it`() {
        b.exec("UPDATE chapters SET read = 0 WHERE url = '${url(1)}'")
        b.sync()
        val logBefore = a.latestSeq()

        a.sync()

        a.long("SELECT read FROM chapters WHERE url = '${url(1)}'") shouldBe 0L
        a.long("SELECT count(*) FROM sync_change_log WHERE seq > $logBefore AND entity_type = 'chapter'") shouldBe 0L
    }

    /**
     * A file a format 3 build wrote has no clocks, and its chapter lines read as clocks 0. Against
     * rows whose clocks are 0 too, every row a device held before this version, read and bookmark
     * merge as they did before: the OR. A mark this device stamped after updating beats such a line.
     */
    @Test
    fun `a format 3 file merges as before, by OR, and loses to a mark stamped since`() {
        // A backend of its own, so the only file c reads is the format 3 one.
        val older = InMemorySyncBackend()
        val c = SyncTestDevice(older, exported = true)
        try {
            c.addFavourite(1)
            (1L..4L).forEach { c.addChapter(it, mangaId = 1) }
            // Chapter 3 read before this version: under the latch, so its clock stays 0, as migrated.
            c.exec("UPDATE chapters SET is_syncing = 1 WHERE url = '${url(3)}'")
            c.exec("UPDATE chapters SET read = 1 WHERE url = '${url(3)}'")
            c.exec("UPDATE chapters SET is_syncing = 0 WHERE url = '${url(3)}'")
            // Chapter 4 read, then marked unread on this version: a stamped clock.
            c.exec("UPDATE chapters SET read = 1 WHERE url = '${url(4)}'")
            c.exec("UPDATE chapters SET read = 0 WHERE url = '${url(4)}'")
            val foreign = "11111111-2222-3333-4444-555555555555"
            runBlocking {
                older.create(
                    SyncLayout.segmentPath(foreign, 0, 4),
                    SyncContent(
                        rawFile(
                            """{"format":3,"deviceId":"$foreign","scope":{"kind":"segment","afterSeq":0,"lastSeq":4,"rawRows":4},"counts":{"chapter":4}}""",
                            format3Chapter(1, read = true, bookmark = false),
                            format3Chapter(2, read = false, bookmark = true),
                            format3Chapter(3, read = false, bookmark = false),
                            format3Chapter(4, read = true, bookmark = false),
                        ),
                    ),
                )
            }
            c.startReading(foreign)

            c.sync().shouldNotBeNull().rowsSkipped shouldBe 0

            c.flags() shouldBe listOf(
                listOf(url(1), "1", "0"),
                listOf(url(2), "0", "1"),
                listOf(url(3), "1", "0"),
                listOf(url(4), "0", "0"),
            )
            c.long(
                "SELECT count(*) FROM chapters WHERE url != '${url(4)}' AND read_modified_at + bookmark_modified_at != 0",
            ) shouldBe 0L
            c.latchesRaised() shouldBe 0
        } finally {
            c.close()
        }
    }

    private fun url(chapter: Long) = "/series/1/chapter/$chapter"

    /** A chapter line as a format 3 build wrote it, for chapter [id] as [SyncTestDevice.addChapter] makes it. */
    private fun format3Chapter(id: Long, read: Boolean, bookmark: Boolean) =
        """{"entity":"chapter","key":{"manga":{"source":${SyncTestDevice.SOURCE},"url":"/series/1"},"url":"${url(id)}"},""" +
            """"read":$read,"bookmark":$bookmark,"lastPageRead":0,"dateFetch":2000,"version":0,"name":"Chapter $id",""" +
            """"scanlator":null,"chapterNumber":$id.0,"dateUpload":3000,"volume":null,"sourceOrder":$id,""" +
            """"deletedAt":null,"restoredAt":null}"""

    private fun SyncTestDevice.flags(): List<List<String?>> =
        rows("SELECT url, read, bookmark FROM chapters ORDER BY url", 3)

    private fun SyncTestDevice.clocks(): List<List<String?>> =
        rows("SELECT url, read_modified_at, bookmark_modified_at FROM chapters ORDER BY url", 3)

    private fun syncBothWays() {
        a.sync()
        b.sync()
        a.sync()
    }

    private fun converged() {
        a.library() shouldBe b.library()
        a.latchesRaised() shouldBe 0
        b.latchesRaised() shouldBe 0
        runBlocking { SyncDigest.of(a.store).sha256 shouldBe SyncDigest.of(b.store).sha256 }
    }
}
