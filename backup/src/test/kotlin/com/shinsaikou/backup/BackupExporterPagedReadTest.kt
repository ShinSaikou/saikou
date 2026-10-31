package com.shinsaikou.backup

import com.shinsaikou.backup.export.toBackupChapter
import com.shinsaikou.backup.export.toBackupHistory
import com.shinsaikou.backup.export.toBackupManga
import com.shinsaikou.backup.export.toBackupTracking
import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.util.Base64

/**
 * Phase 5 unit SC2: the export reads the library a page at a time, one query per table for the
 * page, where it read six queries per entry, and writes the file an entry at a time. Every entry it
 * writes must be the entry the per-entry builder wrote, byte for byte, and the file, uncompressed,
 * the encoding of the whole [Backup] that builder made. [perEntry] is that builder as it stood at `9f95fc5e`, over the same
 * repositories, with categories written as their places in the file's order rather than their sort
 * values, and the library is shaped so an order the planner gave the old reads by accident
 * shows: read chapters after unread ones, scanlators and trackers inserted out of order, two
 * categories with one sort value on one entry, a soft-deleted chapter with history, and entries
 * outside the library with and without a begun chapter, across more than two pages.
 */
class BackupExporterPagedReadTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 1_150, chaptersPerManga = 6)

    @Test
    fun `every entry the paged export writes is the one the per-entry builder wrote`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
            roughen(env)

            for (options in OPTIONS) {
                val written = gunzip(env.exportBytes(options))
                val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), written)
                val (entries, sources) = perEntry(env, options)
                val expected = decoded.copy(
                    backupManga = entries,
                    backupSources = sources.map { BackupSource(env.sourceManager.getOrStub(it).toString(), it) },
                )

                withClue(options) {
                    decoded.backupManga.map(::encodeEntry) shouldBe entries.map(::encodeEntry)
                    // The file, written a frame at a time, is the whole object's encoding.
                    written.contentEquals(encode(expected)) shouldBe true
                }
            }
        }
    }

    /** Rows the restore alone does not leave, each an order or a filter the old reads had. */
    private suspend fun roughen(env: BackupTestEnvironment) {
        val favourites = env.mangaRepository.getFavorites().sortedBy { it.id }
        val first = favourites.first()

        // Scanlators inserted against their sort order.
        listOf("Zeta group", "Alpha group", "Mid group").forEach { name ->
            env.insert("excluded_scanlators", mapOf("manga_id" to first.id, "scanlator" to name))
        }

        // A second tracker with a lower id than the one the restore bound, inserted after it.
        favourites.take(40).forEach { manga ->
            env.insert(
                "manga_sync",
                mapOf(
                    "manga_id" to manga.id, "sync_id" to 1L, "remote_id" to 7L, "title" to "Second ${manga.id}",
                    "last_chapter_read" to 1.0, "total_chapters" to 3L, "status" to 1L, "score" to 0.0,
                    "remote_url" to "https://example.invalid/second/${manga.id}", "start_date" to 0L,
                    "finish_date" to 0L,
                ),
            )
        }

        // Two categories with one sort value: the entry carries two places, the lower id's first.
        env.insert("categories", mapOf("name" to "Tie A", "sort" to 1L, "flags" to 0L, "sync_key" to "tie a"))
        val tieA = env.rows("categories").last()["_id"] as Long
        env.insert("categories", mapOf("name" to "Tie B", "sort" to 1L, "flags" to 0L, "sync_key" to "tie b"))
        val tieB = env.rows("categories").last()["_id"] as Long
        favourites.take(30).forEach { manga ->
            env.insert("mangas_categories", mapOf("manga_id" to manga.id, "category_id" to tieB))
            env.insert("mangas_categories", mapOf("manga_id" to manga.id, "category_id" to tieA))
        }

        // A read chapter with history, soft-deleted: neither the chapter nor its history is written.
        val history = env.rows("history")
        val withHistory = env.chapterRepository.getChapterByMangaId(first.id, applyScanlatorFilter = false)
            .first { chapter -> history.any { it["chapter_id"] == chapter.id } }
        env.chapterRepository.softDelete(listOf(withHistory.id), now = 1_700_000_000_000L)

        // Outside the library: one with a begun chapter, which is written with read entries, and
        // one with nothing read, which is never written.
        for ((url, lastPageRead) in listOf("/begun" to 4L, "/untouched" to 0L)) {
            val id = env.writer.insertManga(Manga.create().copy(source = first.source, url = url, title = url))
            val chapter = Chapter.create().copy(mangaId = id, url = "$url/1", name = "1", lastPageRead = lastPageRead)
            env.writer.insertChapters(listOf(chapter))
            env.writer.lowerChaptersLatch(id)
        }
    }

    /** `BackupExporter.build`'s entries and sources at `9f95fc5e`: six repository reads per entry. */
    private suspend fun perEntry(
        env: BackupTestEnvironment,
        options: BackupOptions,
    ): Pair<List<BackupManga>, List<Long>> {
        val library = if (options.libraryEntries) {
            val favorites = env.mangaRepository.getFavorites()
            val read = if (options.readEntries) env.mangaRepository.getReadMangaNotInLibrary() else emptyList()
            (favorites + read).sortedBy { it.id }
        } else {
            emptyList()
        }
        val places = env.categoryRepository.getAll()
            .filterNot { it.isSystemCategory }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .withIndex()
            .associate { (place, category) -> category.id to place.toLong() }
        val entries = library.map { manga ->
            val entry = manga.toBackupManga(env.mangaRepository.getExcludedScanlators(manga.id).toList())
            val chapters = if (options.chapters) {
                env.chapterRepository.getChapterByMangaId(manga.id, applyScanlatorFilter = false)
            } else {
                emptyList()
            }
            if (chapters.isNotEmpty()) entry.chapters = chapters.map { it.toBackupChapter() }
            if (options.categories) {
                val orders = env.categoryRepository.getCategoriesByMangaId(manga.id)
                    .map { places.getValue(it.id) }
                    .sorted()
                if (orders.isNotEmpty()) entry.categories = orders
            }
            if (options.tracking) {
                val tracks = env.trackRepository.getTracksByMangaId(manga.id)
                if (tracks.isNotEmpty()) entry.tracking = tracks.map { it.toBackupTracking() }
            }
            if (options.history) {
                val history = env.historyRepository.getHistoryByMangaId(manga.id).filter { it.readAt != null }
                if (history.isNotEmpty()) {
                    val urls = env.chapterRepository.getChaptersByIds(history.map { it.chapterId })
                        .filter { it.deletedAt == null }
                        .associate { it.id to it.url }
                    entry.history = history.mapNotNull { row -> urls[row.chapterId]?.let(row::toBackupHistory) }
                }
            }
            entry
        }
        return entries to library.map { it.source }.distinct().sorted()
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GzipSource(Buffer().write(bytes)).buffer().use { it.readByteArray() }

    private fun encode(backup: Backup): ByteArray = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

    private fun encodeEntry(entry: BackupManga): String =
        Base64.getEncoder().encodeToString(ProtoBuf.encodeToByteArray(BackupManga.serializer(), entry))

    private companion object {
        val OPTIONS = listOf(
            BackupOptions(),
            BackupOptions(privateSettings = true),
            BackupOptions(readEntries = false),
            BackupOptions(chapters = false),
            BackupOptions(categories = false, tracking = false, history = false),
        )
    }
}
