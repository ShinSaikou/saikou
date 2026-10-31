package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.ChapterUpdate
import java.io.File

/**
 * Phase 5 unit SC2: a restore inserts each chapter with its latch up and lowers it once per entry,
 * so `insert_chapter_change` logs no row for a restored chapter. The restore's forced full export
 * (R86) carries them instead. A 40,000-entry restore of 50 chapters each wrote two million log rows
 * before this, and one statement per chapter now runs once per entry.
 */
class BackupImporterChapterLatchTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 40, chaptersPerManga = 5)

    /** The fixture repeats each entry's first chapter, which the restore writes once. */
    private val chapterCount = fixture.backupManga.sumOf { entry -> entry.chapters.distinctBy { it.url }.size }.toLong()

    @Test
    fun `a restore of favourites' chapters leaves no change-log row for them`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            env.count("chapters") shouldBe chapterCount
            fixture.backupManga.count { it.favorite } shouldBe 30
            env.rows("sync_change_log").filter { it["entity_type"] == "chapter" }.shouldBeEmpty()
        }
    }

    @Test
    fun `every restored chapter ends with its latch down and the version the file gave it`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            val chapters = env.rows("chapters")
            chapters.filter { it["is_syncing"] != 0L }.shouldBeEmpty()
            env.rows("mangas").filter { it["is_syncing"] != 0L }.shouldBeEmpty()
            val fileVersions = fixture.backupManga.flatMap { it.chapters }.associate { it.url to it.version }
            chapters.associate { it["url"] to it["version"] } shouldBe fileVersions
        }
    }

    /**
     * A restored chapter's `last_modified_at` (the file's field 11 on the next export) is 0, what an
     * insert outside the latch left it before this unit: lowering the latch must not stamp it.
     */
    @Test
    fun `a restored chapter keeps the clock an insert gave it before the latch`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            env.rows("chapters").map { it["last_modified_at"] }.distinct() shouldBe listOf(0L)
        }
    }

    @Test
    fun `a chapter edited after the restore is logged as before`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
            val favourite = env.rows("mangas").first { it["favorite"] == 1L }
            val chapter = env.rows("chapters").first { it["manga_id"] == favourite["_id"] && it["read"] == 0L }

            env.chapterRepository.update(ChapterUpdate(id = chapter["_id"] as Long, read = true))

            env.rows("sync_change_log")
                .filter { it["entity_type"] == "chapter" }
                .map { it["entity_id"] } shouldBe listOf(chapter["_id"])
        }
    }

    @Test
    fun `a second restore over the same entries leaves every latch down`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val bytes = SyntheticBackupFixture.tachibk(fixture)
            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}
            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}

            env.rows("chapters").filter { it["is_syncing"] != 0L }.shouldBeEmpty()
            env.count("chapters") shouldBe chapterCount
        }
    }
}
