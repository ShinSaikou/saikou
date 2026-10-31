package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.BackupImporter
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportPhase
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.data.DatabaseHandler
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File
import java.io.IOException

/**
 * Phase 3 unit E2: every restore leaves the one flag sync reads, so sync's next run pulls again from
 * the start and makes a full export; a restore merges into existing rows without logging them. The
 * flag is app state, so no backup carries it.
 */
class BackupImporterSyncFlagTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 5, chaptersPerManga = 2, privateSettings = true)

    @Test
    fun `a finished restore sets the sync restore flag`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe false

            val report = env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            report.cancelled shouldBe false
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
        }
    }

    /**
     * Phase 5 unit SC3 (found by unit TG3): the flag goes through the committing writer, so it is on
     * disk before the restore returns; a `Preference.set` is an `apply()`, which a process killed
     * within about 100 ms of the restore can lose, and the next sync would then skip its full export.
     */
    @Test
    fun `the flag is committed through the writer before the restore returns, finished or cancelled`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val key = env.syncRestorePreferences.restoredSinceSync.key()

            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            env.restoreFlagCommits shouldBe listOf(key to true)
        }
        BackupTestEnvironment(File(tempDir, "cancelled").apply { mkdirs() }).use { env ->
            val key = env.syncRestorePreferences.restoredSinceSync.key()
            val twoChunks = SyntheticBackupFixture.backup(
                mangaCount = BackupImporter.MANGA_BATCH_SIZE + 50,
                chaptersPerManga = 2,
            )
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(twoChunks))

            lateinit var job: Job
            job = launch { env.importer.import(file, RestoreOptions()) { job.cancel() } }
            job.join()

            job.isCancelled shouldBe true
            env.restoreFlagCommits shouldBe listOf(key to true)
        }
    }

    /**
     * Phase 5 unit SC3's fix round: a cancel whose flag commit the preference file refuses is still
     * a cancel, and the cancelled run's report is written. The refusal is suppressed on the cancel
     * the importer caught; `import`'s `withContext` rethrows the job's own cancel to the caller, so
     * the suppressed exception cannot be seen from here.
     */
    @Test
    fun `a cancel stays a cancel when the flag's commit is refused`() = runTest {
        val twoChunks = SyntheticBackupFixture.backup(
            mangaCount = BackupImporter.MANGA_BATCH_SIZE + 50,
            chaptersPerManga = 2,
        )
        BackupTestEnvironment(tempDir).use { env ->
            env.refuseRestoreFlag = true
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(twoChunks))

            var thrown: Throwable? = null
            lateinit var job: Job
            job = launch {
                try {
                    env.importer.import(file, RestoreOptions()) { job.cancel() }
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                }
            }
            job.join()

            job.isCancelled shouldBe true
            thrown.shouldBeInstanceOf<CancellationException>()
            env.restoreFlagCommits shouldBe emptyList()
            val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
            report.cancelled shouldBe true
        }
    }

    /**
     * Phase 5 unit SC3's fix round: on a run that succeeded, the report is written and the
     * checkpoint cleared first, and then a refused flag commit is the failure the restore throws.
     */
    @Test
    fun `a finished restore writes its report, then throws the refused flag commit`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.refuseRestoreFlag = true
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            shouldThrow<IOException> { env.importer.import(file, RestoreOptions()) {} }

            val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
            report.cancelled shouldBe false
            report.mangaInserted shouldBe fixture.backupManga.size
            env.checkpoints.load().shouldBeNull()
            env.restoreFlagCommits shouldBe emptyList()
        }
    }

    /** A kill after the commit then leaves a run that resumes with the flag set. */
    @Test
    fun `a finished restore commits the flag before it deletes its import file`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))
            var importFileAtCommit: Boolean? = null
            env.beforeRestoreFlagCommit = { importFileAtCommit = file.exists() }

            env.importer.import(file, RestoreOptions()) {}

            importFileAtCommit shouldBe true
            file.exists() shouldBe false
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
        }
    }

    @Test
    fun `a restore that writes nothing new still sets it, and no backup carries it`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val bytes = SyntheticBackupFixture.tachibk(fixture)
            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}
            env.syncRestorePreferences.restoredSinceSync.set(false)

            // The same file again: every row merges into one that exists, which nothing logs.
            val report = env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}

            report.mangaInserted shouldBe 0
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
            val exported = env.export(BackupOptions(privateSettings = true)).backupPreferences.map { it.key }
            (env.syncRestorePreferences.restoredSinceSync.key() in exported) shouldBe false
        }
    }

    /**
     * Covers a cancel the importer observes at its check between chunks, and only that: the job
     * cancels itself from the progress callback once the first chunk has committed, so the next
     * check stops the run and `finish` still runs. A cancel that lands inside a chunk is the next
     * test's.
     */
    @Test
    fun `a restore cancelled between chunks still sets the sync restore flag`() = runTest {
        // Two chunks, so the cancel lands with one committed and one to go.
        val twoChunks = SyntheticBackupFixture.backup(
            mangaCount = BackupImporter.MANGA_BATCH_SIZE + 50,
            chaptersPerManga = 2,
        )
        BackupTestEnvironment(tempDir).use { env ->
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe false
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(twoChunks))

            lateinit var job: Job
            job = launch {
                env.importer.import(file, RestoreOptions()) { job.cancel() }
            }
            job.join()

            // Only the first chunk committed, and the run says it was cancelled, not finished.
            env.count("mangas") shouldBe BackupImporter.MANGA_BATCH_SIZE.toLong()
            env.checkpoints.load().shouldNotBeNull()
            val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
            report.cancelled shouldBe true
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
        }
    }

    /**
     * The cancel a user's tap almost always makes: it lands while a chunk is being written. The
     * handler cancels the job as the second chunk's transaction opens, so the first repository call
     * inside it throws, as `withContext` does on a real device, and the chunk rolls back.
     */
    @Test
    fun `a restore cancelled inside a chunk still sets the sync restore flag`() = runTest {
        val twoChunks = SyntheticBackupFixture.backup(
            mangaCount = BackupImporter.MANGA_BATCH_SIZE + 50,
            chaptersPerManga = 2,
        )
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe false
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(twoChunks))

            lateinit var job: Job
            job = launch {
                env.importer.import(file, RestoreOptions()) { progress ->
                    if (progress.phase == ImportPhase.MANGA) cancelling.onNextTransaction = { job.cancel() }
                }
            }
            job.join()

            cancelling.fired shouldBe true
            // The second chunk rolled back; the first stays, and so does its checkpoint.
            env.count("mangas") shouldBe BackupImporter.MANGA_BATCH_SIZE.toLong()
            env.checkpoints.load().shouldNotBeNull().mangaCursor shouldBe BackupImporter.MANGA_BATCH_SIZE
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
            val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
            report.cancelled shouldBe true
        }
    }

    /*
     * R121 carry-over 1: steps 3 to 6 (stub sources, categories, preferences, extension stores) each
     * have a test that cancels while the step runs. Each cancel leaves the step as a thrown
     * CancellationException, as a suspending call does once its job is cancelled, so a refactor that
     * moved the step out of the importer's `try` would lose the flag for it and turn its test red. On
     * a fresh run nothing has saved a checkpoint before the first manga chunk commits, and a cancel
     * in these steps leaves none (R121 item 2); the next run redoes them.
     */

    @Test
    fun `a restore cancelled in step 3 while it names stub sources still sets the flag`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            lateinit var job: Job
            // SOURCE_MISSING has no provider, so step 3 remembers a name for it.
            env.stubSourceNames.beforeRemember = {
                job.cancel()
                currentCoroutineContext().ensureActive()
            }
            job = launch { env.importer.import(file, RestoreOptions()) {} }
            job.join()

            env.stubSourceNames.remembered.shouldBeEmpty()
            val report = cancelledBeforeTheMangaList(env)
            report.stubSourcesRemembered.shouldBeEmpty()
            report.categoriesCreated shouldBe 0
        }
    }

    @Test
    fun `a restore cancelled in step 4 while it writes categories still sets the flag`() = runTest {
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            lateinit var job: Job
            // Step 3 writes through the fake, so the first transaction is step 4's.
            cancelling.onNextTransaction = { job.cancel() }
            job = launch { env.importer.import(file, RestoreOptions()) {} }
            job.join()

            cancelling.fired shouldBe true
            // The categories' transaction rolled back, and step 5 never ran.
            env.categoryRepository.getAll().filterNot { it.isSystemCategory }.shouldBeEmpty()
            val report = cancelledBeforeTheMangaList(env)
            report.categoriesCreated shouldBe 0
            report.preferencesWritten shouldBe 0
        }
    }

    /**
     * Step 5's one call outside a per-key catch is the schedulers' re-read after the app preferences, so
     * the cancel is thrown there. A cancel a suspending call inside a per-key catch observes is rethrown
     * by that catch (phase 5 unit SC2, R127 carry-over 1); the test after the step 6 one pins that.
     */
    @Test
    fun `a restore cancelled in step 5 while it restores preferences still sets the flag`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            lateinit var job: Job
            every { env.libraryUpdateScheduler.setup() } answers {
                job.cancel()
                throw CancellationException("cancelled while the app preferences were restored")
            }
            job = launch { env.importer.import(file, RestoreOptions()) {} }
            job.join()

            val report = cancelledBeforeTheMangaList(env)
            // The app preferences before the cancel stay; the source preferences and step 6 never ran.
            report.preferencesWritten shouldBeGreaterThan 0
            report.sourcePreferenceFilesWritten shouldBe 0
            report.extensionStoresUpserted shouldBe 0
        }
    }

    @Test
    fun `a restore cancelled in step 6 as it writes extension stores still sets the flag`() = runTest {
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            lateinit var job: Job
            // Armed at the end of step 5's app preferences; step 5 opens no transaction after it, so
            // the next one is step 6's first chunk, and the cancel lands as it opens.
            every { env.libraryUpdateScheduler.setup() } answers {
                cancelling.onNextTransactionOpen = { job.cancel() }
            }
            job = launch { env.importer.import(file, RestoreOptions()) {} }
            job.join()

            cancelling.fired shouldBe true
            val report = cancelledBeforeTheMangaList(env)
            report.sourcePreferenceFilesWritten shouldBeGreaterThan 0
            report.extensionStoresUpserted shouldBe 0
        }
    }

    /*
     * Phase 5 unit SC2 (R127 carry-over 1): a cancel that a write inside one of the importer's
     * per-row catches observes is rethrown by that catch before anything else, so the run ends as a
     * cancel and no row is recorded as failed for it. Each test cancels at a write inside such a catch.
     */

    @Test
    fun `a restore cancelled inside a row's write ends as a cancel inside a chunk does`() = runTest {
        val twoChunks = SyntheticBackupFixture.backup(mangaCount = BackupImporter.MANGA_BATCH_SIZE + 50, chaptersPerManga = 2)
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(twoChunks))

            lateinit var job: Job
            job = launch {
                env.importer.import(file, RestoreOptions()) { progress ->
                    // Past the second chunk's transaction and its first rows' writes.
                    if (progress.phase == ImportPhase.MANGA) cancelling.cancelAtAwait(ROW_WRITES) { job.cancel() }
                }
            }
            job.join()

            cancelling.firedInside shouldBe true
            // J.5: the second chunk rolled back; the first stays, and so does its checkpoint.
            env.count("mangas") shouldBe BackupImporter.MANGA_BATCH_SIZE.toLong()
            env.checkpoints.load().shouldNotBeNull().mangaCursor shouldBe BackupImporter.MANGA_BATCH_SIZE
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
            val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
            report.cancelled shouldBe true
            report.mangaFailed shouldBe 0
            rowErrors(report).shouldBeEmpty()
        }
    }

    @Test
    fun `a restore cancelled inside a row's write after its chunk fell back records no row as failed`() = runTest {
        val twoChunks = SyntheticBackupFixture.backup(mangaCount = BackupImporter.MANGA_BATCH_SIZE + 50, chaptersPerManga = 2)
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(twoChunks))

            lateinit var job: Job
            job = launch {
                env.importer.import(file, RestoreOptions()) { progress ->
                    if (progress.phase == ImportPhase.MANGA) {
                        // The second chunk's transaction is refused, so its rows go one transaction each.
                        cancelling.refuseNextTransaction = true
                        cancelling.cancelAtAwait(ROW_WRITES) { job.cancel() }
                    }
                }
            }
            job.join()

            cancelling.firedInside shouldBe true
            // The rows before the cancelled one committed one by one; the checkpoint is the first chunk's.
            env.count("mangas") shouldBeInRange BackupImporter.MANGA_BATCH_SIZE + 1L..BackupImporter.MANGA_BATCH_SIZE + 49L
            env.checkpoints.load().shouldNotBeNull().mangaCursor shouldBe BackupImporter.MANGA_BATCH_SIZE
            env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
            val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
            report.cancelled shouldBe true
            report.mangaFailed shouldBe 0
            rowErrors(report).map { it.kind } shouldBe listOf(ImportError.Kind.BATCH_FALLBACK)
        }
    }

    @Test
    fun `a restore cancelled as step 4 inserts a category records no category error`() = runTest {
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            lateinit var job: Job
            // Inside step 4's transaction, the next write is the first category's insert.
            cancelling.onNextTransaction = { cancelling.cancelAtAwait(0) { job.cancel() } }
            job = launch { env.importer.import(file, RestoreOptions()) {} }
            job.join()

            cancelling.firedInside shouldBe true
            env.categoryRepository.getAll().filterNot { it.isSystemCategory }.shouldBeEmpty()
            val report = cancelledBeforeTheMangaList(env)
            report.categoriesCreated shouldBe 0
            report.preferencesWritten shouldBe 0
            report.errors.shouldBeEmpty()
        }
    }

    @Test
    fun `a cancel while step 5 writes a legacy extension store is not recorded as its error`() = runTest {
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            val errors = mutableListOf<ImportError>()
            // Upstream's legacy key, which the restorer turns into an extension store row.
            val legacy = BackupPreference("extension_repos", StringSetPreferenceValue(setOf("https://example.invalid/repo")))

            lateinit var job: Job
            // The store's upsert is the restorer's only write.
            cancelling.cancelAtAwait(0) { job.cancel() }
            job = launch { env.preferenceRestorer.restoreApp(listOf(legacy), emptyMap(), errors) }
            job.join()

            cancelling.fired shouldBe true
            job.isCancelled shouldBe true
            errors.shouldBeEmpty()
            env.count("extension_store") shouldBe 0L
        }
    }

    @Test
    fun `a restore cancelled as step 6 upserts a store records no store error`() = runTest {
        val cancelling = CancellingHandler()
        BackupTestEnvironment(tempDir, handlerFactory = { cancelling.wrap(it) }).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            lateinit var job: Job
            // Armed at the end of step 5's app preferences: inside step 6's first transaction, the
            // next write is the first store's upsert.
            every { env.libraryUpdateScheduler.setup() } answers {
                cancelling.onNextTransaction = { cancelling.cancelAtAwait(0) { job.cancel() } }
            }
            job = launch { env.importer.import(file, RestoreOptions()) {} }
            job.join()

            cancelling.firedInside shouldBe true
            val report = cancelledBeforeTheMangaList(env)
            report.extensionStoresUpserted shouldBe 0
            report.errors.filter { it.kind == ImportError.Kind.EXTENSION_STORE }.shouldBeEmpty()
        }
    }

    /** The report's errors but the fixture's unknown tracker, which the first chunk records as it should. */
    private fun rowErrors(report: ImportReport) = report.errors.filterNot { it.kind == ImportError.Kind.UNKNOWN_TRACKER_SKIPPED }

    /** The run finished as a cancelled one before its manga list: the flag, the report and no checkpoint. */
    private fun cancelledBeforeTheMangaList(env: BackupTestEnvironment): ImportReport {
        env.syncRestorePreferences.restoredSinceSync.get() shouldBe true
        val report = env.json.decodeFromString(ImportReport.serializer(), env.checkpoints.reportFile.readText())
        report.cancelled shouldBe true
        env.checkpoints.load().shouldBeNull()
        env.count("mangas") shouldBe 0L
        return report
    }

    /**
     * Runs [onNextTransaction] once, inside the next transaction it opens and before its block, or
     * [onNextTransactionOpen] once, as the next transaction is asked for and before it opens. A
     * [cancelAtAwait] action runs before the await it names, and [firedInside] says whether that
     * await was a write inside an open transaction. [refuseNextTransaction] makes the next outermost
     * transaction throw as it is asked for, as BackupImporterBatchFallbackTest's handler does.
     */
    private class CancellingHandler {
        @Volatile
        var refuseNextTransaction = false

        @Volatile
        var firedInside = false

        @Volatile
        private var onAwait: (() -> Unit)? = null

        @Volatile
        private var awaitsToSkip = 0

        private var depth = 0

        /** Runs [action] once, before the await [skip] awaits from now; awaitList and its kin do not count. */
        fun cancelAtAwait(skip: Int, action: () -> Unit) {
            awaitsToSkip = skip
            onAwait = action
        }

        @Volatile
        var onNextTransaction: (() -> Unit)? = null

        @Volatile
        var onNextTransactionOpen: (() -> Unit)? = null

        @Volatile
        var fired = false

        fun wrap(delegate: DatabaseHandler): DatabaseHandler = object : DatabaseHandler by delegate {
            override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                onAwait?.let { action ->
                    if (awaitsToSkip-- == 0) {
                        onAwait = null
                        firedInside = depth > 0
                        fired = true
                        action()
                    }
                }
                if (inTransaction && depth == 0 && refuseNextTransaction) {
                    refuseNextTransaction = false
                    throw IllegalStateException("The chunk transaction could not be opened")
                }
                depth++
                try {
                    return awaitCounted(inTransaction, block)
                } finally {
                    depth--
                }
            }

            private suspend fun <T> awaitCounted(inTransaction: Boolean, block: suspend Database.() -> T): T {
                if (inTransaction) {
                    onNextTransactionOpen?.let { open ->
                        onNextTransactionOpen = null
                        open()
                        fired = true
                    }
                }
                val action = onNextTransaction?.takeIf { inTransaction }
                    ?: return delegate.await(inTransaction, block)
                onNextTransaction = null
                return delegate.await(inTransaction = true) {
                    action()
                    fired = true
                    block()
                }
            }
        }
    }

    private companion object {
        /** Writes after the second chunk's first await: past its transaction and into its fourth or so row. */
        const val ROW_WRITES = 25
    }
}
