package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.ConfigurableFakeSource
import com.shinsaikou.backup.fixture.FakeSource
import com.shinsaikou.backup.fixture.FakeSourceManager
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.StubSource
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The automatic backup and an import WorkManager resumes can start the process, and until the
 * extension host's first load has ended the registry holds the Local source alone (phase 2 plan,
 * section 1.6). The exporter would then write stub names and leave every extension's settings out
 * of the file; the validator would call every installed source missing, and the importer would
 * remember each one as a stub. Each of them waits for the registry; a failed load ends the wait.
 *
 * The work runs on a real dispatcher, and a test finishes the load only once the work is
 * subscribed to the registry's flag, which is what waiting is. Work that does not wait finishes
 * instead, and the test sees it finished.
 */
class BackupColdStartTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 20, chaptersPerManga = 2)

    @Test
    fun `an export started cold waits for the registry and keeps each source's name and settings`() = runTest {
        seededEnvironment().use { env ->
            env.sourceManager.startCold()

            val export = async(Dispatchers.Default) { env.export(BackupOptions()) }
            env.sourceManager.awaitWaiterOrEnd(export)
            export.isCompleted shouldBe false

            env.sourceManager.finishLoad()
            val backup = export.await()

            backup.backupSourcePreferences.map { it.sourceKey } shouldContainExactly listOf(SOURCE_A_KEY)
            backup.backupSources.single { it.sourceId == SyntheticBackupFixture.SOURCE_A }.name shouldBe
                SyntheticBackupFixture.SOURCE_A_NAME
        }
    }

    @Test
    fun `an export started cold still finishes after a failed extension load`() = runTest {
        seededEnvironment().use { env ->
            env.sourceManager.startCold()

            val export = async(Dispatchers.Default) { env.export(BackupOptions()) }
            env.sourceManager.awaitWaiterOrEnd(export)
            env.sourceManager.finishLoad(succeeded = false)
            val backup = export.await()

            // Nothing from the failed load is known, so its names are stubs and it has no settings.
            backup.backupManga.shouldNotBeEmpty()
            backup.backupSourcePreferences.shouldBeEmpty()
            backup.backupSources.single { it.sourceId == SyntheticBackupFixture.SOURCE_A }.name shouldBe
                StubSource.unknown(SyntheticBackupFixture.SOURCE_A).toString()
        }
    }

    /**
     * Triage E98: such a process has looked up no stub, and `getOrStub` answers a source whose
     * extension is gone with its bare id. The export names it from the remembered names instead,
     * as its stub shows it once looked up.
     */
    @Test
    fun `an export names a source with no extension by its remembered name, not its id`() = runTest {
        seededEnvironment().use { env ->
            env.stubSourceNames.remember(SyntheticBackupFixture.SOURCE_MISSING, "Remembered source", lang = "en")

            val backup = env.export(BackupOptions())

            backup.backupSources.single { it.sourceId == SyntheticBackupFixture.SOURCE_MISSING }.name shouldBe
                "Remembered source (EN)"
        }
    }

    @Test
    fun `an import started cold remembers no installed source as a stub`() = runTest {
        BackupTestEnvironment(tempDir, sources = sources()).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))
            env.sourceManager.startCold()

            val import = async(Dispatchers.Default) { env.importer.import(file, RestoreOptions()) {} }
            env.sourceManager.awaitWaiterOrEnd(import)
            import.isCompleted shouldBe false

            env.sourceManager.finishLoad()
            val report = import.await()

            report.stubSourcesRemembered shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING)
            env.stubSourceNames.remembered.keys shouldContainExactly setOf(SyntheticBackupFixture.SOURCE_MISSING)
        }
    }

    @Test
    fun `an import started cold still finishes after a failed extension load`() = runTest {
        BackupTestEnvironment(tempDir, sources = sources()).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))
            env.sourceManager.startCold()

            val import = async(Dispatchers.Default) { env.importer.import(file, RestoreOptions()) {} }
            env.sourceManager.awaitWaiterOrEnd(import)
            env.sourceManager.finishLoad(succeeded = false)
            val report = import.await()

            report.mangaInserted shouldBeGreaterThan 0
            // Every source the failed load would have served keeps the name the file carries.
            report.stubSourcesRemembered shouldContainExactlyInAnyOrder listOf(
                SyntheticBackupFixture.SOURCE_A,
                SyntheticBackupFixture.SOURCE_B,
                SyntheticBackupFixture.SOURCE_MISSING,
            )
        }
    }

    @Test
    fun `the validator waits for the registry before it calls an installed source missing`() = runTest {
        BackupTestEnvironment(tempDir, sources = sources()).use { env ->
            env.sourceManager.startCold()

            val validation = async(Dispatchers.Default) { env.validator.validate(env.header(fixture)) }
            env.sourceManager.awaitWaiterOrEnd(validation)
            validation.isCompleted shouldBe false

            env.sourceManager.finishLoad()

            validation.await().missingSources shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING.toString())
        }
    }

    @Test
    fun `the validator still answers after a failed extension load`() = runTest {
        BackupTestEnvironment(tempDir, sources = sources()).use { env ->
            env.sourceManager.startCold()

            val validation = async(Dispatchers.Default) { env.validator.validate(env.header(fixture)) }
            env.sourceManager.awaitWaiterOrEnd(validation)
            env.sourceManager.finishLoad(succeeded = false)

            validation.await().missingSources shouldContainExactlyInAnyOrder listOf(
                SyntheticBackupFixture.SOURCE_A_NAME,
                SyntheticBackupFixture.SOURCE_B_NAME,
                StubSource.unknown(SyntheticBackupFixture.SOURCE_MISSING).toString(),
            )
        }
    }

    /** A library imported while the registry was ready, so source A's settings file exists. */
    private suspend fun seededEnvironment(): BackupTestEnvironment {
        val env = BackupTestEnvironment(tempDir, sources = sources())
        env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
        env.sourcePreferenceFiles.keys shouldContain SOURCE_A_KEY
        return env
    }

    /** Source A has settings, as an extension source does; source B has none. */
    private fun sources() = listOf(
        ConfigurableFakeSource(SyntheticBackupFixture.SOURCE_A, SyntheticBackupFixture.SOURCE_A_NAME),
        FakeSource(SyntheticBackupFixture.SOURCE_B, SyntheticBackupFixture.SOURCE_B_NAME),
        FakeSource(SourceIds.LOCAL, "Local source", lang = "other"),
    )

    /**
     * Returns once [work] is subscribed to the registry's flag, or has finished without ever
     * subscribing. Polled in real time: the work runs on [Dispatchers.Default], outside the test
     * scheduler.
     */
    private suspend fun FakeSourceManager.awaitWaiterOrEnd(work: Job) = withContext(Dispatchers.Default) {
        withTimeout(WAITER_TIMEOUT_MS) {
            while (isInitialized.subscriptionCount.value == 0 && !work.isCompleted) delay(POLL_MS)
        }
    }

    private companion object {
        val SOURCE_A_KEY = "source_${SyntheticBackupFixture.SOURCE_A}"
        const val WAITER_TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
