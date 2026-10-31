package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.tracking.TrackerIds
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File

/**
 * A MyAnimeList grant restored from a backup with private settings (R96 carry-over 4), through the
 * whole import: the five tracker keys reach the store as the file holds them, and the app's own
 * tracker reads the sign-in back. The grant is a literal string, the shape a backup carries, and it
 * is never decoded here: `MyAnimeListRefreshTest` in `:tracking` decodes and uses the same literal
 * with the app's `Json`, which this environment's tracker does not have. Nothing calls a service.
 * Then a row of a tracker upstream has and this build does not, kept rather than skipped.
 */
class BackupImporterTrackerRestoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a restored MyAnimeList grant is stored as the backup holds it and reads back as signed in`() = runTest {
        val backup = SyntheticBackupFixture.backup(mangaCount = 1, chaptersPerManga = 1).copy(
            backupPreferences = listOf(
                BackupPreference(Preference.privateKey("track_token_1"), StringPreferenceValue(GRANT)),
                BackupPreference(Preference.privateKey("pref_mangasync_username_1"), StringPreferenceValue("fixture-reader")),
                BackupPreference(Preference.privateKey("pref_mangasync_displayname_1"), StringPreferenceValue("Fixture Reader")),
                BackupPreference(Preference.privateKey("pref_mangasync_password_1"), StringPreferenceValue("fixture-access")),
                BackupPreference(Preference.privateKey("pref_tracker_auth_expired_1"), BooleanPreferenceValue(false)),
            ),
        )
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(backup)), RestoreOptions()) {}

            TrackPreferences(env.preferenceStore).trackToken(TrackerIds.MYANIMELIST).get() shouldBe GRANT
            val tracker = env.trackerManager.get(TrackerIds.MYANIMELIST).shouldNotBeNull()
            tracker.isLoggedIn shouldBe true
            tracker.isAuthExpired shouldBe false
            tracker.getDisplayUsername() shouldBe "Fixture Reader"
        }
    }

    /**
     * Upstream v0.21.0 numbers a new tracker 12. No tracker of that id is built here, but its rows
     * are a known service's, kept and written back out as the rows of every other upstream id are,
     * not skipped as an unknown tracker's.
     */
    @Test
    fun `a tracking row with upstream's sync id 12 is restored and exported, not skipped`() = runTest {
        val backup = SyntheticBackupFixture.backup(mangaCount = 1, chaptersPerManga = 1)
        backup.backupManga.single().apply { tracking = listOf(tracking.first().copy(syncId = 12, mediaId = 12_345L)) }
        BackupTestEnvironment(tempDir).use { env ->
            val report = env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(backup)), RestoreOptions()) {}

            report.unknownTrackers.shouldBeEmpty()
            report.errors.filter { it.kind == ImportError.Kind.UNKNOWN_TRACKER_SKIPPED }.shouldBeEmpty()
            val stored = env.trackRepository.getTracksAsFlow().first().single()
            stored.trackerId shouldBe 12L
            stored.remoteId shouldBe 12_345L
            env.export(BackupOptions()).backupManga.single().tracking.map { it.syncId to it.mediaId } shouldBe
                listOf(12 to 12_345L)
        }
    }

    private companion object {
        /** As a backup carries it: upstream's serial names, `created_at` in epoch seconds. */
        const val GRANT = """{"token_type":"Bearer","refresh_token":"fixture-refresh","access_token":"fixture-access",""" +
            """"expires_in":2678400,"created_at":1790000000}"""
    }
}
