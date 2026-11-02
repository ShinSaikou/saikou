package com.shinsaikou.ui.backup

import android.net.Uri
import com.shinsaikou.backup.BackupRun
import com.shinsaikou.backup.BackupRunState
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.backup.importer.BackupImportRequest
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.i18n.R
import eu.kanade.tachiyomi.data.backup.BackupDecodeException
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The Backup screen's restore: a file picked with Restore backup is queued as soon as it reads, and
 * one that does not decode is not. A file tapped outside the app is checked first (unit
 * restore-report): read for what it names that this device lacks and held, Restore queues it and
 * Cancel drops it, a file that does not decode says why, and the Restore report row follows the
 * report file. An export or a restore counts as in flight from the moment it is queued, and one that
 * ended while the screen was open says how, even one first seen ended. A pick while a restore runs
 * says so rather than that it was queued.
 */
class BackupViewModelTest {

    @TempDir
    lateinit var filesDir: File

    private val testDispatcher = StandardTestDispatcher()
    private val uri = mockk<Uri>()
    private val results = BackupFileValidator.Results(
        missingSources = listOf("Missing source (EN)"),
        unsupportedTrackers = listOf(2L),
        missingTrackers = listOf("MyAnimeList"),
        missingTrackerIds = listOf(1L),
        unknownTrackers = emptyList(),
    )
    private val validator = mockk<BackupFileValidator> {
        coEvery { validate(uri) } returns results
    }
    private val restoreRun = MutableStateFlow<BackupRun?>(null)
    private val exportRun = MutableStateFlow<BackupRun?>(null)
    private val importRequest = mockk<BackupImportRequest>(relaxed = true) {
        every { start(any(), any()) } returns true
        every { run } returns restoreRun
        every { progress } returns flowOf(null)
    }
    private val scheduler = mockk<BackupScheduler>(relaxed = true) {
        every { manualExportRun } returns exportRun
    }
    private val automaticFiles = mockk<AutomaticBackupFiles> {
        coEvery { list() } returns emptyList()
    }
    private val fileCopy = mockk<BackupFileCopy> {
        every { displayName(uri) } returns "saikou-backup.tachibk"
    }
    private val reportFile get() = File(filesDir, "last-report.json")
    private val checkpoints = mockk<ImportCheckpointStore> {
        every { reportFile } answers { this@BackupViewModelTest.reportFile }
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun TestScope.viewModel(): BackupViewModel = BackupViewModel(
        backupScheduler = scheduler,
        backupImportRequest = importRequest,
        automaticBackupFiles = automaticFiles,
        backupFileCopy = fileCopy,
        validator = validator,
        checkpoints = checkpoints,
        dispatchers = AppDispatchers(testDispatcher, testDispatcher, testDispatcher),
    ).also { advanceUntilIdle() }

    private fun newRun(state: BackupRunState) = BackupRun(UUID.randomUUID(), state)

    @Test
    fun `an export is in flight from its queueing, says how it ended, and an old end says nothing`() = runTest(testDispatcher) {
        exportRun.value = newRun(BackupRunState.SUCCEEDED)
        val viewModel = viewModel()
        viewModel.state.value.exportMessage.shouldBeNull()

        val export = newRun(BackupRunState.ACTIVE)
        exportRun.value = export
        advanceUntilIdle()
        viewModel.state.value.exporting shouldBe true

        exportRun.value = export.copy(state = BackupRunState.SUCCEEDED)
        advanceUntilIdle()
        viewModel.state.value.exporting shouldBe false
        viewModel.state.value.exportMessage shouldBe R.string.backup_created

        val next = newRun(BackupRunState.ACTIVE)
        exportRun.value = next
        advanceUntilIdle()
        exportRun.value = next.copy(state = BackupRunState.FAILED)
        advanceUntilIdle()
        viewModel.state.value.exportMessage shouldBe R.string.backup_failed
    }

    @Test
    fun `an export first seen ended, after one that ended the same way, still says how it ended`() = runTest(testDispatcher) {
        exportRun.value = newRun(BackupRunState.SUCCEEDED)
        val viewModel = viewModel()

        exportRun.value = newRun(BackupRunState.SUCCEEDED)
        advanceUntilIdle()

        viewModel.state.value.exporting shouldBe false
        viewModel.state.value.exportMessage shouldBe R.string.backup_created
    }

    @Test
    fun `a restore is in flight from its queueing and says whether it completed, failed or was cancelled`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.state.value.restoring shouldBe false

        val ends = listOf(
            BackupRunState.SUCCEEDED to R.string.restore_completed,
            BackupRunState.FAILED to R.string.restore_failed,
            BackupRunState.CANCELLED to R.string.restore_cancelled,
        )
        for ((end, message) in ends) {
            val restore = newRun(BackupRunState.ACTIVE)
            restoreRun.value = restore
            advanceUntilIdle()
            viewModel.state.value.restoring shouldBe true

            restoreRun.value = restore.copy(state = end)
            advanceUntilIdle()
            viewModel.state.value.restoring shouldBe false
            viewModel.state.value.restoreMessage shouldBe message
        }
    }

    @Test
    fun `a picked file is queued as soon as it reads, with the options as they are, and nothing is held`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.setRestoreAppSettings(false)

        viewModel.restorePicked(uri)
        viewModel.state.value.checking shouldBe true
        advanceUntilIdle()

        verify(exactly = 1) { importRequest.start(uri, RestoreOptions(appSettings = false)) }
        viewModel.state.value.pendingRestore.shouldBeNull()
        viewModel.state.value.checking shouldBe false
        viewModel.state.value.restoreMessage shouldBe R.string.backup_restore_queued
    }

    @Test
    fun `a restore that ends before the queued line is said ends on its own line`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        every { importRequest.start(any(), any()) } answers {
            restoreRun.value = newRun(BackupRunState.SUCCEEDED)
            true
        }

        viewModel.restorePicked(uri)
        advanceUntilIdle()

        viewModel.state.value.restoring shouldBe false
        viewModel.state.value.restoreMessage shouldBe R.string.restore_completed
    }

    @Test
    fun `a file picked while a restore is running says so instead of that it was queued`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        every { importRequest.start(any(), any()) } returns false

        viewModel.restorePicked(uri)
        advanceUntilIdle()

        viewModel.state.value.restoreMessage shouldBe R.string.backup_restore_busy
    }

    @Test
    fun `a picked file that does not decode says why and is not queued`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        coEvery { validator.validate(uri) } throws BackupDecodeException(BackupDecodeException.Reason.JSON_NOT_SUPPORTED)

        viewModel.restorePicked(uri)
        advanceUntilIdle()

        verify(exactly = 0) { importRequest.start(any(), any()) }
        viewModel.state.value.pendingRestore.shouldBeNull()
        viewModel.state.value.checking shouldBe false
        viewModel.state.value.restoreMessage shouldBe R.string.invalid_backup_file_json
    }

    @Test
    fun `a tapped file is checked and held with the validator's lists, and nothing is queued`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.check(uri)
        viewModel.state.value.checking shouldBe true
        advanceUntilIdle()

        val pending = viewModel.state.value.pendingRestore!!
        pending.uri shouldBe uri
        pending.fileName shouldBe "saikou-backup.tachibk"
        pending.results shouldBe results
        viewModel.state.value.checking shouldBe false
        verify(exactly = 0) { importRequest.start(any(), any()) }
    }

    @Test
    fun `Restore queues the checked file with the options as they are then, and Cancel drops one`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.check(uri)
        advanceUntilIdle()
        viewModel.setRestoreCategories(false)

        viewModel.restorePending()
        advanceUntilIdle()

        verify(exactly = 1) { importRequest.start(uri, RestoreOptions(categories = false)) }
        viewModel.state.value.pendingRestore.shouldBeNull()
        viewModel.state.value.restoreMessage shouldBe R.string.backup_restore_queued

        viewModel.check(uri)
        advanceUntilIdle()
        viewModel.cancelPending()
        advanceUntilIdle()

        viewModel.state.value.pendingRestore.shouldBeNull()
        viewModel.restorePending()
        advanceUntilIdle()
        verify(exactly = 1) { importRequest.start(any(), any()) }
    }

    @Test
    fun `a file that does not decode says the decoder's reason and holds nothing`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        coEvery { validator.validate(uri) } throws BackupDecodeException(BackupDecodeException.Reason.JSON_NOT_SUPPORTED)

        viewModel.check(uri)
        advanceUntilIdle()

        viewModel.state.value.pendingRestore.shouldBeNull()
        viewModel.state.value.checking shouldBe false
        viewModel.state.value.restoreMessage shouldBe R.string.invalid_backup_file_json

        coEvery { validator.validate(uri) } throws BackupDecodeException(BackupDecodeException.Reason.MALFORMED)
        viewModel.check(uri)
        advanceUntilIdle()

        viewModel.state.value.restoreMessage shouldBe R.string.invalid_backup_file_unknown
        verify(exactly = 0) { importRequest.start(any(), any()) }
    }

    @Test
    fun `the Restore report row carries the report file's time, and there is none without a file`() = runTest(testDispatcher) {
        viewModel().state.value.lastReportAt.shouldBeNull()

        reportFile.writeText("{}")
        reportFile.setLastModified(1_700_000_000_000L)

        viewModel().state.value.lastReportAt shouldBe 1_700_000_000_000L
    }
}
