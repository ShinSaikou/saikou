package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.tracking.TrackerIds
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Upstream's validator drops every tracker it has no implementation for before it checks login state,
 * so a backup naming one produces no warning at all. The two extra lists close that hole. The
 * environment's tracker set is the app's: MyAnimeList alone (phase 3 unit T2).
 */
class BackupFileValidatorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a source no provider serves is reported by the name the file carries`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val results = env.validator.validate(env.header(SyntheticBackupFixture.backup(mangaCount = 12)))

            results.missingSources shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING.toString())
        }
    }

    @Test
    fun `every known tracker id but MyAnimeList's is unsupported, and MyAnimeList signed out is upstream's missing tracker`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val results = env.validator.validate(env.header(SyntheticBackupFixture.backup(mangaCount = 40)))

            // MyAnimeList (id 1) ships from phase 3 unit T2; every other known id still has no implementation.
            results.unsupportedTrackers shouldContainExactly (TrackerIds.ALL - TrackerIds.MYANIMELIST).sorted()
            // Upstream's own list speaks about the trackers that do ship and are signed out.
            results.missingTrackers shouldContainExactly listOf("MyAnimeList")
        }
    }

    @Test
    fun `a signed-in MyAnimeList is not reported missing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            TrackPreferences(env.preferenceStore).setCredentials(TrackerIds.MYANIMELIST, "reader", "fixture-access-token")

            val results = env.validator.validate(env.header(SyntheticBackupFixture.backup(mangaCount = 40)))

            results.missingTrackers shouldBe emptyList()
            results.unsupportedTrackers shouldContainExactly (TrackerIds.ALL - TrackerIds.MYANIMELIST).sorted()
        }
    }

    /** The ids travel beside the names, so the restore report can offer Sign in (unit restore-report). */
    @Test
    fun `a signed-out tracker's id travels beside its name, and a signed-in one's does not`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val header = env.header(SyntheticBackupFixture.backup(mangaCount = 40))

            env.validator.validate(header).missingTrackerIds shouldContainExactly listOf(TrackerIds.MYANIMELIST)

            TrackPreferences(env.preferenceStore).setCredentials(TrackerIds.MYANIMELIST, "reader", "fixture-access-token")
            env.validator.validate(header).missingTrackerIds shouldBe emptyList()
        }
    }

    @Test
    fun `an id outside the known set is reported separately`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val results = env.validator.validate(env.header(SyntheticBackupFixture.backup(mangaCount = 40)))

            results.unknownTrackers shouldContainExactly listOf(SyntheticBackupFixture.UNKNOWN_TRACKER)
        }
    }
}
