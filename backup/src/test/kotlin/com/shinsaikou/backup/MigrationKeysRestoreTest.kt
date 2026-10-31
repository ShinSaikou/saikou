package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.FakePreferenceStore
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.domain.preferences.SourcePreferences
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Phase 4 unit MA's restore check: upstream's eight migration keys come back from an upstream backup, each
 * as the type upstream's backup creator writes it (an enum's name, a comma-joined id list, an Int
 * bitmask, booleans), and read back through [SourcePreferences] as the values the file held. A
 * restored `migration_flags` wins over the default of every flag, apart from Saikou's Downloaded
 * chapters, whose bit `UpstreamPreferenceKeys` flips on the way in and out (unit migrate-downloads).
 */
class MigrationKeysRestoreTest {

    @TempDir
    lateinit var tempDir: File

    /** Each key with a value other than its default. 0b100001 is Chapters and Notes. */
    private val migrationPreferences = listOf(
        BackupPreference("pref_migration_sorting", StringPreferenceValue("TOTAL")),
        BackupPreference("pref_migration_direction", StringPreferenceValue("DESCENDING")),
        BackupPreference("migration_sources", StringPreferenceValue("12,34,5")),
        BackupPreference("migration_flags", IntPreferenceValue(0b100001)),
        BackupPreference("migration_deep_search", BooleanPreferenceValue(true)),
        BackupPreference("migration_prioritize_by_chapters", BooleanPreferenceValue(true)),
        BackupPreference("migration_hide_unmatched", BooleanPreferenceValue(true)),
        BackupPreference("migration_hide_without_updates", BooleanPreferenceValue(true)),
    )

    @Test
    fun `the eight migration keys come back from an upstream backup as the values it held`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val backup = SyntheticBackupFixture.backup(mangaCount = 1, chaptersPerManga = 1)
                .copy(backupPreferences = migrationPreferences)
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup))

            val report = env.importer.import(file, RestoreOptions()) {}

            val source = SourcePreferences(env.preferenceStore)
            migrationPreferences.size shouldBe 8
            assertSoftly {
                report.errors.shouldBeEmpty()
                report.preferencesWritten shouldBe 8
                source.migrationSortingMode.get() shouldBe SetMigrateSorting.Mode.TOTAL
                source.migrationSortingDirection.get() shouldBe SetMigrateSorting.Direction.DESCENDING
                source.migrationSources.get() shouldBe listOf(12L, 34L, 5L)
                // Upstream's file has no Downloaded chapters bit, so it restores on, as on a fresh install.
                source.migrationFlags.get() shouldBe
                    setOf(MigrationFlag.CHAPTER, MigrationFlag.NOTES, MigrationFlag.DOWNLOADS)
                source.migrationDeepSearchMode.get() shouldBe true
                source.migrationPrioritizeByChapters.get() shouldBe true
                source.migrationHideUnmatched.get() shouldBe true
                source.migrationHideWithoutUpdates.get() shouldBe true
            }
        }
    }

    @Test
    fun `with nothing stored every flag is on and the other seven keys hold upstream's defaults`() {
        BackupTestEnvironment(tempDir).use { env ->
            val source = SourcePreferences(env.preferenceStore)
            assertSoftly {
                source.migrationFlags.get() shouldBe MigrationFlag.entries.toSet()
                source.migrationSortingMode.get() shouldBe SetMigrateSorting.Mode.ALPHABETICAL
                source.migrationSortingDirection.get() shouldBe SetMigrateSorting.Direction.ASCENDING
                source.migrationSources.get() shouldBe emptyList()
                source.migrationDeepSearchMode.get() shouldBe false
                source.migrationPrioritizeByChapters.get() shouldBe false
                source.migrationHideUnmatched.get() shouldBe false
                source.migrationHideWithoutUpdates.get() shouldBe false
            }
        }
    }

    /**
     * Unit migrate-downloads: Saikou's Downloaded chapters is bit 0b1000000. Nothing stored reads it
     * on (the default is every flag); a stored bitmask without the bit reads it off, which is why
     * `MigrationFlagsUpgrade` adds it once to a value saved before the flag and the restore flips
     * it (the tests below).
     */
    @Test
    fun `Downloaded chapters is on with nothing stored, off under an older bitmask, and stored as bit 0b1000000`() {
        val fresh = SourcePreferences(FakePreferenceStore())
        fresh.migrationFlags.get().contains(MigrationFlag.DOWNLOADS) shouldBe true

        val older = SourcePreferences(FakePreferenceStore(mapOf("migration_flags" to 0b111011)))
        older.migrationFlags.get() shouldBe MigrationFlag.entries.toSet() - MigrationFlag.DOWNLOADS

        val store = FakePreferenceStore()
        SourcePreferences(store).migrationFlags.set(setOf(MigrationFlag.DOWNLOADS))
        store.values["migration_flags"] shouldBe 0b1000000
    }

    @Test
    fun `a migration_flags from upstream or an older Saikou restores with Downloaded chapters on`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            // Every flag upstream has (0b111011): what upstream writes once its options were saved.
            val prefs = listOf(BackupPreference("migration_flags", IntPreferenceValue(0b111011)))

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf()) shouldBe 1

            SourcePreferences(env.preferenceStore).migrationFlags.get() shouldBe MigrationFlag.entries.toSet()
            env.preferenceStore.values["migration_flags"] shouldBe 0b1111011
        }
    }

    @Test
    fun `Downloaded chapters unticked in Saikou stays unticked through its own backup`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val source = SourcePreferences(env.preferenceStore)
            source.migrationFlags.set(setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY))

            val file = env.export(BackupOptions()).backupPreferences.single { it.key == "migration_flags" }

            // In the file the bit says "off"; upstream's fromBit ignores a bit it does not know, so
            // upstream reads the same Chapters and Categories.
            file.value shouldBe IntPreferenceValue(0b1000011)
            (MigrationFlag.fromBit(0b1000011) - MigrationFlag.DOWNLOADS) shouldBe
                setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY)

            BackupTestEnvironment(File(tempDir, "second").apply { mkdirs() }).use { other ->
                other.preferenceRestorer.restoreApp(listOf(file), emptyMap(), mutableListOf()) shouldBe 1
                SourcePreferences(other.preferenceStore).migrationFlags.get() shouldBe
                    setOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY)
            }
        }
    }

    @Test
    fun `Downloaded chapters ticked in Saikou is written as upstream would write the rest and restores on`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            SourcePreferences(env.preferenceStore).migrationFlags.set(setOf(MigrationFlag.NOTES, MigrationFlag.DOWNLOADS))

            val file = env.export(BackupOptions()).backupPreferences.single { it.key == "migration_flags" }
            file.value shouldBe IntPreferenceValue(0b100000)

            BackupTestEnvironment(File(tempDir, "second").apply { mkdirs() }).use { other ->
                other.preferenceRestorer.restoreApp(listOf(file), emptyMap(), mutableListOf())
                SourcePreferences(other.preferenceStore).migrationFlags.get() shouldBe
                    setOf(MigrationFlag.NOTES, MigrationFlag.DOWNLOADS)
            }
        }
    }

    @Test
    fun `a stored flag set is written as upstream's Int bitmask`() {
        BackupTestEnvironment(tempDir).use { env ->
            val source = SourcePreferences(env.preferenceStore)
            source.migrationFlags.set(setOf(MigrationFlag.CATEGORY, MigrationFlag.REMOVE_DOWNLOAD))
            env.preferenceStore.getAll()["migration_flags"] shouldBe 0b10010
        }
    }
}
