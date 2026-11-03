package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.mapManga
import com.shinsaikou.data.DatabaseHandler
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.extensions.EMPTY
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File

/**
 * A chunk is one transaction because a hundred separate commits is a hundred times the overhead.
 * When that transaction fails, every manga in it is lost unless the run falls back, so the
 * fallback is the property this file pins, along with the log line that makes a slow restore
 * diagnosable rather than mysterious.
 */
class BackupImporterBatchFallbackTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a chunk that fails is retried row by row and the rest of the library survives`() = runTest {
        val flaky = FlakyTransactionHandler(failChunk = 1)
        BackupTestEnvironment(tempDir, handlerFactory = { flaky.wrap(it) }).use { env ->
            val fixture = SyntheticBackupFixture.backup(mangaCount = 250, chaptersPerManga = 2)
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            report.errors.count { it.kind == ImportError.Kind.BATCH_FALLBACK } shouldBe 1
            report.mangaFailed shouldBe 0
            report.mangaInserted shouldBe 250
            env.count("mangas") shouldBe 250L
        }
    }

    @Test
    fun `a memo the file cannot decode costs the field, not the manga`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val entry = BackupManga(
                source = SyntheticBackupFixture.SOURCE_A,
                url = "/manga/1",
                title = "Fixture Manga 1",
                // Not UTF-8, so the memo adapter throws where the vendored model decodes it.
                memo = byteArrayOf(0xC3.toByte(), 0x28, 0x7B),
            )
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(Backup(backupManga = listOf(entry))))

            val report = env.importer.import(file, RestoreOptions()) {}

            report.mangaInserted shouldBe 1
            report.errors.count { it.kind == ImportError.Kind.MANGA_ROW } shouldBe 1
            env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }.memo shouldBe JsonObject.EMPTY
        }
    }

    /** Refuses the transaction of one manga chunk, then behaves. */
    private class FlakyTransactionHandler(private val failChunk: Int) {
        private var chunkTransactions = 0
        private var depth = 0
        private var failed = false

        fun wrap(delegate: DatabaseHandler): DatabaseHandler = object : DatabaseHandler by delegate {
            override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                if (!inTransaction) return delegate.await(inTransaction, block)
                // Only an outermost transaction is a phase or a chunk: a repository call that opens
                // its own transaction inside one (a category insert) joins it.
                if (depth == 0) {
                    chunkTransactions++
                    // Three phase transactions come first (the categories, the store list and the
                    // merge of its duplicates); the chunks follow.
                    if (!failed && chunkTransactions == PHASE_TRANSACTIONS + failChunk) {
                        failed = true
                        throw IllegalStateException("The chunk transaction could not be opened")
                    }
                }
                depth++
                try {
                    return delegate.await(inTransaction, block)
                } finally {
                    depth--
                }
            }
        }

        private companion object {
            const val PHASE_TRANSACTIONS = 3
        }
    }
}
