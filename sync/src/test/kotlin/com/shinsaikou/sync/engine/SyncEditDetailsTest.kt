package com.shinsaikou.sync.engine

import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncDigest
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File

/**
 * The owner's title and description edit across two devices (phase 5 unit ED, R138 item 2), over
 * two real databases. An edit goes through `SetMangaDetailsOverride` and a source refresh through
 * the repository's `update`, the write `UpdateManga.awaitUpdateFromSource` makes, so every trigger
 * fires as in the app. The overrides merge under their own clock, as the notes do; the source's
 * values under them keep following the version.
 */
class SyncEditDetailsTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @TempDir
    lateinit var files: File

    @BeforeEach
    fun shareTheEntry() {
        a.addFavourite(1, title = "Source title")
        a.exec("UPDATE mangas SET description = 'Source text' WHERE _id = 1")
        a.sync()
        b.sync()
        b.shown() shouldBe Shown("Source title", "Source text", "Source title")
    }

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    @Test
    fun `an edit on A against source refreshes on B keeps the edit and takes B's source text under it`() {
        a.edit("Edited title", "Edited text")
        // A manual refresh, then a library update, each bringing a change: B's version passes A's.
        b.refresh(title = "Renamed by the source", description = "Source text")
        b.refresh(title = "Renamed by the source", description = "New source text")

        syncBothWays()

        for (device in listOf(a, b)) {
            device.shown() shouldBe Shown("Edited title", "Edited text", "Renamed by the source")
            device.string("SELECT description FROM mangas WHERE _id = 1") shouldBe "New source text"
        }
        converged()
    }

    @Test
    fun `an edit and one refresh tie on the version, and the edit still wins on both devices`() {
        a.edit("Edited title", "Edited text")
        b.refresh(title = "Renamed by the source", description = "Source text")

        syncBothWays()

        // The source's text on a version tie goes by encoding (R82 item 2), the same on both; the
        // next refresh writes the source's current text again. The edit is decided by its clock.
        a.shown().title shouldBe "Edited title"
        b.shown().title shouldBe "Edited title"
        a.shown() shouldBe b.shown()
        converged()
    }

    @Test
    fun `an edit on both devices takes the later clock's, both fields together`() {
        a.edit("A's title", "A's text")
        b.edit("B's title", "Source text")
        b.advanceDetailsClock(60_000)

        syncBothWays()

        a.shown() shouldBe Shown("B's title", "Source text", "Source title")
        b.shown() shouldBe Shown("B's title", "Source text", "Source title")
        a.string("SELECT description_override FROM mangas WHERE _id = 1") shouldBe null
        converged()
    }

    @Test
    fun `a revert on A against an older edit on B shows the source's text on both`() {
        a.edit("Edited title", "Edited text")
        syncBothWays()
        b.edit("B's later title", "Edited text")
        a.reset()
        a.advanceDetailsClock(60_000)

        syncBothWays()

        a.shown() shouldBe Shown("Source title", "Source text", "Source title")
        b.shown() shouldBe Shown("Source title", "Source text", "Source title")
        converged()
    }

    @Test
    fun `a restore on B, whose file carries no edit, leaves A's edit standing on both`() {
        a.edit("Edited title", "Edited text")
        a.sync()

        // B restores a file of the same entry, the source's text in it as every file Saikou writes.
        val file = Backup(
            backupManga = listOf(
                BackupManga(
                    source = SyncTestDevice.SOURCE, url = "/series/1", title = "Source title",
                    description = "Source text", favorite = true, version = 1,
                ),
            ),
        )
        b.restore(file.bytes(), files)
        b.shown() shouldBe Shown("Source title", "Source text", "Source title")

        b.sync()
        a.sync()

        a.shown() shouldBe Shown("Edited title", "Edited text", "Source title")
        b.shown() shouldBe Shown("Edited title", "Edited text", "Source title")
        converged()
    }

    /**
     * Another reader's file carries an edit at fields 800 and 804 (R162 item 8). The restore writes it
     * under the row's latch with no clock, as it writes the notes, so against A, which never edited the
     * entry, the clocks tie and the edit's encoding wins on both devices.
     */
    @Test
    fun `a restore on B of another reader's edit shows that edit on both`() {
        b.restore(otherReadersFile().bytes(), files)
        b.shown() shouldBe Shown("Their title", "Their text", "Source title")

        b.sync()
        a.sync()

        a.shown() shouldBe Shown("Their title", "Their text", "Source title")
        b.shown() shouldBe Shown("Their title", "Their text", "Source title")
        converged()
    }

    @Test
    fun `an edit made on A wins over another reader's edit restored on B`() {
        a.edit("Edited title", "Edited text")
        a.sync()

        // B has not pulled A's edit yet, so its row has none and takes the file's.
        b.restore(otherReadersFile().bytes(), files)
        b.shown() shouldBe Shown("Their title", "Their text", "Source title")

        b.sync()
        a.sync()

        a.shown() shouldBe Shown("Edited title", "Edited text", "Source title")
        b.shown() shouldBe Shown("Edited title", "Edited text", "Source title")
        converged()
    }

    private fun otherReadersFile() = Backup(
        backupManga = listOf(
            BackupManga(
                source = SyncTestDevice.SOURCE, url = "/series/1", title = "Source title",
                description = "Source text", favorite = true, version = 1,
                customTitle = "Their title", customDescription = "Their text",
            ),
        ),
    )

    /** What the entry screen shows (title, description) and the source's title under it. */
    private data class Shown(val title: String?, val description: String?, val sourceTitle: String?)

    private fun SyncTestDevice.shown(): Shown = rows(
        """
        SELECT coalesce(title_override, title), coalesce(description_override, description), title
        FROM mangas WHERE _id = 1
        """,
        3,
    ).single().let { (title, description, source) -> Shown(title, description, source) }

    private fun SyncTestDevice.edit(title: String, description: String) = runBlocking {
        SetMangaDetailsOverride(MangaRepositoryImpl(handler)).await(1, title, description) shouldBe true
    }

    private fun SyncTestDevice.reset() = runBlocking {
        SetMangaDetailsOverride(MangaRepositoryImpl(handler)).awaitReset(1) shouldBe true
    }

    private fun SyncTestDevice.refresh(title: String, description: String) = runBlocking {
        MangaRepositoryImpl(handler).update(MangaUpdate(id = 1, title = title, description = description)) shouldBe true
    }

    /**
     * Moves the details clock by [ms] under the row's latch, so nothing logs or stamps again: what a
     * device whose clock runs [ms] ahead would have stamped.
     */
    private fun SyncTestDevice.advanceDetailsClock(ms: Long) {
        exec("UPDATE mangas SET is_syncing = 1 WHERE _id = 1")
        exec("UPDATE mangas SET details_modified_at = details_modified_at + $ms WHERE _id = 1")
        exec("UPDATE mangas SET is_syncing = 0 WHERE _id = 1")
    }

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
