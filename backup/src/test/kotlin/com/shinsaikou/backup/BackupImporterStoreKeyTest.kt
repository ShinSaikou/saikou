package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.extension.model.ExtensionStore
import java.io.File

/**
 * A restore never changes the signing key of a repository that is already configured: trust is
 * "signed by a configured key", so a shared or crafted backup that swapped it would decide which
 * extensions load. A row with no key yet takes the backup's, and a new repository arrives as is.
 */
class BackupImporterStoreKeyTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 4, chaptersPerManga = 2)
    private val configuredUrl = "https://example.invalid/one/index.min.json"
    private val newUrl = "https://example.invalid/two/index.min.json"
    private val configuredKey = "c".repeat(64)

    private fun configured(signingKey: String) = ExtensionStore(
        indexUrl = configuredUrl,
        name = "Configured Store",
        badgeLabel = "Cfg",
        signingKey = signingKey,
        contact = ExtensionStore.Contact("https://example.invalid/configured", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    @Test
    fun `a configured repository keeps its key and the refusal is reported`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(configured(configuredKey))
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            env.extensionStoreRepository.get(configuredUrl) shouldBe configured(configuredKey)
            env.extensionStoreRepository.get(newUrl)!!.signingKey shouldBe SyntheticBackupFixture.SIGNING_KEY
            report.errors.map { it.kind to it.subject } shouldContain (ImportError.Kind.EXTENSION_STORE to configuredUrl)
        }
    }

    @Test
    fun `a configured repository with no key yet takes the backup's`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(configured(""))
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            env.extensionStoreRepository.get(configuredUrl)!!.signingKey shouldBe SyntheticBackupFixture.SIGNING_KEY
            report.errors.none { it.kind == ImportError.Kind.EXTENSION_STORE } shouldBe true
        }
    }

    @Test
    fun `a blank key in the backup keeps the configured key and restores the rest of the row`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.extensionStoreRepository.upsert(configured(configuredKey))
            val stores = fixture.backupExtensionStores.map { store ->
                if (store.indexUrl != configuredUrl) store else BackupExtensionStore(
                    indexUrl = store.indexUrl,
                    name = "Renamed In Backup",
                    badgeLabel = store.badgeLabel,
                    signingKey = "",
                    contactWebsite = store.contactWebsite,
                    contactDiscord = store.contactDiscord,
                    isLegacy = store.isLegacy,
                    extensionListUrl = store.extensionListUrl,
                )
            }
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture.copy(backupExtensionStores = stores)))

            val report = env.importer.import(file, RestoreOptions()) {}

            val restored = env.extensionStoreRepository.get(configuredUrl)!!
            restored.signingKey shouldBe configuredKey
            restored.name shouldBe "Renamed In Backup"
            report.errors.none { it.kind == ImportError.Kind.EXTENSION_STORE } shouldBe true
        }
    }
}
