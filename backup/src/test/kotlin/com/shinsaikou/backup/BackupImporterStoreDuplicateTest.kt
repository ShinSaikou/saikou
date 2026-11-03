package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.extension.model.ExtensionStore
import java.io.File

/**
 * An upstream backup can name one repository twice: its legacy `extension_repos` set by the base
 * address, and its store list by the index under it. The restore used to write one row for each,
 * the first with no name, so the Extension stores screen listed the repository twice. A restore
 * now leaves one row per repository, the named one, and folds in a nameless row an earlier restore
 * left. Every address and key is a placeholder.
 */
class BackupImporterStoreDuplicateTest {

    @TempDir
    lateinit var tempDir: File

    private val base = "https://example.invalid/shared"
    private val indexUrl = "$base/index.pb"

    private val store = BackupExtensionStore(
        indexUrl = indexUrl,
        name = "Shared Store",
        badgeLabel = "SHR",
        signingKey = SyntheticBackupFixture.SIGNING_KEY,
        contactWebsite = "https://example.invalid/site",
        contactDiscord = null,
        isLegacy = false,
        extensionListUrl = null,
    )

    private val restored = ExtensionStore(
        indexUrl = indexUrl,
        name = "Shared Store",
        badgeLabel = "SHR",
        signingKey = SyntheticBackupFixture.SIGNING_KEY,
        contact = ExtensionStore.Contact("https://example.invalid/site", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    /** The legacy row a restore before the fix wrote for [base]. */
    private val nameless = ExtensionStore(
        indexUrl = "$base/repo.json",
        name = "",
        badgeLabel = "",
        signingKey = "",
        contact = ExtensionStore.Contact(website = base, discord = null),
        isLegacy = true,
        extensionListUrl = null,
    )

    /** The fixture with its legacy set naming [base] and its store list holding [store] alone. */
    private fun backup(legacySet: Set<String> = setOf(base), stores: List<BackupExtensionStore> = listOf(store)): Backup {
        val fixture = SyntheticBackupFixture.backup(mangaCount = 2, chaptersPerManga = 1)
        val preferences = fixture.backupPreferences.filterNot { it.key == "extension_repos" } +
            BackupPreference("extension_repos", StringSetPreferenceValue(legacySet))
        return fixture.copy(backupPreferences = preferences, backupExtensionStores = stores)
    }

    @Test
    fun `a repository in both the legacy set and the store list restores as one named row`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup()))

            val report = env.importer.import(file, RestoreOptions()) {}

            env.extensionStoreRepository.getAll() shouldContainExactly listOf(restored)
            report.errors.none { it.kind == ImportError.Kind.EXTENSION_STORE } shouldBe true
        }
    }

    @Test
    fun `the legacy set is told apart by repository, not by host`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val elsewhere = "https://example.invalid/elsewhere"
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup(legacySet = setOf(base, elsewhere))))

            env.importer.import(file, RestoreOptions()) {}

            env.extensionStoreRepository.getAll().map { it.indexUrl } shouldContainExactlyInAnyOrder
                listOf(indexUrl, "$elsewhere/repo.json")
        }
    }

    @Test
    fun `a restore folds in the nameless row an earlier restore left`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(nameless)
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup(legacySet = emptySet())))

            env.importer.import(file, RestoreOptions()) {}

            env.extensionStoreRepository.getAll() shouldContainExactly listOf(restored)
        }
    }

    @Test
    fun `restoring the same file twice still leaves one row`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val bytes = SyntheticBackupFixture.tachibk(backup())

            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}
            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}

            env.extensionStoreRepository.getAll() shouldContainExactly listOf(restored)
        }
    }

    @Test
    fun `with the store list unticked, the legacy set adds no second row for a configured repository`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(restored)
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup()))

            env.importer.import(file, RestoreOptions(extensionStores = false)) {}

            env.extensionStoreRepository.getAll() shouldContainExactly listOf(restored)
        }
    }

    @Test
    fun `a backup naming another key for a configured repository under another address is refused`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val configured = restored.copy(indexUrl = "http://example.invalid/shared/index.pb", signingKey = "c".repeat(64))
            env.extensionStoreRepository.upsert(configured)
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup(legacySet = emptySet())))

            val report = env.importer.import(file, RestoreOptions()) {}

            env.extensionStoreRepository.getAll() shouldContainExactly listOf(configured)
            report.errors.map { it.kind to it.subject } shouldContain (ImportError.Kind.EXTENSION_STORE to indexUrl)
        }
    }
}
