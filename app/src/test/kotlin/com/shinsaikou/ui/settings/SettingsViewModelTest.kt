package com.shinsaikou.ui.settings

import android.app.Activity
import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.library.interactor.RebuildLibraryCounts
import com.shinsaikou.domain.preferences.BackupPreferences
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.source.interactor.languageOrder
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadManifestSweep
import com.shinsaikou.downloads.OrphanDownloads
import com.shinsaikou.downloads.OrphanTitle
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.export.DownloadExportScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummary
import com.shinsaikou.downloads.adoption.AdoptionSummaryStore
import com.shinsaikou.downloads.adoption.AdoptionUnmatched
import com.shinsaikou.downloads.adoption.AdoptionUnmatchedReason
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.source.StubSource
import com.shinsaikou.tracking.FakeOAuthTracker
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.main.BatteryExemption
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import java.io.File
import java.util.Locale
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Covers the two settings mutations that must reach a scheduler, not just a preference: writing
 * an interval alone would leave the periodic worker on its old schedule. Also the enabled
 * languages picker's candidates, which no settings section can read off a preference directly
 * (ruling R57), and the Data section's chapter cache row, which is the first production caller of
 * `ChapterCache.clear()` and `readableSize()` (ruling R56).
 */
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val store = InMemoryPreferenceStore()

    private val libraryPreferences = LibraryPreferences(store)
    private val backupPreferences = BackupPreferences(store)
    private val uiPreferences = UiPreferences(store)
    private val readerPreferences = ReaderPreferences(store)
    private val sourcePreferences = SourcePreferences(store)
    private val extensionsPreferences = ExtensionsPreferences(store)
    private val basePreferences = BasePreferences(store)
    private val downloadPreferences = DownloadPreferences(store)

    private val availableFlow = MutableStateFlow<List<Extension.Available>>(emptyList())
    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val extensionManager = mockk<ExtensionManager> {
        every { availableExtensions } returns availableFlow
        every { installedExtensions } returns installedFlow
    }

    private val libraryUpdateScheduler = mockk<LibraryUpdateScheduler>(relaxed = true)
    private val backupScheduler = mockk<BackupScheduler>(relaxed = true)
    private val batteryExemption = FakeBatteryExemption()
    private val chapterCache = mockk<ChapterCache>()
    private val downloadManifestRepository = mockk<DownloadManifestRepository>()
    private val downloadManifest = mockk<DownloadManifest>(relaxed = true)
    private val downloadManifestSweep = mockk<DownloadManifestSweep>(relaxed = true)
    private val storagePreferences = StoragePreferences(store)
    private val adoptionState = MutableStateFlow(AdoptionScheduler.State(running = false))
    private val adoptionScheduler = mockk<AdoptionScheduler>(relaxed = true) {
        every { state } returns adoptionState
    }
    private val adoptionSummary = MutableStateFlow<AdoptionSummary?>(null)
    private val adoptionSummaryStore = mockk<AdoptionSummaryStore>(relaxed = true) {
        every { summary } returns adoptionSummary
    }
    private val categories = listOf(
        Category(id = 0, name = "", order = 0, flags = 0),
        Category(id = 3, name = "Reading", order = 1, flags = 0),
    )
    private val getCategories = mockk<GetCategories> {
        every { subscribe() } returns flowOf(categories)
    }
    private val orphanDownloads = mockk<OrphanDownloads>()
    private val resetViewerFlags = mockk<ResetViewerFlags>()
    private val rebuildLibraryCounts = mockk<RebuildLibraryCounts>()
    private val trustStore = mockk<TrustStore>(relaxed = true)

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel(trackers: Set<Tracker> = emptySet()) = SettingsViewModel(
        uiPreferences = uiPreferences,
        libraryPreferences = libraryPreferences,
        readerPreferences = readerPreferences,
        sourcePreferences = sourcePreferences,
        backupPreferences = backupPreferences,
        extensionsPreferences = extensionsPreferences,
        basePreferences = basePreferences,
        downloadPreferences = downloadPreferences,
        storagePreferences = storagePreferences,
        networkPreferences = NetworkPreferences(store),
        securityPreferences = SecurityPreferences(store),
        extensionManager = extensionManager,
        libraryUpdateScheduler = libraryUpdateScheduler,
        backupScheduler = backupScheduler,
        batteryExemption = batteryExemption,
        chapterCache = chapterCache,
        downloadManifestRepository = downloadManifestRepository,
        downloadManifest = downloadManifest,
        downloadManifestSweep = downloadManifestSweep,
        adoptionScheduler = adoptionScheduler,
        adoptionSummaryStore = adoptionSummaryStore,
        downloadExportScheduler = mockk<DownloadExportScheduler>(relaxed = true) {
            every { state } returns MutableStateFlow(DownloadExportScheduler.State(running = false))
        },
        getCategories = getCategories,
        resetCategoryFlags = mockk(relaxed = true),
        orphanDownloads = orphanDownloads,
        resetViewerFlags = resetViewerFlags,
        rebuildLibraryCounts = rebuildLibraryCounts,
        trustStore = trustStore,
        appInfo = mockk(),
        trackerManager = TrackerManager(trackers),
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    @Test
    fun `setAutoUpdateInterval writes the preference and then reschedules the library update worker`() = runTest(testDispatcher) {
        every { libraryUpdateScheduler.setup() } answers {
            libraryPreferences.autoUpdateInterval.get() shouldBe 12
        }

        val viewModel = viewModel()
        viewModel.setAutoUpdateInterval(12)
        advanceUntilIdle()

        libraryPreferences.autoUpdateInterval.get() shouldBe 12
        verify(exactly = 1) { libraryUpdateScheduler.setup() }
    }

    @Test
    fun `setAutoUpdateInterval with zero still reschedules so the worker can cancel itself`() = runTest(testDispatcher) {
        libraryPreferences.autoUpdateInterval.set(6)
        every { libraryUpdateScheduler.setup() } answers {
            libraryPreferences.autoUpdateInterval.get() shouldBe 0
        }

        val viewModel = viewModel()
        viewModel.setAutoUpdateInterval(0)
        advanceUntilIdle()

        libraryPreferences.autoUpdateInterval.get() shouldBe 0
        verify(exactly = 1) { libraryUpdateScheduler.setup() }
    }

    @Test
    fun `setBackupInterval writes the preference and then reschedules the backup export worker`() = runTest(testDispatcher) {
        every { backupScheduler.setup() } answers {
            backupPreferences.backupInterval.get() shouldBe 24
        }

        val viewModel = viewModel()
        viewModel.setBackupInterval(24)
        advanceUntilIdle()

        backupPreferences.backupInterval.get() shouldBe 24
        verify(exactly = 1) { backupScheduler.setup() }
    }

    @Test
    fun `setBackupInterval with zero still reschedules so the worker can cancel itself`() = runTest(testDispatcher) {
        backupPreferences.backupInterval.set(12)
        every { backupScheduler.setup() } answers {
            backupPreferences.backupInterval.get() shouldBe 0
        }

        val viewModel = viewModel()
        viewModel.setBackupInterval(0)
        advanceUntilIdle()

        backupPreferences.backupInterval.get() shouldBe 0
        verify(exactly = 1) { backupScheduler.setup() }
    }

    // ---- Auto-download and the update restrictions (phase 3 unit AD) ----

    @Test
    fun `a device restriction write sets the preference and then re-arms the library update worker`() = runTest(testDispatcher) {
        val chosen = setOf(LibraryUpdateRestrictions.DEVICE_NETWORK_NOT_METERED, LibraryUpdateRestrictions.DEVICE_CHARGING)
        every { libraryUpdateScheduler.setup() } answers {
            libraryPreferences.autoUpdateDeviceRestrictions.get() shouldBe chosen
        }

        val viewModel = viewModel()
        viewModel.setAutoUpdateDeviceRestrictions(chosen)
        advanceUntilIdle()

        libraryPreferences.autoUpdateDeviceRestrictions.get() shouldBe chosen
        verify(exactly = 1) { libraryUpdateScheduler.setup() }
    }

    @Test
    fun `the update categories row writes upstream's include and exclude keys`() = runTest(testDispatcher) {
        viewModel().setUpdateCategories(included = setOf("3"), excluded = setOf("0"))

        libraryPreferences.updateCategories.key() shouldBe "library_update_categories"
        libraryPreferences.updateCategoriesExclude.key() shouldBe "library_update_categories_exclude"
        libraryPreferences.updateCategories.get() shouldBe setOf("3")
        libraryPreferences.updateCategoriesExclude.get() shouldBe setOf("0")
        verify(exactly = 0) { libraryUpdateScheduler.setup() }
    }

    @Test
    fun `the new chapter categories row writes upstream's include and exclude keys`() = runTest(testDispatcher) {
        viewModel().setDownloadNewChapterCategories(included = setOf("0", "3"), excluded = emptySet())

        downloadPreferences.downloadNewChapterCategories.key() shouldBe "download_new_categories"
        downloadPreferences.downloadNewChapterCategoriesExclude.key() shouldBe "download_new_categories_exclude"
        downloadPreferences.downloadNewChapterCategories.get() shouldBe setOf("0", "3")
        downloadPreferences.downloadNewChapterCategoriesExclude.get() shouldBe emptySet()
    }

    @Test
    fun `the state lists every category, the system one included, for the two category rows`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.categories shouldBe categories
    }

    @Test
    fun `the language candidates are every source language on offer plus the stored set, all first`() =
        runTest(testDispatcher) {
            // The owner's restored set carries en-US, which no source here reports; it has to stay
            // a candidate or it could never be unchecked. A multi-language extension contributes
            // each of its sources' languages, not the "all" its own code collapses to.
            sourcePreferences.enabledLanguages.set(setOf("all", "en-US", "en"))
            availableFlow.value = listOf(available(sourceLangs = listOf("ja", "fr", "ja")))
            installedFlow.value = listOf(installed(sourceLangs = listOf("de", "all")))

            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.languageCandidates.first() shouldBe "all"
            viewModel.state.value.languageCandidates shouldContainExactlyInAnyOrder
                listOf("all", "en-US", "en", "ja", "fr", "de")
        }

    /**
     * Phase 5 unit BX: the dialog lists languages in the filters' order, `languageOrder` with no
     * enabled half. In French, Ewe is "éwé": a plain string compare puts it after "français", the
     * collator between "estonien" and "français"; and "en-US" reads "anglais (États-Unis)", apart
     * from "en", where the display language alone named both "anglais".
     */
    @Test
    fun `the language candidates come in the filters' order, with no enabled half`() = runTest(testDispatcher) {
        val previous = Locale.getDefault(Locale.Category.DISPLAY)
        Locale.setDefault(Locale.Category.DISPLAY, Locale.FRENCH)
        try {
            sourcePreferences.enabledLanguages.set(setOf("fr", "en-US"))
            availableFlow.value = listOf(available(sourceLangs = listOf("ee", "en", "et")))

            val viewModel = viewModel()
            advanceUntilIdle()

            val codes = listOf("ee", "en", "en-US", "et", "fr")
            viewModel.state.value.languageCandidates shouldContainExactly codes.sortedWith(languageOrder(emptySet()))
            viewModel.state.value.languageCandidates shouldContainExactly listOf("en", "en-US", "et", "ee", "fr")
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, previous)
        }
    }

    @Test
    fun `the language candidates follow the manager and the stored set`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.languageCandidates shouldContainExactly listOf("en")

        availableFlow.value = listOf(available(sourceLangs = listOf("ja")))
        sourcePreferences.enabledLanguages.set(setOf("en", "pt-BR"))
        advanceUntilIdle()

        viewModel.state.value.languageCandidates shouldContainExactlyInAnyOrder listOf("en", "ja", "pt-BR")
    }

    @Test
    fun `the chapter cache size is unknown until the data section asks for it`() = runTest(testDispatcher) {
        coEvery { chapterCache.readableSize() } returns "12 MB"

        val viewModel = viewModel()
        advanceUntilIdle()

        // Measuring walks the whole directory, so building the view model must not do it.
        viewModel.state.value.chapterCacheSize shouldBe null
        coVerify(exactly = 0) { chapterCache.readableSize() }

        viewModel.refreshChapterCacheSize()
        advanceUntilIdle()

        viewModel.state.value.chapterCacheSize shouldBe "12 MB"
    }

    @Test
    fun `clearing the chapter cache clears it and then measures what is left`() = runTest(testDispatcher) {
        coEvery { chapterCache.readableSize() } returnsMany listOf("12 MB", "8 kB")
        coEvery { chapterCache.clear() } returns 40
        val viewModel = viewModel()
        viewModel.refreshChapterCacheSize()
        advanceUntilIdle()
        viewModel.state.value.chapterCacheSize shouldBe "12 MB"

        viewModel.clearChapterCache()
        advanceUntilIdle()

        coVerifyOrder {
            chapterCache.readableSize()
            chapterCache.clear()
            chapterCache.readableSize()
        }
        viewModel.state.value.chapterCacheSize shouldBe "8 kB"
    }

    @Test
    fun `the storage rows are unknown until the data section asks, then hold the manifest's size and titles`() =
        runTest(testDispatcher) {
            val titles = listOf(OrphanTitle(mangaId = 4L, title = "Fixture Manga", chapterIds = listOf(40L), sizeBytes = 900L))
            coEvery { orphanDownloads.list() } returns titles
            coEvery { downloadManifest.getTotalSizeBytes() } returns 5_000L

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.orphanTitles shouldBe null
            viewModel.state.value.downloadsSize shouldBe null

            viewModel.refreshStorage()
            advanceUntilIdle()

            viewModel.state.value.orphanTitles shouldBe titles
            viewModel.state.value.downloadsSize shouldBe 5_000L
        }

    @Test
    fun `refresh library covers starts a manual update with details and says when one is already running`() =
        runTest(testDispatcher) {
            coEvery { libraryUpdateScheduler.startNow(refreshMetadata = true) } returnsMany listOf(true, false)
            val viewModel = viewModel()
            val results = mutableListOf<Boolean>()

            viewModel.refreshLibraryCovers { results += it }
            viewModel.refreshLibraryCovers { results += it }
            advanceUntilIdle()

            results shouldBe listOf(true, false)
            coVerify(exactly = 2) { libraryUpdateScheduler.startNow(categoryId = null, refreshMetadata = true) }
        }

    @Test
    fun `reset viewer flags reports what the reset answered, and revoke trust clears the manual trust`() =
        runTest(testDispatcher) {
            coEvery { resetViewerFlags.await() } returnsMany listOf(true, false)
            val viewModel = viewModel()
            val results = mutableListOf<Boolean>()

            viewModel.resetViewerFlags { results += it }
            advanceUntilIdle()
            viewModel.resetViewerFlags { results += it }
            advanceUntilIdle()
            viewModel.revokeTrustedExtensions()

            results shouldBe listOf(true, false)
            verify(exactly = 1) { trustStore.revokeAll() }
        }

    @Test
    fun `rebuild library counts reports what the rebuild answered`() =
        runTest(testDispatcher) {
            coEvery { rebuildLibraryCounts.await() } returnsMany listOf(true, false)
            val viewModel = viewModel()
            val results = mutableListOf<Boolean>()

            viewModel.rebuildLibraryCounts { results += it }
            advanceUntilIdle()
            viewModel.rebuildLibraryCounts { results += it }
            advanceUntilIdle()

            results shouldBe listOf(true, false)
            coVerify(exactly = 2) { rebuildLibraryCounts.await() }
        }

    @Test
    fun `the launch time clear preference is upstream's key and starts off`() {
        val preference = viewModel().libraryPreferences.autoClearChapterCache

        preference.key() shouldBe "auto_clear_chapter_cache"
        preference.get() shouldBe false
    }

    /**
     * A download whose chapter came back under a new row is matched before it is counted, not
     * left for the next start-up sweep while the row offers to delete it.
     */
    @Test
    fun `the unmatched downloads are unknown until the downloads section asks, and re-matched before they are read`() =
        runTest(testDispatcher) {
            coEvery { downloadManifestRepository.getWithoutChapter() } returns listOf(orphan(7L), orphan(8L))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.orphanDownloads shouldBe null

            viewModel.refreshOrphanDownloads()
            advanceUntilIdle()
            viewModel.state.value.orphanDownloads?.map { it.chapterId } shouldBe listOf(7L, 8L)
            coVerifyOrder {
                downloadManifestSweep.rematch()
                downloadManifestRepository.getWithoutChapter()
            }
        }

    /**
     * The rows are re-matched and re-read when the owner confirms, not taken from the list on
     * screen: a row matched in between has a chapter again and keeps its files.
     */
    @Test
    fun `deleting unmatched downloads re-matches, deletes what is unmatched now, then counts again`() =
        runTest(testDispatcher) {
            coEvery { downloadManifestRepository.getWithoutChapter() } returnsMany listOf(
                listOf(orphan(7L), orphan(8L)),
                listOf(orphan(8L)),
                emptyList(),
            )
            val viewModel = viewModel()
            viewModel.refreshOrphanDownloads()
            advanceUntilIdle()

            viewModel.deleteOrphanDownloads()
            advanceUntilIdle()

            coVerifyOrder {
                downloadManifestSweep.rematch()
                downloadManifestRepository.getWithoutChapter()
                downloadManifestSweep.rematch()
                downloadManifestRepository.getWithoutChapter()
                downloadManifest.delete(listOf(8L))
            }
            viewModel.state.value.orphanDownloads shouldBe emptyList()
        }

    /** The confirmation named what it would delete; a row it never showed is not deleted with it. */
    @Test
    fun `deleting unmatched downloads leaves a row that became unmatched after the confirmation opened`() =
        runTest(testDispatcher) {
            coEvery { downloadManifestRepository.getWithoutChapter() } returnsMany listOf(
                listOf(orphan(7L)),
                listOf(orphan(7L), orphan(9L)),
                listOf(orphan(9L)),
            )
            val viewModel = viewModel()
            viewModel.refreshOrphanDownloads()
            advanceUntilIdle()

            viewModel.deleteOrphanDownloads()
            advanceUntilIdle()

            coVerify(exactly = 1) { downloadManifest.delete(listOf(7L)) }
            viewModel.state.value.orphanDownloads?.map { it.chapterId } shouldBe listOf(9L)
        }

    @Test
    fun `the confirmation names the first five by entry title and chapter, and counts the rest`() =
        runTest(testDispatcher) {
            coEvery { downloadManifestRepository.getWithoutChapter() } returns
                listOf("Delta", "alpha", "Echo", "charlie", "Golf", "bravo", "foxtrot")
                    .mapIndexed { index, title -> orphan(index.toLong(), mangaTitle = title) }
            val viewModel = viewModel()

            viewModel.refreshOrphanDownloads()
            advanceUntilIdle()

            val state = viewModel.state.value
            state.orphanDownloads?.size shouldBe 7
            state.orphansNamed.map { it.mangaTitle to it.chapterName } shouldBe listOf(
                "alpha" to "Chapter 1",
                "bravo" to "Chapter 5",
                "charlie" to "Chapter 3",
                "Delta" to "Chapter 0",
                "Echo" to "Chapter 2",
            )
            state.orphansMore shouldBe 2
        }

    @Test
    fun `requesting the battery exemption goes to the platform helper`() = runTest(testDispatcher) {
        val activity = mockk<Activity>()

        viewModel().requestBatteryExemption(activity)

        batteryExemption.requests shouldContainExactly listOf(activity)
    }

    /** The picked tree goes to the scheduler, which records it as the run's state and starts the worker. */
    @Test
    fun `startAdoption hands the picked tree to the adoption scheduler`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.startAdoption("content://fixture/tree/primary%3AUpstream")
        advanceUntilIdle()

        coVerify(exactly = 1) { adoptionScheduler.start("content://fixture/tree/primary%3AUpstream") }
    }

    /** The row's progress and the last summary are the scheduler's and the store's, as they change. */
    @Test
    fun `the adoption state and the last summary follow the scheduler and the store`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { adoptionSummaryStore.load() }
        viewModel.state.value.adoptionSummary shouldBe null

        adoptionState.value = AdoptionScheduler.State(running = true, done = 2, total = 5)
        val summary = AdoptionSummary(
            finishedAt = 1,
            matched = 5,
            copied = 4,
            skipped = 1,
            unmatched = listOf(AdoptionUnmatched("downloads/Fixture Unknown (EN)", AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED)),
            failures = emptyList(),
        )
        adoptionSummary.value = summary
        advanceUntilIdle()

        viewModel.state.value.adoption shouldBe AdoptionScheduler.State(running = true, done = 2, total = 5)
        viewModel.state.value.adoptionSummary shouldBe summary
    }

    /**
     * Tracking is listed only while a configured tracker is in the graph (phase 3 unit T1): none at
     * all, as every build has until T2, and one whose build-time key is blank both hide it; a
     * configured one shows it after Downloads.
     */
    @Test
    fun `the tracking section is listed only while a configured tracker is in the graph`() {
        viewModel().sections shouldBe SettingsSection.entries - SettingsSection.TRACKING
        viewModel(setOf(FakeOAuthTracker(id = 1L, isConfigured = false))).sections shouldBe
            SettingsSection.entries - SettingsSection.TRACKING
        val shown = viewModel(setOf(FakeOAuthTracker(id = 1L, isConfigured = false), FakeOAuthTracker(id = 2L))).sections
        shown shouldBe SettingsSection.entries
        shown.indexOf(SettingsSection.TRACKING) shouldBe shown.indexOf(SettingsSection.DOWNLOADS) + 1
    }
}

private fun orphan(chapterId: Long, mangaTitle: String = "Fixture Manga") = DownloadManifestEntry(
    id = chapterId,
    chapterId = chapterId,
    mangaId = 1L,
    sourceId = 1L,
    mangaTitle = mangaTitle,
    mangaUrl = "/manga/1",
    chapterUrl = "/chapter/$chapterId",
    chapterName = "Chapter $chapterId",
    scanlator = null,
    chapterDirName = "Chapter $chapterId",
    pageCount = 1,
    sizeBytes = 0,
    dateDownloaded = 0,
)

private fun available(sourceLangs: List<String>) = Extension.Available(
    packageName = "dev.example.fixture.available",
    name = "Fixture Available",
    versionName = "1.0",
    versionCode = 1L,
    libVersion = 1.6,
    lang = "all",
    isNsfw = false,
    sources = sourceLangs.mapIndexed { index, lang ->
        AvailableSource(id = index.toLong(), name = "Fixture Source $index", lang = lang, homeUrl = "")
    },
    apkUrl = "https://example.invalid/fixture.apk",
    iconUrl = "https://example.invalid/fixture.png",
    jarUrl = null,
    store = ExtensionStore(
        indexUrl = "https://example.invalid/index.json",
        name = "Fixture Index",
        badgeLabel = "FI",
        signingKey = "fixture-signing-key",
        contact = ExtensionStore.Contact("https://example.invalid", null),
        isLegacy = false,
        extensionListUrl = null,
    ),
)

private fun installed(sourceLangs: List<String>) = Extension.Installed(
    packageName = "dev.example.fixture.installed",
    name = "Fixture Installed",
    versionName = "1.0",
    versionCode = 1L,
    libVersion = 1.6,
    lang = "all",
    isNsfw = false,
    sources = sourceLangs.mapIndexed { index, lang -> StubSource(id = 100L + index, name = "Fixture $index", lang = lang) },
    extFile = File("fixture.ext"),
)

/** Records what the settings row asked the platform for, since the platform answers nothing back. */
private class FakeBatteryExemption : BatteryExemption {

    val requests = mutableListOf<Activity>()

    override fun isExempt(): Boolean = false

    override fun request(activity: Activity) {
        requests += activity
    }
}
