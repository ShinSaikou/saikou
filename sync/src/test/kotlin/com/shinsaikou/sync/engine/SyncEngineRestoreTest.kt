package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A restore (phase 3 unit E2, the plan's restore bullet): the importer leaves one flag, and the
 * next run forgets this device's pull cursors, pulls every other device again, makes a full export
 * and clears the flag. Challenge 2's case: a device that has synced restores an older backup that
 * brings back a manga the other device deleted, and merges read progress into rows that exist,
 * none of which a restore logs.
 */
class SyncEngineRestoreTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    /** What a restore does to rows that exist: writes under the latch, which logs nothing. */
    private fun SyncTestDevice.restoreWrites(vararg statements: String) {
        exec("UPDATE mangas SET is_syncing = 1")
        exec("UPDATE chapters SET is_syncing = 1")
        statements.forEach(::exec)
        exec("UPDATE chapters SET is_syncing = 0")
        exec("UPDATE mangas SET is_syncing = 0")
    }

    private fun exportIds(device: SyncTestDevice): List<String> = backend.paths()
        .mapNotNull { SyncLayout.parse(it) as? RemoteName.Done }
        .filter { it.deviceId == device.deviceId }
        .map { it.exportId }

    @Test
    fun `a restore resets the pull cursors, pulls again, makes a full export and clears the flag`() {
        a.buildLibrary()
        a.addFavourite(2)
        a.addChapter(20, mangaId = 2)
        a.sync()
        b.sync()
        a.sync()
        // A deletes manga 2; B applies the tombstone.
        a.exec("UPDATE mangas SET favorite = 0, deleted_at = strftime('%s', 'now') * 1000 WHERE _id = 2")
        a.sync()
        b.sync()
        val bManga2 = b.mangaId(SyncTestDevice.SOURCE, "/series/2")!!
        b.long("SELECT deleted_at IS NOT NULL FROM mangas WHERE _id = $bManga2") shouldBe 1
        val cursorBefore = b.preferences.pullCursors.get().getValue(a.deviceId)
        val bPushed = b.preferences.lastPushedSeq.get()
        val bLog = b.logCount()

        // B restores an older backup: manga 2 back as it was, and a chapter read that A has unread.
        val bChapter11 = b.chapterId(b.mangaId(SyncTestDevice.SOURCE, "/series/1")!!, "/series/1/chapter/11")!!
        b.restoreWrites(
            "UPDATE mangas SET deleted_at = NULL, favorite = 1 WHERE _id = $bManga2",
            "UPDATE chapters SET read = 1 WHERE _id = $bChapter11",
        )
        b.logCount() shouldBe bLog
        b.restorePreferences.restoredSinceSync.set(true)

        val result = b.sync().shouldNotBeNull()

        // B read A again from nothing: A's export and every segment after it, tombstone included.
        result.exportsApplied shouldBe 1
        b.preferences.pullCursors.get().getValue(a.deviceId) shouldBe cursorBefore
        // The favourite stays 1: the restore's flip stamped the favourite clock, whose trigger has no
        // guard (R85 carry-over 5), so it is newer than A's. The tombstone decides the row.
        b.long("SELECT deleted_at IS NOT NULL FROM mangas WHERE _id = $bManga2") shouldBe 1
        // And pushed a new export past everything A had read of it, so A applies it.
        result.exported shouldBe true
        exportIds(b) shouldHaveSize 2
        b.preferences.lastPushedSeq.get() shouldBe bPushed + 1
        b.restorePreferences.restoredSinceSync.get() shouldBe false
        b.preferences.exportedBackend.get() shouldBe backend.id

        a.sync().shouldNotBeNull().exportsApplied shouldBe 1
        a.long("SELECT read FROM chapters WHERE _id = 11") shouldBe 1
        a.library() shouldBe b.library()

        // A later run on B is an ordinary one.
        b.sync().shouldNotBeNull().exported shouldBe false
    }

    /** Sets the restore flag inside the first library read, as a restore finishing mid-export would. */
    private class RestoringStore(private val real: SyncStore, private val restore: () -> Unit) : SyncStore by real {
        private var restored = false

        override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows {
            if (!restored) {
                restored = true
                restore()
            }
            return real.readLibrary(mangaIds)
        }
    }

    @Test
    fun `a restore that finishes during a run is handled by the next run`() {
        a.addFavourite(1)
        a.sync()
        lateinit var c: SyncTestDevice
        c = SyncTestDevice(backend, storeWrapper = { RestoringStore(it) { c.restorePreferences.restoredSinceSync.set(true) } })
        c.use {
            c.restorePreferences.restoredSinceSync.set(true)

            // The flag is consumed before the pull; the one set during the export waits for the next run.
            c.sync().shouldNotBeNull().exported shouldBe true
            c.restorePreferences.restoredSinceSync.get() shouldBe true
            val second = c.sync().shouldNotBeNull()

            second.exported shouldBe true
            second.exportsApplied shouldBe 1
            exportIds(c) shouldHaveSize 2
            c.restorePreferences.restoredSinceSync.get() shouldBe false
            c.preferences.pullCursors.get()[a.deviceId] shouldNotBe null
        }
    }
}
