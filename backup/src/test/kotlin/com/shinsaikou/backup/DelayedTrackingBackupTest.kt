package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.TrackerIds
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File

/**
 * The tracker retry store is app state (phase 3 units T1 and TQ): its queued pushes never travel in
 * a `.tachibk`, not even with private settings on, which is how tracker logins travel; and a backup
 * that carries one of its keys anyway never overwrites this device's queue. A push queued here is
 * keyed by a manga id of this device's database and a tracker id, which mean nothing on another.
 *
 * The entries are written by the real [DelayedTrackingStore] into the same preference store the
 * real exporter and restorer use.
 */
class DelayedTrackingBackupTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var env: BackupTestEnvironment
    private lateinit var store: DelayedTrackingStore
    private lateinit var entries: Map<String, Any>

    @BeforeEach
    fun seed() = runTest {
        env = BackupTestEnvironment(tempDir)
        val fixture = SyntheticBackupFixture.backup(mangaCount = 5, chaptersPerManga = 2, privateSettings = true)
        env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

        val preferences = env.preferenceStore
        store = DelayedTrackingStore(preferences) { key, value ->
            if (value == null) preferences.values.remove(key) else preferences.values[key] = value
        }
        val before = preferences.values.keys.toSet()
        store.add(101, TrackerIds.MYANIMELIST, 1001, 12.0)
        store.add(102, TrackerIds.MYANIMELIST, 1002, 3.5)
        entries = preferences.values.filterKeys { it !in before }
        entries.keys shouldHaveSize 2
    }

    @AfterEach
    fun close() = env.close()

    @Test
    fun `a backup with private settings on carries none of the queue's keys or values`() = runTest {
        val backup = env.export(BackupOptions(privateSettings = true))

        // The export did carry private settings, so the flag under test was really on.
        backup.backupPreferences.any { Preference.isPrivate(it.key) } shouldBe true

        val exportedKeys = backup.backupPreferences.map { it.key }.toSet()
        exportedKeys.intersect(entries.keys) shouldBe emptySet()
        val encoded = ProtoBuf.encodeToByteArray(Backup.serializer(), backup).decodeToString()
        entries.keys.filter { it in encoded } shouldBe emptyList()
    }

    @Test
    fun `a backup that carries a queue key never overwrites the queue`() = runTest {
        val key = DelayedTrackingStore.key(101, TrackerIds.MYANIMELIST)
        val errors = mutableListOf<ImportError>()

        env.preferenceRestorer.restoreApp(
            listOf(BackupPreference(key, StringPreferenceValue("1001:999.0"))),
            emptyMap(),
            errors,
        )

        env.preferenceStore.values[key] shouldBe "1001:12.0"
        store.getItems() shouldBe listOf(
            DelayedTrackingStore.DelayedTrackingItem(101, TrackerIds.MYANIMELIST, 1001, 12.0),
            DelayedTrackingStore.DelayedTrackingItem(102, TrackerIds.MYANIMELIST, 1002, 3.5),
        )
    }
}
