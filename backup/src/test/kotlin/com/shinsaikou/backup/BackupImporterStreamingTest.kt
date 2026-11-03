package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.BackupImporter.Companion.MANGA_BATCH_SIZE
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportPhase
import com.shinsaikou.backup.importer.ImportProgress
import com.shinsaikou.backup.importer.ImportReport
import eu.kanade.tachiyomi.data.backup.BackupDecodeException
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupEntries
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Phase 5 unit SC2: the restore reads its file as a stream, the header first and then the entries
 * a chunk at a time in file order, never the whole file. These pin what the stream changes: a file
 * laid out in another field order restores the same rows, the progress counts against the header's
 * total, and an entry that does not decode costs that entry alone.
 */
class BackupImporterStreamingTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = MANGA_BATCH_SIZE * 2 + 30, chaptersPerManga = 3)

    @Test
    fun `a file whose entries sit around the other fields restores what upstream's order restores`() = runTest {
        val upstreamOrder = restoredRows(SyntheticBackupFixture.tachibk(fixture), File(tempDir, "upstream"))
        val interleaved = restoredRows(interleaved(), File(tempDir, "interleaved"))

        interleaved shouldBe upstreamOrder
    }

    @Test
    fun `progress counts each committed chunk against the header's total`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))
            val seen = mutableListOf<ImportProgress>()

            env.importer.import(file, RestoreOptions()) { if (it.phase == ImportPhase.MANGA) seen += it }

            seen.map { it.done } shouldContainExactly listOf(MANGA_BATCH_SIZE, MANGA_BATCH_SIZE * 2, fixture.backupManga.size)
            seen.map { it.total }.distinct() shouldContainExactly listOf(fixture.backupManga.size)
            seen.map { it.currentTitle } shouldContainExactly listOf(
                fixture.backupManga[MANGA_BATCH_SIZE - 1].title,
                fixture.backupManga[MANGA_BATCH_SIZE * 2 - 1].title,
                fixture.backupManga.last().title,
            )
        }
    }

    /**
     * An entry whose frame is whole but whose bytes do not decode is passed over: entry 150 of 300,
     * missing its required fields, and separately with a nested chapter cut short. The other 299
     * restore, the one is counted as failed and named in the report, and the cursor counts it, so
     * the progress still ends at the header's total.
     */
    @Test
    fun `a file whose entry 150 of 300 does not decode restores the other 299 and reports the one`() = runTest {
        val large = SyntheticBackupFixture.backup(mangaCount = 300, chaptersPerManga = 3)
        val brokenChapter = Buffer()
            .write(ProtoBuf.encodeToByteArray(BackupManga.serializer(), large.backupManga[149]))
            // Field 16, one more chapter: its url (field 1) says five bytes and holds one.
            .write(byteArrayOf(0x82.toByte(), 0x01, 0x03, 0x0a, 0x05, 0x41))
            .readByteArray()
        listOf("missing-fields" to ByteArray(0), "broken-chapter" to brokenChapter).forEach { (name, entry) ->
            BackupTestEnvironment(File(tempDir, name).apply { mkdirs() }).use { env ->
                val bytes = Buffer()
                    .write(encode(large.copy(backupManga = large.backupManga.take(149))))
                    .write(byteArrayOf(0x0a))
                    .write(varint(entry.size))
                    .write(entry)
                    .write(encode(Backup(backupManga = large.backupManga.drop(150))))
                    .readByteArray()
                val seen = mutableListOf<Int>()

                val report = env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {
                    if (it.phase == ImportPhase.MANGA) seen += it.done
                }

                env.count("mangas") shouldBe 299L
                report.mangaTotal shouldBe 300
                report.mangaInserted shouldBe 299
                report.mangaFailed shouldBe 1
                report.errors.filter { it.kind == ImportError.Kind.MANGA_ROW }.map { it.subject } shouldContainExactly
                    listOf("Entry 150 of 300")
                seen shouldContainExactly listOf(MANGA_BATCH_SIZE, MANGA_BATCH_SIZE * 2 + 1, 300)
                env.checkpoints.load() shouldBe null
            }
        }
    }

    /**
     * The defensive path: the header read every entry, but the list then meets one it cannot
     * decode (as if the file changed under the run). The chunk before it stays, with its checkpoint
     * and the flag, and the report says what was restored.
     */
    @Test
    fun `an entry the list cannot decode after the header read it leaves the report of what was restored`() = runTest {
        val failing = entriesFailingAt(MANGA_BATCH_SIZE + 50) {
            BackupDecodeException(BackupDecodeException.Reason.MALFORMED)
        }
        BackupTestEnvironment(tempDir, decoderFactory = failing).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            shouldThrow<BackupDecodeException> { env.importer.import(file, RestoreOptions()) {} }

            env.count("mangas") shouldBe MANGA_BATCH_SIZE.toLong()
            env.checkpoints.load().shouldNotBeNull().mangaCursor shouldBe MANGA_BATCH_SIZE
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
            val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
            report.mangaTotal shouldBe fixture.backupManga.size
            report.mangaInserted shouldBe MANGA_BATCH_SIZE
            report.cancelled shouldBe false
        }
    }

    /**
     * Phase 5 unit SC3's fix round: a flag commit the preference file refuses while the run fails
     * never replaces the run's own failure. The decode failure is still thrown, the refusal rides on
     * it as suppressed, and the report is still written.
     */
    @Test
    fun `a decode failure stays the failure thrown when the flag's commit is refused, and the report is written`() =
        runTest {
            val failing = entriesFailingAt(MANGA_BATCH_SIZE + 50) {
                BackupDecodeException(BackupDecodeException.Reason.MALFORMED)
            }
            BackupTestEnvironment(tempDir, decoderFactory = failing).use { env ->
                env.refuseRestoreFlag = true
                val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

                val thrown = shouldThrow<BackupDecodeException> { env.importer.import(file, RestoreOptions()) {} }

                thrown.suppressed.single().shouldBeInstanceOf<IOException>()
                val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
                report.mangaInserted shouldBe MANGA_BATCH_SIZE
                env.checkpoints.load().shouldNotBeNull().mangaCursor shouldBe MANGA_BATCH_SIZE
            }
        }

    /**
     * A read that fails for another reason, the storage or the heap, after a chunk committed: the
     * worker retries from the checkpoint, and the flag is set meanwhile, since the committed chunk
     * was written unlogged. A failure before any chunk commits leaves the flag alone.
     */
    @Test
    fun `a storage or memory failure after a chunk committed sets the flag`() = runTest {
        listOf<() -> Throwable>(
            { IOException("fixture: the storage failed") },
            { OutOfMemoryError("fixture: the heap is full") },
        ).forEachIndexed { case, failure ->
            val failing = entriesFailingAt(MANGA_BATCH_SIZE + 50, failure)
            val dir = File(tempDir, "after-$case").apply { mkdirs() }
            BackupTestEnvironment(dir, decoderFactory = failing).use { env ->
                val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

                val thrown = shouldThrow<Throwable> { env.importer.import(file, RestoreOptions()) {} }

                thrown::class shouldBe failure()::class
                env.count("mangas") shouldBe MANGA_BATCH_SIZE.toLong()
                env.checkpoints.load().shouldNotBeNull().mangaCursor shouldBe MANGA_BATCH_SIZE
                env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
            }
        }

        val early = entriesFailingAt(50) { IOException("fixture: the storage failed") }
        BackupTestEnvironment(File(tempDir, "before").apply { mkdirs() }, decoderFactory = early).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            shouldThrow<IOException> { env.importer.import(file, RestoreOptions()) {} }

            env.count("mangas") shouldBe 0L
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe false
        }
    }

    @Test
    fun `a header that does not decode writes nothing and leaves the flag alone`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val whole = SyntheticBackupFixture.tachibk(fixture, gzip = false)
            val file = env.writeImportFile(whole.copyOf(whole.size - 3))

            shouldThrow<BackupDecodeException> { env.importer.import(file, RestoreOptions()) {} }

            env.count("mangas") shouldBe 0L
            env.checkpoints.load() shouldBe null
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe false
        }
    }

    /** Every manga, chapter, history, track and membership row a restore of [bytes] writes. */
    private suspend fun restoredRows(bytes: ByteArray, dir: File): List<Any?> =
        BackupTestEnvironment(dir.apply { mkdirs() }).use { env ->
            val report = env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}
            report.mangaTotal shouldBe fixture.backupManga.size
            report.mangaFailed shouldBe 0
            listOf("mangas", "chapters", "history", "manga_sync", "mangas_categories", "categories", "excluded_scanlators")
                .map { table ->
                    env.rows(table).map { row ->
                        // Clocks the restore stamps with the wall clock differ between two runs.
                        row.filterKeys { it !in WALL_CLOCK_COLUMNS }
                            .mapValues { (_, value) -> if (value is ByteArray) value.toList() else value }
                    }
                }
        }

    /** The fixture's fields split and moved: categories, half the entries, sources, the rest, then the others. */
    private fun interleaved(): ByteArray {
        val half = fixture.backupManga.size / 2
        return Buffer()
            .write(encode(Backup(emptyList(), backupCategories = fixture.backupCategories)))
            .write(encode(Backup(backupManga = fixture.backupManga.take(half))))
            .write(encode(Backup(emptyList(), backupSources = fixture.backupSources)))
            .write(encode(Backup(backupManga = fixture.backupManga.drop(half))))
            .write(
                encode(
                    Backup(
                        emptyList(),
                        backupPreferences = fixture.backupPreferences,
                        backupSourcePreferences = fixture.backupSourcePreferences,
                        backupExtensionStores = fixture.backupExtensionStores,
                    ),
                ),
            )
            .readByteArray()
    }

    private fun encode(backup: Backup): ByteArray = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

    /** The decoder, but its entries throw [failure] where the list reaches entry [index]. */
    private fun entriesFailingAt(index: Int, failure: () -> Throwable): (BackupDecoder) -> BackupDecoder = { real ->
        spyk(real).also { spy ->
            every { spy.entries(any(), any(), any()) } answers {
                val entries = real.entries(firstArg(), secondArg(), thirdArg())
                BackupEntries(Buffer()) {
                    iterator {
                        entries.use { all ->
                            all.forEachIndexed { i, entry -> if (i == index) throw failure() else yield(entry) }
                        }
                    }
                }
            }
        }
    }

    private fun varint(value: Int): ByteArray {
        val buffer = Buffer()
        var rest = value
        while (rest and 0x7f.inv() != 0) {
            buffer.writeByte((rest and 0x7f) or 0x80)
            rest = rest ushr 7
        }
        return buffer.writeByte(rest).readByteArray()
    }

    private companion object {
        val WALL_CLOCK_COLUMNS = setOf(
            "last_modified_at", "date_added", "favorite_modified_at", "next_update", "calculate_interval",
            "categories_modified_at", "exclusions_modified_at", "trackers_modified_at", "restored_at",
            "modified_at", "date_fetch",
        )
    }
}
