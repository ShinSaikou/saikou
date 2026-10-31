package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.importer.ImportError
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The one test that reads a real file. It is opt in, it never runs on a machine that has not set
 * the variable, and the file it reads is never copied into the repository: point
 * `SAIKOU_OWNER_BACKUP` at the file on disk and run the module's tests.
 *
 * Nothing it prints names a source: the report is summarised by counts and error kinds only.
 */
@EnabledIfEnvironmentVariable(named = "SAIKOU_OWNER_BACKUP", matches = ".+")
class OwnerBackupSmokeTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the real file decodes, validates and restores with no failed manga`() = runTest {
        val path = System.getenv("SAIKOU_OWNER_BACKUP")
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(File(path).readBytes())

            val report = env.importer.import(file, RestoreOptions()) {}

            println(
                "manga ${report.mangaInserted}/${report.mangaTotal}, chapters ${report.chaptersWritten}, " +
                    "history ${report.historyWritten}, tracks ${report.tracksWritten}, " +
                    "categories ${report.categoriesCreated}, preferences ${report.preferencesWritten}, " +
                    "errors by kind " + report.errors.groupingBy { it.kind }.eachCount(),
            )
            report.errors.filter { it.kind == ImportError.Kind.MANGA_ROW }.shouldBeEmpty()
        }
    }
}
