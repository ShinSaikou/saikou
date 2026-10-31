package com.shinsaikou.backup

import android.content.Context
import android.content.res.Resources
import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.FakePreferenceStore
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.TrackerIds
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the restore report reads (unit restore-report): the two lists the run adds to the report,
 * the report read back from `last-report.json` whichever build wrote it, and the completion
 * notification's one line.
 */
class RestoreReportTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 12)

    @Test
    fun `the report names the signed-out trackers and the sources with no extension`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            report.trackersNotSignedIn shouldContainExactly listOf(TrackerIds.MYANIMELIST)
            report.missingSources shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING)
            report.sourcesWithoutExtension shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING)
        }
    }

    /**
     * The second run finds the source already named, so it remembers nothing, and a report built
     * from the remembered list alone would say nothing is missing. A resumed run is the same case.
     */
    @Test
    fun `a second restore of the same file still lists the source it already named`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            val again = env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

            again.stubSourcesRemembered.shouldBeEmpty()
            again.missingSources shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING)
            again.sourcesWithoutExtension shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING)
        }
    }

    @Test
    fun `the store reads back the report the run wrote`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.checkpoints.loadReport().shouldBeNull()
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            env.checkpoints.loadReport() shouldBe report
        }
    }

    /**
     * A report from before this unit has neither list, and the app's Json leaves a null label out
     * when it writes; a key from a later build is skipped. All three still open the report.
     */
    @Test
    fun `a report an older build wrote decodes with both lists empty`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.checkpoints.reportFile.apply { parentFile?.mkdirs() }.writeText(OLDER_REPORT)

            val report = env.checkpoints.loadReport()!!

            report.trackersNotSignedIn.shouldBeEmpty()
            report.missingSources.shouldBeEmpty()
            report.stubSourcesRemembered shouldContainExactly listOf(42L)
            report.sourcesWithoutExtension shouldContainExactly listOf(42L)
            report.errors.single().sourceLabel.shouldBeNull()
            report.mangaFailed shouldBe 1
        }
    }

    @Test
    fun `a report written with both lists, by the app's Json, round trips`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val appJson = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
            val written = report(
                errors = listOf(error(sourceLabel = null)),
                trackersNotSignedIn = listOf(TrackerIds.MYANIMELIST),
                missingSources = listOf(42L, 43L),
            )
            env.checkpoints.reportFile.apply { parentFile?.mkdirs() }
                .writeText(appJson.encodeToString(ImportReport.serializer(), written))

            env.checkpoints.loadReport() shouldBe written
        }
    }

    @Test
    fun `a damaged report reads as no report`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.checkpoints.reportFile.apply { parentFile?.mkdirs() }.writeText("{\"fileSha256\":")

            env.checkpoints.loadReport().shouldBeNull()
        }
    }

    @Test
    fun `the notification line names the errors first, then the missing sources, then nothing`() {
        val resources = mockk<Resources> {
            every { getQuantityString(R.plurals.restore_missing_sources_count, 2, 2) } returns "2 sources need an extension"
        }
        val context = mockk<Context> {
            every { getString(R.string.restore_error_count, 3) } returns "3 entries could not be restored"
            every { this@mockk.resources } returns resources
        }
        val notifier = BackupNotifier(context, SecurityPreferences(FakePreferenceStore()), mockk())
        val threeErrors = List(3) { error(sourceLabel = "Source") }

        notifier.restoreCompleteText(report(errors = threeErrors, missingSources = listOf(1L, 2L))) shouldBe
            "3 entries could not be restored"
        notifier.restoreCompleteText(report(missingSources = listOf(1L), stubSourcesRemembered = listOf(1L, 2L))) shouldBe
            "2 sources need an extension"
        notifier.restoreCompleteText(report()).shouldBeNull()
    }

    private fun error(sourceLabel: String?) = ImportError(
        kind = ImportError.Kind.CHAPTERS,
        subject = "An entry",
        sourceLabel = sourceLabel,
        message = "broken",
        at = 1L,
    )

    private fun report(
        errors: List<ImportError> = emptyList(),
        trackersNotSignedIn: List<Long> = emptyList(),
        missingSources: List<Long> = emptyList(),
        stubSourcesRemembered: List<Long> = emptyList(),
    ) = ImportReport(
        fileSha256 = "00",
        startedAt = 1L,
        finishedAt = 2L,
        cancelled = false,
        resumed = false,
        mangaTotal = 3,
        mangaInserted = 2,
        mangaMerged = 0,
        mangaFailed = 1,
        chaptersWritten = 0,
        historyWritten = 0,
        tracksWritten = 0,
        categoriesCreated = 0,
        preferencesWritten = 0,
        sourcePreferenceFilesWritten = 0,
        extensionStoresUpserted = 0,
        stubSourcesRemembered = stubSourcesRemembered,
        unsupportedTrackers = emptyList(),
        unknownTrackers = emptyList(),
        errors = errors,
        trackersNotSignedIn = trackersNotSignedIn,
        missingSources = missingSources,
    )

    private companion object {
        /** `last-report.json` as the build before this unit wrote it, plus a key from a later one. */
        const val OLDER_REPORT = """{"fileSha256":"ab","startedAt":1,"finishedAt":2,"cancelled":false,""" +
            """"resumed":false,"mangaTotal":3,"mangaInserted":2,"mangaMerged":0,"mangaFailed":1,""" +
            """"chaptersWritten":0,"historyWritten":0,"tracksWritten":0,"categoriesCreated":0,""" +
            """"preferencesWritten":0,"sourcePreferenceFilesWritten":0,"extensionStoresUpserted":0,""" +
            """"stubSourcesRemembered":[42],"unsupportedTrackers":[],"unknownTrackers":[],""" +
            """"errors":[{"kind":"MANGA_ROW","subject":"An entry","message":"broken","at":1}],""" +
            """"fromALaterBuild":true}"""
    }
}
