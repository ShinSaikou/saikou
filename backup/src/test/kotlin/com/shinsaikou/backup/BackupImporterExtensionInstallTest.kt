package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.FakeSource
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportPhase
import com.shinsaikou.backup.importer.ImportProgress
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.domain.extension.SourceExtensionInstaller
import com.shinsaikou.domain.extension.SourceExtensionInstaller.KnownStore
import com.shinsaikou.domain.extension.SourceExtensionInstaller.WantedSource
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.extension.model.ExtensionStore
import java.io.File

/**
 * Install extensions: once the rest is restored, the sources the file named that no live source
 * serves are handed to the installer by id and by the backup's name, and what it did lands in the
 * report. The installer itself is a fake here; its matching has its own test in `:extensions`.
 */
class BackupImporterExtensionInstallTest {

    @TempDir
    lateinit var tempDir: File

    private val missingName = "Fixture Missing Source"

    private val waiting = SourceExtensionInstaller.FromAddedStore("Waiting Extension", "fixture.waiting", "Added Store", ADDED_STORE_URL)

    /** The fixture, with a name for its missing source as an upstream backup would carry. */
    private val fixture = SyntheticBackupFixture.backup(mangaCount = 4, chaptersPerManga = 1).let { backup ->
        backup.copy(
            backupSources = backup.backupSources.map {
                if (it.sourceId == SyntheticBackupFixture.SOURCE_MISSING) BackupSource(missingName, it.sourceId) else it
            },
        )
    }

    @Test
    fun `the sources without an extension are installed after the entries, and the report says how it went`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.sourceExtensionInstaller.answer = { sources, onProgress ->
                onProgress(SourceExtensionInstaller.Progress(0, 2, "Fixture Extension"))
                onProgress(SourceExtensionInstaller.Progress(1, 2, "Broken Extension"))
                SourceExtensionInstaller.Outcome(
                    installed = listOf("Fixture Extension"),
                    failed = listOf(SourceExtensionInstaller.Failure("Broken Extension", "Install rejected: UNSIGNED")),
                    notFound = sources,
                    alreadyInstalled = listOf("Fixture Installed Extension"),
                    unreadStores = listOf("Fixture Store"),
                    fromAddedStores = listOf(waiting),
                )
            }
            val progress = mutableListOf<ImportProgress>()
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) { progress += it }

            env.sourceExtensionInstaller.requests shouldContainExactly
                listOf(listOf(WantedSource(SyntheticBackupFixture.SOURCE_MISSING, missingName)))
            report.extensionInstalls shouldBe ImportReport.ExtensionInstalls(
                installed = listOf("Fixture Extension"),
                failed = listOf("Broken Extension"),
                notFound = listOf(missingName),
                alreadyInstalled = listOf("Fixture Installed Extension"),
                unreadStores = listOf("Fixture Store"),
                fromAddedStores = listOf(
                    ImportReport.FromAddedStore("Waiting Extension", "fixture.waiting", "Added Store", ADDED_STORE_URL),
                ),
            )
            report.errors.filter { it.kind == ImportError.Kind.EXTENSION_INSTALL }
                .map { it.subject to it.message } shouldContainExactly listOf("Broken Extension" to "Install rejected: UNSIGNED")
            // After the entries, and counted per extension.
            progress.map { it.phase }.dropWhile { it != ImportPhase.EXTENSIONS }.distinct() shouldContainExactly
                listOf(ImportPhase.EXTENSIONS)
            progress.filter { it.phase == ImportPhase.EXTENSIONS }.map { Triple(it.done, it.total, it.currentTitle) } shouldContainExactly
                listOf(Triple(0, 0, null), Triple(0, 2, "Fixture Extension"), Triple(1, 2, "Broken Extension"))
            env.count("mangas") shouldBe 4L
        }
    }

    @Test
    fun `only the stores configured before the restore are handed over as the ones to install from`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(OWN_STORE)
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            env.importer.import(file, RestoreOptions()) {}

            // The fixture's own stores are restored, and are not among them.
            env.extensionStoreRepository.getAll().map { it.indexUrl } shouldContain "https://example.invalid/one/index.min.json"
            env.sourceExtensionInstaller.storesBefore shouldContainExactly
                listOf(listOf(KnownStore(OWN_STORE.indexUrl, OWN_STORE.signingKey)))
            env.checkpoints.storesBeforeFile.exists() shouldBe false
        }
    }

    @Test
    fun `a retry of the same file still hands over the stores from before its first attempt`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(OWN_STORE)
            val bytes = SyntheticBackupFixture.tachibk(fixture)
            env.sourceExtensionInstaller.answer = { _, _ -> throw CancellationException("stopped by the owner") }
            shouldThrow<CancellationException> { env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {} }
            env.checkpoints.storesBeforeFile.exists() shouldBe true

            env.sourceExtensionInstaller.answer = { sources, _ -> SourceExtensionInstaller.Outcome(notFound = sources) }
            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}

            val own = listOf(KnownStore(OWN_STORE.indexUrl, OWN_STORE.signingKey))
            env.sourceExtensionInstaller.storesBefore shouldContainExactly listOf(own, own)
            env.checkpoints.storesBeforeFile.exists() shouldBe false
        }
    }

    @Test
    fun `stores a stopped run with the option off restored do not count as the owner's on the next run`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(OWN_STORE)
            val bytes = SyntheticBackupFixture.tachibk(fixture)
            // Stopped in the entries, after the store phase has written the fixture's stores.
            shouldThrow<CancellationException> {
                env.importer.import(env.writeImportFile(bytes), RestoreOptions(installExtensions = false)) {
                    throw CancellationException("stopped by the owner")
                }
            }
            env.extensionStoreRepository.getAll().map { it.indexUrl } shouldContain "https://example.invalid/one/index.min.json"

            env.sourceExtensionInstaller.answer = { sources, _ -> SourceExtensionInstaller.Outcome(notFound = sources) }
            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}

            env.sourceExtensionInstaller.storesBefore shouldContainExactly
                listOf(listOf(KnownStore(OWN_STORE.indexUrl, OWN_STORE.signingKey)))
        }
    }

    @Test
    fun `an install phase that throws is one more error, and the report is still written`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.sourceExtensionInstaller.answer = { _, _ -> throw IllegalStateException("the index table is locked") }
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            report.extensionInstalls.shouldBeNull()
            report.errors.filter { it.kind == ImportError.Kind.EXTENSION_INSTALL }.map { it.subject to it.message } shouldContainExactly
                listOf("Install extensions" to "the index table is locked")
            env.checkpoints.loadReport() shouldBe report
            env.checkpoints.load().shouldBeNull()
            env.count("mangas") shouldBe 4L
        }
    }

    @Test
    fun `with the option off nothing is installed and the report has no install section`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions(installExtensions = false)) {}

            env.sourceExtensionInstaller.requests.shouldBeEmpty()
            report.extensionInstalls.shouldBeNull()
            report.missingSources shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING)
        }
    }

    @Test
    fun `a backup whose every source is served here asks for no install`() = runTest {
        val sources = BackupTestEnvironment.defaultSources() + FakeSource(SyntheticBackupFixture.SOURCE_MISSING, missingName)
        BackupTestEnvironment(tempDir, sources = sources).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            env.sourceExtensionInstaller.requests.shouldBeEmpty()
            report.extensionInstalls.shouldBeNull()
        }
    }

    @Test
    fun `the install option is the worker input's sixth flag, and an older five-flag input reads as off`() {
        val options = RestoreOptions(categories = false, installExtensions = true)

        RestoreOptions.fromBooleanArray(options.asBooleanArray()) shouldBe options
        RestoreOptions.fromBooleanArray(booleanArrayOf(true, true, true, true, true)).installExtensions shouldBe false
        RestoreOptions(
            libraryEntries = false,
            categories = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
            installExtensions = true,
        ).canRestore() shouldBe false
    }

    private companion object {
        const val ADDED_STORE_URL = "https://example.invalid/added/index.pb"

        /** A store the device had before any restore, which the fixture does not carry. */
        val OWN_STORE = ExtensionStore(
            indexUrl = "https://example.invalid/own/index.pb",
            name = "Own Store",
            badgeLabel = "OWN",
            signingKey = "1".repeat(64),
            contact = ExtensionStore.Contact("https://example.invalid/own", null),
            isLegacy = false,
            extensionListUrl = null,
        )
    }
}
