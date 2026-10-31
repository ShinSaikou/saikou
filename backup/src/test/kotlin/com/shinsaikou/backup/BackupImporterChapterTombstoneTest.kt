package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A backup chapter whose url a soft-deleted row holds (phase 4 unit BF, R120 carry-over 2). The
 * restore matched the file's chapters against live rows only, so it inserted the chapter as a
 * second row beside the tombstone, and sync, which finds a chapter by its manga and url, then saw
 * two rows for one key. It restores the tombstone now, in the one update that writes the file's
 * progress, over the real schema and triggers.
 */
class BackupImporterChapterTombstoneTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a backup chapter whose url only a tombstone holds restores that row with the file's progress`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.seedManga()
            env.seedChapter(id = CHAPTER)
            env.chapterRepository.softDelete(listOf(CHAPTER), now = TOMBSTONE)
            val tombstone = env.chapter(CHAPTER)
            tombstone["deleted_at"] shouldBe TOMBSTONE
            val seq = env.latestSeq()

            val history = BackupHistory(url = URL, lastRead = 9_000L, readDuration = 600L)
            env.import(backupWith(BackupChapter(url = URL, name = NAME, read = true, lastPageRead = 12L), history))

            env.rows("chapters").map { it["_id"] } shouldContainExactly listOf(CHAPTER)
            val restored = env.chapter(CHAPTER)
            restored["read"] shouldBe 1L
            restored["last_page_read"] shouldBe 12L
            restored["deleted_at"] shouldBe null
            // A local undelete: the restore clock passes the tombstone, so the tombstone the other
            // devices hold loses the next sync, and the version moves once for it.
            (restored["restored_at"] as Long?).shouldNotBeNull() shouldBeGreaterThan TOMBSTONE
            restored["version"] shouldBe (tombstone["version"] as Long) + 1
            restored["is_syncing"] shouldBe 0L
            env.changesAfter(seq) shouldContainExactly listOf("upsert")
            // The history step, after the chapter step, finds the restored row as live.
            env.rows("history").map { it["chapter_id"] to it["time_read"] } shouldContainExactly
                listOf(CHAPTER to 600L)
        }
    }

    @Test
    fun `a restored tombstone takes its own version plus one, never the file's higher version`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.seedManga()
            env.seedChapter(id = CHAPTER)
            env.chapterRepository.softDelete(listOf(CHAPTER), now = TOMBSTONE)
            val tombstone = env.chapter(CHAPTER)
            val seq = env.latestSeq()

            // A file from a device that had synced many edits of this chapter. The row keeps the
            // tombstone's own name and source values (mergeChapter), so the file's clock is not
            // this row's: written, it would outrank a newer edit elsewhere on the next sync.
            val file = BackupChapter(url = URL, name = "Renamed elsewhere", read = true, lastPageRead = 12L, version = 50L)
            env.import(backupWith(file))

            val restored = env.chapter(CHAPTER)
            restored["deleted_at"] shouldBe null
            restored["name"] shouldBe NAME
            restored["version"] shouldBe (tombstone["version"] as Long) + 1
            env.changesAfter(seq) shouldContainExactly listOf("upsert")
        }
    }

    @Test
    fun `a tombstone the file matches with nothing else changed still comes back`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.seedManga()
            env.seedChapter(id = CHAPTER)
            env.chapterRepository.softDelete(listOf(CHAPTER), now = TOMBSTONE)

            env.import(backupWith(BackupChapter(url = URL, name = NAME, chapterNumber = 1f, sourceOrder = 1L)))

            env.rows("chapters").map { it["_id"] to it["deleted_at"] } shouldContainExactly listOf(CHAPTER to null)
        }
    }

    @Test
    fun `a url with a live row and a tombstone restores into the live row and leaves the tombstone alone`() =
        runTest {
            BackupTestEnvironment(tempDir).use { env ->
                env.seedManga()
                env.seedChapter(id = CHAPTER)
                env.chapterRepository.softDelete(listOf(CHAPTER), now = TOMBSTONE)
                env.seedChapter(id = LIVE)
                val tombstone = env.chapter(CHAPTER)

                env.import(
                    backupWith(BackupChapter(url = URL, name = NAME, read = true, lastPageRead = 12L, version = 50L)),
                )

                env.rows("chapters").map { it["_id"] } shouldContainExactly listOf(CHAPTER, LIVE)
                env.chapter(CHAPTER) shouldBe tombstone
                val live = env.chapter(LIVE)
                live["read"] shouldBe 1L
                live["last_page_read"] shouldBe 12L
                live["deleted_at"] shouldBe null
                // A live row's update runs under the latch, which writes the merged version as is.
                live["version"] shouldBe 50L
            }
        }

    private fun backupWith(chapter: BackupChapter, vararg history: BackupHistory) = Backup(
        backupManga = listOf(
            BackupManga(
                source = SOURCE,
                url = SERIES,
                title = "Fixture Manga 1",
                chapters = listOf(chapter),
                favorite = true,
                history = history.toList(),
            ),
        ),
    )

    private suspend fun BackupTestEnvironment.import(backup: Backup) {
        importer.import(writeImportFile(SyntheticBackupFixture.tachibk(backup)), RestoreOptions()) {}
    }

    private fun BackupTestEnvironment.chapter(id: Long): Map<String, Any?> = rows("chapters").single { it["_id"] == id }

    private fun BackupTestEnvironment.latestSeq(): Long =
        rows("sync_change_log").maxOfOrNull { it["seq"] as Long } ?: 0L

    /** The chapter changes the log holds after [seq], oldest first. */
    private fun BackupTestEnvironment.changesAfter(seq: Long): List<Any?> = rows("sync_change_log")
        .filter { (it["seq"] as Long) > seq && it["entity_type"] == "chapter" }
        .map { it["change_type"] }

    private suspend fun BackupTestEnvironment.seedManga() = handler.await {
        mangasQueries.insert(
            source = SOURCE,
            url = SERIES,
            artist = null,
            author = null,
            description = null,
            genre = null,
            title = "Fixture Manga 1",
            status = 1L,
            thumbnailUrl = null,
            favorite = true,
            lastUpdate = 0L,
            nextUpdate = 0L,
            initialized = true,
            viewer = 0L,
            chapterFlags = 0L,
            coverLastModified = 0L,
            dateAdded = 1L,
            updateStrategy = com.shinsaikou.source.model.UpdateStrategy.ALWAYS_UPDATE,
            calculateInterval = 0L,
            lastModifiedAt = 0L,
            version = 0L,
            notes = "",
            memo = JsonObject(emptyMap()),
            manualPosition = 0L,
            deletedAt = null,
        )
    }

    /** Chapter [id] of the entry at [URL], unread, as a refresh inserts it. */
    private fun BackupTestEnvironment.seedChapter(id: Long) = insert(
        "chapters",
        mapOf(
            "_id" to id,
            "manga_id" to 1L,
            "url" to URL,
            "name" to NAME,
            "read" to 0L,
            "bookmark" to 0L,
            "last_page_read" to 0L,
            "chapter_number" to 1.0,
            "source_order" to 1L,
            "date_fetch" to 0L,
            "date_upload" to 0L,
            "memo" to "{}".toByteArray(),
        ),
    )

    private companion object {
        val SOURCE = SyntheticBackupFixture.SOURCE_A
        const val SERIES = "/manga/1"
        const val URL = "/manga/1/ch/1"
        const val NAME = "Chapter 1"
        const val CHAPTER = 1L
        const val LIVE = 2L
        const val TOMBSTONE = 5_000L
    }
}
