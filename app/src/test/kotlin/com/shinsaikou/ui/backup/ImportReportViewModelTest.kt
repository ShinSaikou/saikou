package com.shinsaikou.ui.backup

import android.net.Uri
import app.cash.turbine.test
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.browse.extension.MissingExtensions
import com.shinsaikou.ui.browse.extension.MissingExtensionsFixture
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.TimeZone
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The restore report's model (unit restore-report): the report read back once, its missing sources
 * through [MissingExtensions] with Install offered only where an available extension lists the id,
 * its signed-out trackers by name, its errors by kind, and the exported log.
 */
class ImportReportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val extensions = MissingExtensionsFixture()
    private val signedInFlow = MutableStateFlow(false)
    private val tracker = mockk<Tracker> {
        every { id } returns TRACKER_ID
        every { name } returns "MyAnimeList"
        every { isLoggedInFlow } returns signedInFlow
    }
    private val trackerManager = TrackerManager(setOf(tracker))
    private var stored: ImportReport? = null
    private val checkpoints = mockk<ImportCheckpointStore> {
        every { loadReport() } answers { stored }
    }
    private val fileCopy = mockk<BackupFileCopy>()
    private val adoptionState = MutableStateFlow(AdoptionScheduler.State(running = false))
    private val adoptionScheduler = mockk<AdoptionScheduler> {
        every { state } returns adoptionState
        coEvery { start(any()) } returns true
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun TestScope.viewModel(): ImportReportViewModel = ImportReportViewModel(
        checkpoints = checkpoints,
        missingExtensions = extensions.missingExtensions,
        trackerManager = trackerManager,
        extensionManager = extensions.extensionManager,
        backupFileCopy = fileCopy,
        adoptionScheduler = adoptionScheduler,
        dispatchers = AppDispatchers(testDispatcher, testDispatcher, testDispatcher),
    ).also { advanceUntilIdle() }

    /**
     * A backup carries no downloads, so the report offers the Downloads section's copy from a
     * folder: the folder picked starts the copy, and the row follows the run.
     */
    @Test
    fun `the copy of downloads starts from the folder picked, and the state follows its run`() = runTest(testDispatcher) {
        stored = report()
        val viewModel = viewModel()
        viewModel.state.value.adoption shouldBe AdoptionScheduler.State(running = false)

        viewModel.copyDownloadsFrom(TREE)
        adoptionState.value = AdoptionScheduler.State(running = true, done = 3, total = 10)
        advanceUntilIdle()

        coVerify(exactly = 1) { adoptionScheduler.start(TREE) }
        viewModel.state.value.adoption shouldBe AdoptionScheduler.State(running = true, done = 3, total = 10)
    }

    @Test
    fun `the rows are the report's sources, with install offered only where listed, its trackers and its errors by kind`() =
        runTest(testDispatcher) {
            extensions.remember(SOURCE_LISTED, "Listed")
            extensions.remember(SOURCE_UNLISTED, "Unlisted")
            val offering = extensions.available(PACKAGE, SOURCE_LISTED)
            extensions.availableFlow.value = listOf(offering)
            extensions.countsFlow.value = mapOf(SOURCE_LISTED to 12L, SOURCE_UNLISTED to 1L)
            val chapters = error(ImportError.Kind.CHAPTERS, "Second entry", at = 2L)
            val row = error(ImportError.Kind.MANGA_ROW, "First entry", at = 1L)
            stored = report(
                errors = listOf(chapters, row),
                stubSourcesRemembered = listOf(SOURCE_UNLISTED),
                missingSources = listOf(SOURCE_LISTED, SOURCE_UNLISTED),
                trackersNotSignedIn = listOf(TRACKER_ID, UNSHIPPED_TRACKER_ID),
            )

            val state = viewModel().state.value

            state.loading shouldBe false
            state.report shouldBe stored
            state.sources shouldContainExactly listOf(
                MissingExtensions.Row(SOURCE_LISTED, "Listed (EN)", 12L, offering, query = "Listed"),
                MissingExtensions.Row(SOURCE_UNLISTED, "Unlisted (EN)", 1L, null, query = "Unlisted"),
            )
            // A tracker id this build does not serve has no Sign in to offer.
            state.trackers shouldContainExactly listOf(ImportReportViewModel.TrackerRow(TRACKER_ID, "MyAnimeList"))
            state.errorGroups shouldContainExactly listOf(
                ImportError.Kind.MANGA_ROW to listOf(row),
                ImportError.Kind.CHAPTERS to listOf(chapters),
            )
            state.nothingMissing shouldBe false
        }

    @Test
    fun `a tracker signed in and a source installed since the restore leave the report`() = runTest(testDispatcher) {
        stored = report(missingSources = listOf(SOURCE_LISTED), trackersNotSignedIn = listOf(TRACKER_ID))
        val viewModel = viewModel()
        viewModel.state.value.sources.map { it.sourceId } shouldContainExactly listOf(SOURCE_LISTED)
        viewModel.state.value.trackers.map { it.id } shouldContainExactly listOf(TRACKER_ID)

        signedInFlow.value = true
        extensions.liveFlow.value = listOf(extensions.live(SOURCE_LISTED))
        advanceUntilIdle()

        viewModel.state.value.sources.shouldBeEmpty()
        viewModel.state.value.trackers.shouldBeEmpty()
        viewModel.state.value.nothingMissing shouldBe true
    }

    @Test
    fun `no report file is no report, and a report naming nothing has nothing missing`() = runTest(testDispatcher) {
        val none = viewModel().state.value
        none.loading shouldBe false
        none.report.shouldBeNull()

        stored = report()
        viewModel().state.value.nothingMissing shouldBe true

        stored = report(unknownTrackers = listOf(99L))
        viewModel().state.value.nothingMissing shouldBe false
    }

    @Test
    fun `a report naming missing sources fetches the indexes once while nothing is available, and the row offers the extension the fetch brings`() =
        runTest(testDispatcher) {
            extensions.remember(SOURCE_LISTED, "Listed")
            val offering = extensions.available(PACKAGE, SOURCE_LISTED)
            extensions.onRefresh = {
                extensions.availableFlow.value = listOf(offering)
                Result.success(Unit)
            }
            stored = report(missingSources = listOf(SOURCE_LISTED))

            val viewModel = viewModel()

            extensions.refreshCalls shouldBe 1
            viewModel.state.value.sources.single().available shouldBe offering
        }

    @Test
    fun `a report fetches nothing while the available list already has extensions`() = runTest(testDispatcher) {
        extensions.availableFlow.value = listOf(extensions.available(PACKAGE, SOURCE_UNLISTED))
        stored = report(missingSources = listOf(SOURCE_LISTED))

        viewModel()

        extensions.refreshCalls shouldBe 0
    }

    @Test
    fun `no report file and a report naming no missing sources fetch nothing`() = runTest(testDispatcher) {
        viewModel()
        stored = report(trackersNotSignedIn = listOf(TRACKER_ID))
        viewModel()

        extensions.refreshCalls shouldBe 0
    }

    @Test
    fun `a fetch that throws leaves the report working and the row on no extension`() = runTest(testDispatcher) {
        extensions.remember(SOURCE_LISTED, "Listed")
        extensions.onRefresh = { error("the catalog is unreachable") }
        stored = report(missingSources = listOf(SOURCE_LISTED))

        val viewModel = viewModel()

        extensions.refreshCalls shouldBe 1
        viewModel.state.value.loading shouldBe false
        viewModel.state.value.sources.single().available.shouldBeNull()
        extensions.liveFlow.value = listOf(extensions.live(SOURCE_LISTED))
        advanceUntilIdle()
        viewModel.state.value.sources.shouldBeEmpty()
    }

    @Test
    fun `a fetch that fails with a result leaves the row on no extension`() = runTest(testDispatcher) {
        extensions.remember(SOURCE_LISTED, "Listed")
        extensions.onRefresh = { Result.failure(java.io.IOException("offline")) }
        stored = report(missingSources = listOf(SOURCE_LISTED))

        val viewModel = viewModel()

        extensions.refreshCalls shouldBe 1
        viewModel.state.value.sources.single().available.shouldBeNull()
    }

    @Test
    fun `install goes through the manager's current list`() = runTest(testDispatcher) {
        val current = extensions.available(PACKAGE, SOURCE_LISTED)
        extensions.availableFlow.value = listOf(current)
        stored = report(missingSources = listOf(SOURCE_LISTED))
        val viewModel = viewModel()

        viewModel.install(current.copy(versionName = "0.9"))
        viewModel.install(extensions.available("dev.example.fixture.gone", SOURCE_UNLISTED))

        verify(exactly = 1) { extensions.extensionManager.install(current) }
        verify(exactly = 1) { extensions.extensionManager.install(any()) }
    }

    @Test
    fun `the error log is one stamped line per error in the order the run met them`() {
        val errors = listOf(
            error(ImportError.Kind.CHAPTERS, "An entry", at = 0L, sourceLabel = "A source"),
            error(ImportError.Kind.PREFERENCE, "a_key", at = 61_001L, sourceLabel = null),
        )

        ImportReportViewModel.errorLogText(errors, TimeZone.getTimeZone("UTC")) shouldBe
            "[1970-01-01 00:00:00.000] CHAPTERS An entry (A source): broken\n" +
            "[1970-01-01 00:01:01.001] PREFERENCE a_key: broken\n"
        ImportReportViewModel.errorSubject(errors[0]) shouldBe "An entry (A source)"
        ImportReportViewModel.errorSubject(errors[1]) shouldBe "a_key"
    }

    @Test
    fun `export writes the log into the document and says whether it was saved`() = runTest(testDispatcher) {
        val target = mockk<Uri>()
        val logged = error(ImportError.Kind.CHAPTERS, "An entry", at = 0L)
        stored = report(errors = listOf(logged))
        every { fileCopy.write(target, any()) } returns true
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.exportErrors(target)
            awaitItem() shouldBe ImportReportViewModel.Event.Snackbar(R.string.import_export_error_log_saved)

            every { fileCopy.write(target, any()) } returns false
            viewModel.exportErrors(target)
            awaitItem() shouldBe ImportReportViewModel.Event.Snackbar(R.string.import_export_error_log_failed)
        }
        verify(exactly = 2) { fileCopy.write(target, match { it.endsWith("CHAPTERS An entry (A source): broken\n") }) }
    }

    private fun error(
        kind: ImportError.Kind,
        subject: String,
        at: Long,
        sourceLabel: String? = "A source",
    ) = ImportError(kind = kind, subject = subject, sourceLabel = sourceLabel, message = "broken", at = at)

    private fun report(
        errors: List<ImportError> = emptyList(),
        stubSourcesRemembered: List<Long> = emptyList(),
        missingSources: List<Long> = emptyList(),
        trackersNotSignedIn: List<Long> = emptyList(),
        unknownTrackers: List<Long> = emptyList(),
    ) = ImportReport(
        fileSha256 = "00",
        startedAt = 1L,
        finishedAt = 2L,
        cancelled = false,
        resumed = false,
        mangaTotal = 10,
        mangaInserted = 7,
        mangaMerged = 2,
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
        unknownTrackers = unknownTrackers,
        errors = errors,
        trackersNotSignedIn = trackersNotSignedIn,
        missingSources = missingSources,
    )

    private companion object {
        const val SOURCE_LISTED = 7_310_000_000_000_111L
        const val SOURCE_UNLISTED = 7_310_000_000_000_112L
        const val PACKAGE = "dev.example.fixture.listed"
        const val TRACKER_ID = 1L
        const val UNSHIPPED_TRACKER_ID = 2L
        const val TREE = "content://fixture/tree/primary%3ADownloads"
    }
}
