package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.BackupImporter.Companion.MANGA_BATCH_SIZE
import com.shinsaikou.backup.importer.ImportPhase
import com.shinsaikou.backup.importer.ImportProgress
import com.shinsaikou.data.DatabaseHandler
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File

/**
 * A restore of a real library takes minutes, so it will be interrupted. What matters is that the
 * next attempt picks up where the last one committed and that nothing is written twice.
 */
class BackupImporterResumeTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 250, chaptersPerManga = 2)
    private val bytes = SyntheticBackupFixture.tachibk(fixture)

    @Test
    fun `an interrupted run leaves a checkpoint at the last committed chunk`() = runTest {
        val failing = FailingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { failing.wrap(it) }).use { env ->
            val file = env.writeImportFile(bytes)

            shouldThrow<DatabaseGone> { env.importer.import(file, RestoreOptions(), failing::armAfterTwoChunks) }

            val checkpoint = env.checkpoints.load()
            checkpoint shouldNotBe null
            checkpoint!!.mangaCursor shouldBe 200
            checkpoint.phase shouldBe ImportPhase.MANGA
            checkpoint.mangaTotal shouldBe 250
        }
    }

    @Test
    fun `the next attempt resumes and writes no duplicate row`() = runTest {
        val failing = FailingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { failing.wrap(it) }).use { env ->
            val file = env.writeImportFile(bytes)
            shouldThrow<DatabaseGone> { env.importer.import(file, RestoreOptions(), failing::armAfterTwoChunks) }
            failing.armed = false

            val report = env.importer.import(file, RestoreOptions()) {}

            report.resumed shouldBe true
            report.cancelled shouldBe false
            // Only the manga the first attempt never reached.
            report.mangaInserted shouldBe 50
            env.count("mangas") shouldBe 250L
            env.checkpoints.load() shouldBe null
        }
    }

    @Test
    fun `a checkpoint for a different file is discarded`() = runTest {
        val failing = FailingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { failing.wrap(it) }).use { env ->
            val file = env.writeImportFile(bytes)
            shouldThrow<DatabaseGone> { env.importer.import(file, RestoreOptions(), failing::armAfterTwoChunks) }
            failing.armed = false

            val other = SyntheticBackupFixture.backup(seed = 2L, mangaCount = 10, chaptersPerManga = 2)
            val otherFile = env.writeImportFile(SyntheticBackupFixture.tachibk(other))
            val report = env.importer.import(otherFile, RestoreOptions()) {}

            report.resumed shouldBe false
            report.mangaTotal shouldBe 10
        }
    }

    @Test
    fun `a checkpoint taken with different options is discarded`() = runTest {
        val failing = FailingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { failing.wrap(it) }).use { env ->
            val file = env.writeImportFile(bytes)
            shouldThrow<DatabaseGone> { env.importer.import(file, RestoreOptions(), failing::armAfterTwoChunks) }
            failing.armed = false

            val report = env.importer.import(file, RestoreOptions(categories = false)) {}

            report.resumed shouldBe false
            // The whole file is walked again from the first entry: the 200 the interrupted run
            // committed are merged rather than inserted a second time.
            report.mangaTotal shouldBe 250
            report.mangaMerged shouldBe 200
            report.mangaInserted shouldBe 50
            env.count("mangas") shouldBe 250L
        }
    }

    /**
     * Refuses to open a transaction once [armed], and throws an [Error] rather than an exception,
     * because that is what losing the process looks like from inside a suspend function: nothing
     * catches it and nothing rolls forward. The tests arm it from the progress callback, so the
     * run dies at a known cursor whatever number of transactions the earlier phases take.
     */
    private class FailingHandler {
        var armed = false

        /** Kills the run once two chunks of [BackupImporter.MANGA_BATCH_SIZE] have committed. */
        fun armAfterTwoChunks(progress: ImportProgress) {
            if (progress.phase == ImportPhase.MANGA && progress.done >= CHUNKS_BEFORE_FAILURE * MANGA_BATCH_SIZE) {
                armed = true
            }
        }

        fun wrap(delegate: DatabaseHandler): DatabaseHandler = object : DatabaseHandler by delegate {
            override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                if (inTransaction && armed) throw DatabaseGone()
                return delegate.await(inTransaction, block)
            }
        }
    }

    private class DatabaseGone : Error("The database went away mid restore")

    private companion object {
        const val CHUNKS_BEFORE_FAILURE = 2
    }
}
