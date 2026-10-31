package com.shinsaikou.backup

import app.cash.sqldelight.ExecutableQuery
import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.ConfigurableFakeSource
import com.shinsaikou.backup.fixture.FakeSource
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.source.SourceIds
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSource
import okio.GzipSource
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Phase 5 unit SC2: the export is staged in a private file, entry by entry, read back, and only then
 * copied to its target. These pin the file's layout, which is upstream's field order, and the staging
 * file's life: gone once the export is closed, and gone when the export fails.
 */
class BackupExporterStagingTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 40, chaptersPerManga = 3)

    @Test
    fun `the file holds every entry first, then the other fields in upstream's order`() = runTest {
        // A configurable source, so the file has source settings (field 105) as well.
        val sources = listOf(
            FakeSource(SyntheticBackupFixture.SOURCE_A, SyntheticBackupFixture.SOURCE_A_NAME),
            ConfigurableFakeSource(SyntheticBackupFixture.SOURCE_B, SyntheticBackupFixture.SOURCE_B_NAME, lang = "en"),
            FakeSource(SourceIds.LOCAL, "Local source", lang = "other"),
        )
        BackupTestEnvironment(tempDir, sources = sources).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
            env.context.getSharedPreferences("source_${SyntheticBackupFixture.SOURCE_B}", 0)
                .edit().putString("fixture_key", "fixture value").apply()

            val fields = topLevelFields(env.exportBytes(BackupOptions(privateSettings = true)))

            fields.count { it == 1 } shouldBe fixture.backupManga.size
            fields.distinct() shouldContainExactly listOf(1, 2, 101, 104, 105, 106)
            fields shouldBe fields.sorted()
        }
    }

    /**
     * Item 8: the whole-object reader still reads the streamed file, and reads it as the streaming
     * reader does: the same entries in the same order, and the same other fields.
     */
    @Test
    fun `BackupDecoder decode reads the streamed file as the header and entries read it`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
            val bytes = env.exportBytes(BackupOptions(privateSettings = true))

            val whole = env.decoder.decode(Buffer().write(bytes))
            val header = env.decoder.header(Buffer().write(bytes))
            val entries = env.decoder.entries(Buffer().write(bytes)).use { it.toList() }

            whole.backupManga.size shouldBe fixture.backupManga.size
            header.mangaCount shouldBe whole.backupManga.size
            entries.map(::encodeEntry) shouldBe whole.backupManga.map(::encodeEntry)
            encode(header.backup) shouldBe encode(whole.copy(backupManga = emptyList()))
        }
    }

    @Test
    fun `an export leaves no staging file behind once it is written`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            env.exportBytes(BackupOptions())

            stagingFiles(env).shouldBeEmpty()
        }
    }

    @Test
    fun `an export that fails deletes the file it staged`() = runTest {
        var failing = false
        val handler = { delegate: DatabaseHandler ->
            object : DatabaseHandler by delegate {
                override suspend fun <T : Any> awaitList(
                    inTransaction: Boolean,
                    block: suspend Database.() -> ExecutableQuery<T>,
                ): List<T> {
                    if (failing) throw IOException("fixture: the read failed")
                    return delegate.awaitList(inTransaction, block)
                }
            }
        }
        BackupTestEnvironment(tempDir, handlerFactory = handler).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
            failing = true

            shouldThrow<IOException> { env.exporter.build(BackupOptions()) }

            stagingFiles(env).shouldBeEmpty()
        }
    }

    @Test
    fun `a build deletes staged files over an hour old and leaves a fresh one`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
            val dir = File(env.context.cacheDir, "backup-export").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val stale = File(dir, "export-123.tachibk").apply { writeText("left by a process that died") }
            stale.setLastModified(now - 2 * 60 * 60 * 1000L) shouldBe true
            val fresh = File(dir, "export-456.tachibk").apply { writeText("an export running beside this one") }
            fresh.setLastModified(now - 10 * 60 * 1000L) shouldBe true
            val other = File(dir, "notes.txt").apply { writeText("not a staged file") }
            other.setLastModified(now - 2 * 60 * 60 * 1000L) shouldBe true

            env.exporter.build(BackupOptions()).use { staged ->
                stagingFiles(env).map { it.name }.sorted() shouldContainExactly
                    listOf(fresh.name, other.name, staged.file.name).sorted()
            }
            stale.exists() shouldBe false
        }
    }

    /** The field number of each top-level frame of the gzipped file [bytes], in file order. */
    private fun topLevelFields(bytes: ByteArray): List<Int> {
        val file = GzipSource(Buffer().write(bytes)).buffer()
        val fields = mutableListOf<Int>()
        while (!file.exhausted()) {
            val tag = varint(file)
            fields += (tag ushr 3).toInt()
            // Every field of Backup is a repeated message: wire type 2, a length, then that many bytes.
            (tag and 7) shouldBe 2L
            file.skip(varint(file))
        }
        return fields
    }

    private fun varint(source: BufferedSource): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = source.readByte().toInt()
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun encode(backup: Backup): String =
        Base64.getEncoder().encodeToString(ProtoBuf.encodeToByteArray(Backup.serializer(), backup))

    private fun encodeEntry(entry: BackupManga): String =
        Base64.getEncoder().encodeToString(ProtoBuf.encodeToByteArray(BackupManga.serializer(), entry))

    private fun stagingFiles(env: BackupTestEnvironment): List<File> =
        env.context.cacheDir.walkTopDown().filter { it.isFile }.toList()
}
