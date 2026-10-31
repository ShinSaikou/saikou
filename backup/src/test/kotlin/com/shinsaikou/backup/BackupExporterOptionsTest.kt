package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.domain.manga.model.ReadingMode
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File

/**
 * Every flag in [BackupOptions] empties exactly one thing. The private flag is the one that costs
 * the owner something when it is wrong, because a file without tracker logins is indistinguishable
 * from a file written by an install that never logged in.
 */
class BackupExporterOptionsTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var env: BackupTestEnvironment

    @BeforeEach
    fun seed() = runTest {
        env = BackupTestEnvironment(tempDir)
        val fixture = SyntheticBackupFixture.backup(mangaCount = 20, chaptersPerManga = 4, privateSettings = true)
        env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}
    }

    @Test
    fun `everything on exports everything`() = runTest {
        val backup = env.export(BackupOptions(privateSettings = true))

        backup.backupManga.shouldNotBeEmpty()
        backup.backupCategories.shouldNotBeEmpty()
        backup.backupSources.shouldNotBeEmpty()
        backup.backupPreferences.shouldNotBeEmpty()
        backup.backupExtensionStores.shouldNotBeEmpty()
        backup.backupManga.first().chapters.shouldNotBeEmpty()
    }

    @Test
    fun `each flag off empties exactly its own list`() = runTest {
        env.export(BackupOptions(libraryEntries = false)).backupManga.shouldBeEmpty()
        env.export(BackupOptions(categories = false)).backupCategories.shouldBeEmpty()
        env.export(BackupOptions(extensionStores = false)).backupExtensionStores.shouldBeEmpty()
        env.export(BackupOptions(appSettings = false)).backupPreferences.shouldBeEmpty()

        env.export(BackupOptions(chapters = false)).backupManga.forEach {
            it.chapters.shouldBeEmpty()
        }
        env.export(BackupOptions(tracking = false)).backupManga.forEach {
            it.tracking.shouldBeEmpty()
        }
        env.export(BackupOptions(history = false)).backupManga.forEach {
            it.history.shouldBeEmpty()
        }
        env.export(BackupOptions(categories = false)).backupManga.forEach {
            it.categories.shouldBeEmpty()
        }
    }

    @Test
    fun `read entries off leaves only the library`() = runTest {
        val withRead = env.export(BackupOptions()).backupManga
        val withoutRead = env.export(BackupOptions(readEntries = false)).backupManga

        withRead.count { !it.favorite } shouldBeGreaterThan 0
        withoutRead.all { it.favorite } shouldBe true
    }

    @Test
    fun `private settings off carries no private key anywhere`() = runTest {
        val off = env.export(BackupOptions(privateSettings = false))
        off.backupPreferences.none { Preference.isPrivate(it.key) } shouldBe true
        off.backupSourcePreferences.flatMap { it.prefs }.none { Preference.isPrivate(it.key) } shouldBe true

        val on = env.export(BackupOptions(privateSettings = true))
        on.backupPreferences.any { Preference.isPrivate(it.key) } shouldBe true
    }

    @Test
    fun `app state never travels, whatever the flags say`() = runTest {
        val backup = env.export(BackupOptions(privateSettings = true))

        backup.backupPreferences.none { Preference.isAppState(it.key) } shouldBe true
    }

    @Test
    fun `the system category is never exported and categories travel as orders`() = runTest {
        val backup = env.export(BackupOptions())

        backup.backupCategories.none { it.id == 0L } shouldBe true
        val orders = env.categoryRepository.getAll().filterNot { it.isSystemCategory }.map { it.order }
        backup.backupManga.flatMap { it.categories }.toSet().all { it in orders } shouldBe true
    }

    @Test
    fun `the legacy viewer field is the reading mode only`() = runTest {
        val backup = env.export(BackupOptions())

        backup.backupManga.forEach { manga ->
            manga.viewer shouldBe (manga.viewer_flags!! and ReadingMode.MASK)
        }
    }
}
