package com.shinsaikou.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.util.Fixtures
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import okio.sink
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File
import java.util.Date

/**
 * The export half of the backup, on a device: what the application writes, restored into a
 * database cleared of it, comes back whole. The export device test of Phase 2 unit K
 * (`design/plans/phase2-start.md`, Q9 (a)): until it, nothing exported on a device.
 *
 * `BackupImportTest` proves the importer against the owner's file, and `:backup`'s round-trip unit
 * test proves the pair against an in-memory database. Neither runs the exporter the application
 * ships, through the graph, against the schema, triggers and SQLite of a real install, and neither
 * reads a file back after it has been written to storage. This does all of that.
 *
 * ### What "cleared" means
 *
 * The test's own rows, not the whole database. It seeds a synthetic source id nothing else uses
 * ([SOURCE_ID]) with favourites, one read entry that is not in the library, chapters, history and two
 * categories named with a per-run prefix, and removes exactly those between the export and the
 * import, asserting they are gone. Wiping every table would need a path into the database no
 * shipping code has (ruling R39), and on a gate device it would take the seeded content other
 * tests measure with it. The comparison is therefore over this seed only, which is also what makes
 * it exact: every count is known before the export, and the device's other rows cannot move it.
 *
 * The export is the whole file, with the options the export screen starts on, because that is the
 * file an owner gets. The import takes its library and categories only: app settings, source
 * settings and index rows would rewrite state the device's other tests depend on, and this seed
 * has none of them. Entries already on the device merge rather than insert, so they are left as
 * they were.
 *
 * There is no early return: the test needs nothing from the owner or the gate, so it asserts on
 * every device it runs on.
 */
@RunWith(AndroidJUnit4::class)
class BackupExportRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /** Distinct per run, so a category a failed earlier run left behind is not counted as this one's. */
    private val categoryPrefix = "saikou-export-${System.nanoTime()}-"

    private val file = File(Fixtures.cacheDir(), FILE_NAME)

    @After
    fun removeTheSeed() {
        file.delete()
        runBlocking { clear() }
    }

    @Test
    fun anExportRestoredIntoAClearedDatabaseComesBackWhole() {
        runBlocking {
            seed()
            val seeded = count()
            assertEquals("the seed did not write what it meant to", SEEDED, seeded)

            val bytes = graph.backupExporter.build(BackupOptions()).use { staged ->
                graph.backupExporter.write(staged, file.sink())
            }
            assertTrue("the exporter wrote an empty file", bytes > 0 && file.length() == bytes)
            assertTheFileCarriesTheSeed()

            clear()
            assertEquals("clearing left rows of the seed behind", Counts(), count())

            val report = graph.backupImporter.import(file, RESTORE_OPTIONS, progress = {})
            assertFalse("the import reported itself cancelled", report.cancelled)
            assertEquals("${report.mangaFailed} entries failed to restore", 0, report.mangaFailed)

            val restored = count()
            logcat(LOG_TAG, LogPriority.INFO) { "seeded $seeded, restored $restored from $bytes bytes" }
            assertEquals("the restore does not match what was exported", seeded, restored)
        }
    }

    /**
     * The file as written to storage, decoded the way the importer decodes it: the seed's entries,
     * chapters, history and categories are all in it. Checked before the clear, so a failure here
     * names the exporter rather than the importer.
     */
    private fun assertTheFileCarriesTheSeed() {
        val written = BackupDecoder(context, ProtoBuf).decode(file.source())
        val entries = written.backupManga.filter { it.source == SOURCE_ID }
        assertEquals("the file carries a different number of the seed's entries", SEEDED.entries, entries.size)
        assertEquals(
            "the file carries a different number of the seed's chapters",
            SEEDED.chapters,
            entries.sumOf { it.chapters.size },
        )
        assertEquals(
            "the file carries a different number of the seed's history rows",
            SEEDED.history,
            entries.sumOf { it.history.size },
        )
        assertEquals(
            "the file carries a different number of the seed's categories",
            SEEDED.categories,
            written.backupCategories.count { it.name.startsWith(categoryPrefix) },
        )
    }

    private suspend fun seed() {
        CATEGORY_SUFFIXES.forEachIndexed { index, suffix ->
            graph.categoryRepository.insert(
                Category(id = 0L, name = categoryPrefix + suffix, order = CATEGORY_ORDER_BASE + index, flags = 0L),
            )
        }
        val categories = seedCategories().associateBy { it.name.removePrefix(categoryPrefix) }

        val now = System.currentTimeMillis()
        ENTRIES.forEachIndexed { index, entry ->
            val stored = graph.mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = SOURCE_ID, url = "/export/$index", title = "Export entry $index")),
            ).single()
            if (entry.favorite) {
                graph.mangaRepository.update(MangaUpdate(id = stored.id, favorite = true, dateAdded = now))
            }
            val chapters = graph.chapterRepository.addAll(
                List(CHAPTERS_PER_ENTRY) { number ->
                    Chapter.create().copy(
                        mangaId = stored.id,
                        url = "/export/$index/${number + 1}",
                        name = "Chapter ${number + 1}",
                        chapterNumber = (number + 1).toDouble(),
                        sourceOrder = (CHAPTERS_PER_ENTRY - number).toLong(),
                        read = number < entry.historyRows,
                    )
                },
            ).sortedBy { it.chapterNumber }
            chapters.take(entry.historyRows).forEachIndexed { offset, chapter ->
                graph.historyRepository.upsertHistory(
                    HistoryUpdate(chapter.id, Date(now - (offset + 1) * HISTORY_STEP_MILLIS), HISTORY_READ_MILLIS),
                )
            }
            if (entry.categories.isNotEmpty()) {
                graph.mangaRepository.setMangaCategories(stored.id, entry.categories.map { categories.getValue(it).id })
            }
        }
    }

    /**
     * The seed's entries are unfavourited and then deleted with the query the application uses for
     * a source's leftovers, which the chapters, history rows and category links follow by their
     * foreign keys. The categories are deleted as the library deletes one.
     */
    private suspend fun clear() {
        seedEntries().filter { it.favorite }.forEach {
            graph.mangaRepository.update(MangaUpdate(id = it.id, favorite = false))
        }
        graph.mangaRepository.deleteNonLibraryManga(listOf(SOURCE_ID), keepReadManga = false)
        seedCategories().forEach { graph.categoryRepository.delete(it.id) }
    }

    private suspend fun seedEntries(): List<Manga> =
        (graph.mangaRepository.getFavorites() + graph.mangaRepository.getReadMangaNotInLibrary())
            .filter { it.source == SOURCE_ID }
            .distinctBy { it.id }

    private suspend fun seedCategories(): List<Category> =
        graph.categoryRepository.getAll().filter { it.name.startsWith(categoryPrefix) }

    /** Read back from the database, never from the importer's report, which is its own account. */
    private suspend fun count(): Counts {
        val entries = seedEntries()
        return Counts(
            entries = entries.size,
            favorites = entries.count { it.favorite },
            chapters = entries.sumOf { graph.chapterRepository.getChapterByMangaId(it.id).size },
            readChapters = entries.sumOf { entry -> graph.chapterRepository.getChapterByMangaId(entry.id).count { it.read } },
            history = entries.sumOf { entry ->
                graph.historyRepository.getHistoryByMangaId(entry.id).count { it.readAt != null }
            },
            categories = seedCategories().size,
            categoryLinks = entries.sumOf { entry ->
                graph.categoryRepository.getCategoriesByMangaId(entry.id).count { it.name.startsWith(categoryPrefix) }
            },
        )
    }

    private data class Counts(
        val entries: Int = 0,
        val favorites: Int = 0,
        val chapters: Int = 0,
        val readChapters: Int = 0,
        val history: Int = 0,
        val categories: Int = 0,
        val categoryLinks: Int = 0,
    )

    private data class Entry(val favorite: Boolean, val historyRows: Int, val categories: List<String>)

    private companion object {
        const val LOG_TAG = "SaikouBackupExport"

        /** A synthetic source id this test alone seeds; the gate rows use ...001 to ...003. */
        const val SOURCE_ID = 9_000_000_000_000_000_004L

        const val FILE_NAME = "export-round-trip.tachibk"

        const val CHAPTERS_PER_ENTRY = 4
        const val CATEGORY_ORDER_BASE = 1_000L
        const val HISTORY_STEP_MILLIS = 60_000L
        const val HISTORY_READ_MILLIS = 90_000L

        val CATEGORY_SUFFIXES = listOf("a", "b")

        /**
         * Three library entries, one of them in both categories and one in none, and a fourth that is
         * not in the library but has been read, which the export carries as a read entry.
         */
        val ENTRIES = listOf(
            Entry(favorite = true, historyRows = 2, categories = listOf("a")),
            Entry(favorite = true, historyRows = 1, categories = listOf("a", "b")),
            Entry(favorite = true, historyRows = 0, categories = emptyList()),
            Entry(favorite = false, historyRows = 1, categories = emptyList()),
        )

        val SEEDED = Counts(
            entries = ENTRIES.size,
            favorites = ENTRIES.count { it.favorite },
            chapters = ENTRIES.size * CHAPTERS_PER_ENTRY,
            readChapters = ENTRIES.sumOf { it.historyRows },
            history = ENTRIES.sumOf { it.historyRows },
            categories = CATEGORY_SUFFIXES.size,
            categoryLinks = ENTRIES.sumOf { it.categories.size },
        )

        val RESTORE_OPTIONS = RestoreOptions(
            libraryEntries = true,
            categories = true,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            installExtensions = false,
        )
    }
}
