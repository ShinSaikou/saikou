package com.shinsaikou.ui.settings

import app.cash.turbine.test
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummary
import com.shinsaikou.downloads.adoption.AdoptionSummaryStore
import com.shinsaikou.downloads.adoption.AdoptionUnmatched
import com.shinsaikou.downloads.adoption.AdoptionUnmatchedReason
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.Source
import com.shinsaikou.ui.browse.extension.MissingExtensionsFixture
import com.shinsaikou.ui.settings.AdoptionSummaryViewModel.Event
import com.shinsaikou.ui.settings.AdoptionSummaryViewModel.UnmatchedRow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The copy summary's model (unit adoption-unmatched-reasons): the unmatched paths grouped by the
 * walker's reasons, Install offered by the folder's source name and language, a source folder's row
 * following the extension lists, and Copy again over the stored tree or through the picker.
 *
 * Every name here is synthetic; `CONTRIBUTING.md` binds test data too.
 */
class AdoptionSummaryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val extensions = MissingExtensionsFixture()
    private val stored = MutableStateFlow<AdoptionSummary?>(null)
    private val summaryStore = mockk<AdoptionSummaryStore>(relaxed = true) {
        every { summary } returns stored
    }
    private val adoptionState = MutableStateFlow(AdoptionScheduler.State(running = false))
    private val scheduler = mockk<AdoptionScheduler> {
        every { state } returns adoptionState
        coEvery { start(any()) } returns true
        coEvery { reopen(any()) } returns true
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun TestScope.viewModel(): AdoptionSummaryViewModel = AdoptionSummaryViewModel(
        summaryStore = summaryStore,
        scheduler = scheduler,
        extensionManager = extensions.extensionManager,
        sourceManager = extensions.sourceManager,
        dispatchers = AppDispatchers(testDispatcher, testDispatcher, testDispatcher),
    ).also { advanceUntilIdle() }

    @Test
    fun `the paths are grouped by reason, source and entry folders first, then one row per entry for its chapters with no match`() {
        val rows = AdoptionSummaryViewModel.groupUnmatched(
            listOf(
                AdoptionUnmatched("$SOURCE_DIR/Title One/Chapter 1_tmp", AdoptionUnmatchedReason.UNFINISHED),
                chapter("Title One", "Chapter 7_ffffff", mangaId = 1),
                AdoptionUnmatched(
                    "$SOURCE_DIR/Title Elsewhere",
                    AdoptionUnmatchedReason.NOT_IN_LIBRARY,
                    sourceName = "Fixture Source",
                    sourceLang = "EN",
                    title = "Title Elsewhere",
                ),
                chapter("Title One", "Chapter 8_ffffff", mangaId = 1),
                chapter("Title Two", "Chapter 9_ffffff", mangaId = 2),
                AdoptionUnmatched("downloads/Fixture Gone (FR)", AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED, "Fixture Gone", "FR"),
                AdoptionUnmatched("downloads/Fixture Bare", AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED, "Fixture Bare"),
                AdoptionUnmatched("$SOURCE_DIR/Title One/Chapter 2.cbz", AdoptionUnmatchedReason.DUPLICATE),
                // What a summary written before the reasons were kept loads as.
                AdoptionUnmatched("downloads/Fixture Old", AdoptionUnmatchedReason.UNKNOWN),
            ),
        )

        rows shouldContainExactly listOf(
            UnmatchedRow.SourceFolder("downloads/Fixture Gone (FR)", "Fixture Gone", "FR"),
            UnmatchedRow.SourceFolder("downloads/Fixture Bare", "Fixture Bare", null),
            UnmatchedRow.EntryFolder("$SOURCE_DIR/Title Elsewhere", "Title Elsewhere", "Fixture Source (EN)"),
            UnmatchedRow.EntryChapters("$SOURCE_DIR/Title One", "Title One", "Fixture Source (EN)", mangaId = 1, count = 2),
            UnmatchedRow.EntryChapters("$SOURCE_DIR/Title Two", "Title Two", "Fixture Source (EN)", mangaId = 2, count = 1),
            UnmatchedRow.Other("$SOURCE_DIR/Title One/Chapter 1_tmp", AdoptionUnmatchedReason.UNFINISHED),
            UnmatchedRow.Other("$SOURCE_DIR/Title One/Chapter 2.cbz", AdoptionUnmatchedReason.DUPLICATE),
            UnmatchedRow.Other("downloads/Fixture Old", AdoptionUnmatchedReason.UNKNOWN),
        )
    }

    @Test
    fun `install is offered by the folder's source name and language without case, never for an untrusted package or a folder with no language`() {
        val gone = offering(PACKAGE, "Fixture Gone", "fr")
        val other = offering("dev.example.fixture.other", "Fixture Gone", "en")
        val available = listOf(other, gone)

        AdoptionSummaryViewModel.installMatch("fixture gone", "FR", available, emptySet()) shouldBe gone
        AdoptionSummaryViewModel.installMatch("Fixture Gone", "EN", available, emptySet()) shouldBe other
        AdoptionSummaryViewModel.installMatch("Fixture Gone", "DE", available, emptySet()).shouldBeNull()
        AdoptionSummaryViewModel.installMatch("Fixture Gone", null, available, emptySet()).shouldBeNull()
        AdoptionSummaryViewModel.installMatch("Fixture Gone", "FR", available, setOf(PACKAGE)).shouldBeNull()
    }

    @Test
    fun `a source folder's row offers install, installs through the manager's current list, and drops its action once the source is live`() =
        runTest(testDispatcher) {
            val gone = offering(PACKAGE, "Fixture Gone", "fr")
            extensions.availableFlow.value = listOf(gone)
            stored.value = summary(
                AdoptionUnmatched("downloads/Fixture Gone (FR)", AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED, "Fixture Gone", "FR"),
            )
            val viewModel = viewModel()
            viewModel.state.value.loading shouldBe false
            viewModel.state.value.rows.single() shouldBe
                UnmatchedRow.SourceFolder("downloads/Fixture Gone (FR)", "Fixture Gone", "FR", available = gone)

            viewModel.install(gone)
            coVerify(exactly = 1) { extensions.extensionManager.install(gone) }

            extensions.liveFlow.value = listOf(
                mockk<Source> {
                    every { name } returns "Fixture Gone"
                    every { lang } returns "fr"
                },
            )
            advanceUntilIdle()

            (viewModel.state.value.rows.single() as UnmatchedRow.SourceFolder).installed shouldBe true
        }

    @Test
    fun `a summary with a source folder fetches the indexes once while nothing is available, and the row offers the extension the fetch brings`() =
        runTest(testDispatcher) {
            val gone = offering(PACKAGE, "Fixture Gone", "fr")
            extensions.onRefresh = {
                extensions.availableFlow.value = listOf(gone)
                Result.success(Unit)
            }
            stored.value = summary(goneFolder())

            val viewModel = viewModel()

            extensions.refreshCalls shouldBe 1
            (viewModel.state.value.rows.single() as UnmatchedRow.SourceFolder).available shouldBe gone
        }

    @Test
    fun `a summary fetches nothing while the available list already has extensions`() = runTest(testDispatcher) {
        extensions.availableFlow.value = listOf(offering(PACKAGE, "Fixture Other", "fr"))
        stored.value = summary(goneFolder())

        viewModel()

        extensions.refreshCalls shouldBe 0
    }

    @Test
    fun `no summary, no source folder, and a source folder with no language fetch nothing`() = runTest(testDispatcher) {
        viewModel()
        stored.value = summary(AdoptionUnmatched("downloads/Fixture Source/Title One", AdoptionUnmatchedReason.DUPLICATE))
        viewModel()
        stored.value = summary(
            AdoptionUnmatched("downloads/Fixture Bare", AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED, "Fixture Bare"),
        )
        viewModel()

        extensions.refreshCalls shouldBe 0
    }

    @Test
    fun `a fetch that throws leaves the summary working and the row on no extension`() = runTest(testDispatcher) {
        extensions.onRefresh = { error("the catalog is unreachable") }
        stored.value = summary(goneFolder())

        val viewModel = viewModel()

        extensions.refreshCalls shouldBe 1
        viewModel.state.value.loading shouldBe false
        (viewModel.state.value.rows.single() as UnmatchedRow.SourceFolder).available.shouldBeNull()
        extensions.availableFlow.value = listOf(offering(PACKAGE, "Fixture Gone", "fr"))
        advanceUntilIdle()
        (viewModel.state.value.rows.single() as UnmatchedRow.SourceFolder).available?.packageName shouldBe PACKAGE
    }

    @Test
    fun `Copy again starts the scheduler with the stored tree while it can be read`() = runTest(testDispatcher) {
        stored.value = summary(treeUri = TREE)
        val viewModel = viewModel()

        viewModel.copyAgain()
        advanceUntilIdle()

        coVerify(exactly = 1) { scheduler.reopen(TREE) }
        coVerify(exactly = 1) { scheduler.start(TREE) }
    }

    @Test
    fun `Copy again opens the picker at the stored tree once it cannot be read, and with no hint for a summary that kept no tree`() =
        runTest(testDispatcher) {
            coEvery { scheduler.reopen(TREE) } returns false
            stored.value = summary(treeUri = TREE)
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.copyAgain()
                awaitItem() shouldBe Event.PickFolder(TREE)

                stored.value = summary(treeUri = null)
                advanceUntilIdle()
                viewModel.copyAgain()
                awaitItem() shouldBe Event.PickFolder(null)
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 0) { scheduler.start(any()) }

            viewModel.copyFrom(PICKED)
            advanceUntilIdle()
            coVerify(exactly = 1) { scheduler.start(PICKED) }
        }

    private fun goneFolder() =
        AdoptionUnmatched("downloads/Fixture Gone (FR)", AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED, "Fixture Gone", "FR")

    private fun chapter(title: String, name: String, mangaId: Long) = AdoptionUnmatched(
        "$SOURCE_DIR/$title/$name",
        AdoptionUnmatchedReason.NO_CHAPTER,
        sourceName = "Fixture Source",
        sourceLang = "EN",
        title = title,
        mangaId = mangaId,
    )

    private fun summary(vararg unmatched: AdoptionUnmatched, treeUri: String? = TREE) = AdoptionSummary(
        finishedAt = 1,
        matched = 0,
        copied = 0,
        skipped = 0,
        unmatched = unmatched.toList(),
        failures = emptyList(),
        treeUri = treeUri,
    )

    private fun offering(packageName: String, sourceName: String, lang: String): Extension.Available =
        extensions.available(packageName, SOURCE_ID).copy(sources = listOf(AvailableSource(SOURCE_ID, sourceName, lang, "")))

    private companion object {
        const val SOURCE_DIR = "downloads/Fixture Source (EN)"
        const val SOURCE_ID = 7_310_000_000_000_201L
        const val PACKAGE = "dev.example.fixture.gone"
        const val TREE = "content://fixture/tree/primary%3AUpstream"
        const val PICKED = "content://fixture/tree/primary%3AOther"
    }
}
