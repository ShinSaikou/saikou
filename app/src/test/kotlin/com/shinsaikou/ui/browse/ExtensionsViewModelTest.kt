package com.shinsaikou.ui.browse

import app.cash.turbine.test
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.source.interactor.ToggleLanguage
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadError
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.extensions.store.UpdateExtensionStores
import com.shinsaikou.i18n.R
import com.shinsaikou.source.StubSource
import com.shinsaikou.ui.browse.extension.ExtensionFilterViewModel
import com.shinsaikou.ui.fixture.LaggingStateFlow
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import tachiyomi.domain.extension.interactor.GetExtensionStoreCountAsFlow
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * Every ExtensionManager flow is a MutableStateFlow field so a test can both drive the combined
 * projection and change what a later install/update/trust lookup sees.
 *
 * The fields carry a Flow suffix because the mockk configuration block puts ExtensionManager itself
 * in scope as an implicit receiver, and a bare name matching one of its properties would bind to the
 * mock rather than to the field being handed back.
 */
class ExtensionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = AppDispatchers(
        io = testDispatcher,
        default = testDispatcher,
        main = testDispatcher,
    )

    private val initializedFlow = MutableStateFlow(true)
    private val updatesFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val availableFlow = MutableStateFlow<List<Extension.Available>>(emptyList())
    private val untrustedFlow = MutableStateFlow<List<Extension.Untrusted>>(emptyList())
    private val loadErrorsFlow = MutableStateFlow<List<LoadResult.Error>>(emptyList())
    private val installStepsFlow = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    private val installFailuresFlow = MutableStateFlow<Map<String, Throwable>>(emptyMap())
    private val notLoadedUpdatesFlow = MutableStateFlow<Set<String>>(emptySet())
    private val storeCountFlow = MutableStateFlow(1L)
    private val refreshingStoresFlow = MutableStateFlow<Set<String>>(emptySet())
    private val storeRefreshFailuresFlow = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)

    private val extensionManager = mockk<ExtensionManager> {
        every { isInitialized } returns initializedFlow
        every { updates } returns updatesFlow
        every { installedExtensions } returns installedFlow
        every { availableExtensions } returns availableFlow
        every { untrustedExtensions } returns untrustedFlow
        every { loadErrors } returns loadErrorsFlow
        every { installSteps } returns installStepsFlow
        every { installFailures } returns installFailuresFlow
        every { notLoadedUpdates } returns notLoadedUpdatesFlow
        every { refreshingStores } returns refreshingStoresFlow
        every { storeRefreshFailures } returns storeRefreshFailuresFlow
    }
    private val updateExtensionStores = mockk<UpdateExtensionStores>()
    private val getExtensionStoreCount = mockk<GetExtensionStoreCountAsFlow>()
    private val sourcePreferences = SourcePreferences(InMemoryPreferenceStore())

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        every { getExtensionStoreCount.invoke() } returns storeCountFlow
        // Opening the page with nothing available fetches the index once; a test that cares about
        // that call counts it, and every other test only needs it to succeed quietly.
        coEvery { extensionManager.refreshAvailable() } returns Result.success(Unit)
        // "all" is a literal code (ruling R57), so every language a fixture uses is enabled here
        // unless a test narrows the set on purpose.
        sourcePreferences.enabledLanguages.set(setOf("all", "en", "ja", "de"))
        sourcePreferences.showNsfwSource.set(true)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun buildViewModel() = ExtensionsViewModel(
        extensionManager = extensionManager,
        sourcePreferences = sourcePreferences,
        updateExtensionStores = updateExtensionStores,
        getExtensionStoreCountAsFlow = getExtensionStoreCount,
        libVersionPolicy = LibVersionPolicy(ExtensionsPreferences(InMemoryPreferenceStore())),
        dispatchers = appDispatchers,
    )

    private fun ExtensionsViewModel.State.rows(group: ExtensionGroup): List<ExtensionUi> =
        groups.firstOrNull { it.first == group }?.second.orEmpty()

    @Test
    fun `a fully populated emission is cut into the contract's groups, in its order`() = runTest(testDispatcher) {
        val withUpdate = installed(PACKAGE_HAS_UPDATE, name = "Fixture Has Update", hasUpdate = true)
        installedFlow.value = listOf(installed(PACKAGE_NO_UPDATE, name = "Fixture No Update"), withUpdate)
        updatesFlow.value = listOf(withUpdate)
        untrustedFlow.value = listOf(untrusted(PACKAGE_UNTRUSTED, name = "Fixture Untrusted"))
        loadErrorsFlow.value = listOf(LoadResult.Error(PACKAGE_BROKEN, LoadError.CLASS_LOAD_FAILED))
        availableFlow.value = listOf(
            available(PACKAGE_AVAILABLE, name = "Fixture Available", lang = "en"),
            available(PACKAGE_AVAILABLE_JA, name = "Fixture Japanese", lang = "ja"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        state.isInitialized shouldBe true
        state.storeCount shouldBe 1L
        state.updatesCount shouldBe 1
        state.groups.map { it.first } shouldContainExactly listOf(
            ExtensionGroup.Updates,
            ExtensionGroup.Installed,
            ExtensionGroup.Untrusted,
            ExtensionGroup.Errors,
            ExtensionGroup.AvailableLanguage("en"),
            ExtensionGroup.AvailableLanguage("ja"),
        )
        state.rows(ExtensionGroup.Updates).map { it.packageName } shouldContainExactly listOf(PACKAGE_HAS_UPDATE)
        // Installed is the installed list minus whatever the Updates group already claimed.
        state.rows(ExtensionGroup.Installed).map { it.packageName } shouldContainExactly listOf(PACKAGE_NO_UPDATE)
        state.rows(ExtensionGroup.Errors).map { it.packageName } shouldContainExactly listOf(PACKAGE_BROKEN)
    }

    @Test
    fun `an empty group is dropped rather than rendered as a bare heading`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed(PACKAGE_NO_UPDATE, name = "Fixture"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.first } shouldContainExactly listOf(ExtensionGroup.Installed)
        viewModel.state.value.isEmpty shouldBe false
    }

    @Test
    fun `an available entry already on disk in any state is dropped from the language groups`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed(PACKAGE_DUP, name = "Fixture Duplicate"))
        untrustedFlow.value = listOf(untrusted(PACKAGE_UNTRUSTED, name = "Fixture Untrusted"))
        loadErrorsFlow.value = listOf(LoadResult.Error(PACKAGE_BROKEN, LoadError.UNSIGNED))
        availableFlow.value = listOf(
            available(PACKAGE_DUP, name = "Fixture Duplicate"),
            available(PACKAGE_UNTRUSTED, name = "Fixture Untrusted"),
            available(PACKAGE_BROKEN, name = "Fixture Broken"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.rows(ExtensionGroup.AvailableLanguage("en")).shouldBeEmpty()
    }

    @Test
    fun `an update row carries the index entry for its package`() = runTest(testDispatcher) {
        val withUpdate = installed(PACKAGE_HAS_UPDATE, name = "Fixture Has Update", hasUpdate = true)
        installedFlow.value = listOf(withUpdate)
        updatesFlow.value = listOf(withUpdate)
        availableFlow.value = listOf(available(PACKAGE_HAS_UPDATE, name = "Fixture Has Update"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val row = viewModel.state.value.rows(ExtensionGroup.Updates).single() as ExtensionUi.Update
        row.available?.packageName shouldBe PACKAGE_HAS_UPDATE
    }

    @Test
    fun `a mature entry is hidden while showNsfwSource is off`() = runTest(testDispatcher) {
        sourcePreferences.showNsfwSource.set(false)
        availableFlow.value = listOf(
            available(PACKAGE_AVAILABLE, name = "Fixture Available"),
            available(PACKAGE_AVAILABLE_JA, name = "Fixture Mature", isNsfw = true),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.rows(ExtensionGroup.AvailableLanguage("en"))
            .map { it.packageName } shouldContainExactly listOf(PACKAGE_AVAILABLE)
    }

    @Test
    fun `the language groups narrow to the enabled set`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        availableFlow.value = listOf(
            available(PACKAGE_AVAILABLE, name = "Fixture English", lang = "en"),
            available(PACKAGE_AVAILABLE_JA, name = "Fixture Japanese", lang = "ja"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.first } shouldContainExactly listOf(
            ExtensionGroup.AvailableLanguage("en"),
        )
    }

    /**
     * Phase 5 unit BX: the extension filter screen writes `source_languages`, which this page
     * already re-projects on, so its switches reach the list with no wiring of their own. Both view
     * models are built over the one preference store, as the app graph's single
     * `SourcePreferences` makes them.
     */
    @Test
    fun `a language switched on and off on the extension filter screen joins and leaves the list`() =
        runTest(testDispatcher) {
            sourcePreferences.enabledLanguages.set(setOf("en"))
            availableFlow.value = listOf(
                available(PACKAGE_AVAILABLE, name = "Fixture English", lang = "en"),
                available(PACKAGE_AVAILABLE_JA, name = "Fixture Japanese", lang = "ja"),
            )
            val page = buildViewModel()
            val filter = ExtensionFilterViewModel(
                preferences = sourcePreferences,
                getExtensionLanguages = GetExtensionLanguages(sourcePreferences, extensionManager),
                toggleLanguage = ToggleLanguage(sourcePreferences),
                dispatchers = appDispatchers,
            )
            advanceUntilIdle()
            page.state.value.groups.map { it.first } shouldContainExactly listOf(ExtensionGroup.AvailableLanguage("en"))

            filter.toggle("ja")
            advanceUntilIdle()
            page.state.value.groups.map { it.first } shouldContainExactly listOf(
                ExtensionGroup.AvailableLanguage("en"),
                ExtensionGroup.AvailableLanguage("ja"),
            )

            filter.toggle("en")
            advanceUntilIdle()
            page.state.value.groups.map { it.first } shouldContainExactly listOf(ExtensionGroup.AvailableLanguage("ja"))
        }

    @Test
    fun `all is a literal code for multi-language sources, not a wildcard`() = runTest(testDispatcher) {
        // Ruling R57, on the owner's own restored set: English plus multi-language sources. A
        // single-language entry outside it stays hidden even though "all" is enabled.
        sourcePreferences.enabledLanguages.set(setOf("all", "en-US", "en"))
        availableFlow.value = listOf(
            available(PACKAGE_AVAILABLE, name = "Fixture English", lang = "en"),
            available(PACKAGE_AVAILABLE_JA, name = "Fixture Japanese", lang = "ja"),
            available(PACKAGE_A, name = "Fixture Multi", lang = "all"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.first } shouldContainExactly listOf(
            ExtensionGroup.AvailableLanguage("all"),
            ExtensionGroup.AvailableLanguage("en"),
        )
    }

    @Test
    fun `an extension shows when any one of its sources is in an enabled language`() = runTest(testDispatcher) {
        // The index collapses an extension whose sources disagree to "all", so matching on that
        // code alone would hide it from an owner who enabled only one of its languages.
        sourcePreferences.enabledLanguages.set(setOf("ja"))
        availableFlow.value = listOf(
            available(PACKAGE_AVAILABLE, name = "Fixture Several", lang = "all", sourceLangs = listOf("en", "ja")),
            available(PACKAGE_A, name = "Fixture Elsewhere", lang = "all", sourceLangs = listOf("en", "de")),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.rows(ExtensionGroup.AvailableLanguage("all"))
            .map { it.packageName } shouldContainExactly listOf(PACKAGE_AVAILABLE)
    }

    /**
     * An index entry that lists no source (the legacy index format allows one) answers to its own
     * code, the rule the extension filter offers its switches by (`Extension.Available.languages()`,
     * phase 5 unit BX): listed while that code is enabled, hidden once it is off.
     */
    @Test
    fun `an entry that lists no source is matched by its own code`() = runTest(testDispatcher) {
        sourcePreferences.enabledLanguages.set(setOf("ko"))
        availableFlow.value = listOf(
            available(PACKAGE_AVAILABLE, name = "Fixture No Source", lang = "ko", sourceLangs = emptyList()),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.state.value.rows(ExtensionGroup.AvailableLanguage("ko"))
            .map { it.packageName } shouldContainExactly listOf(PACKAGE_AVAILABLE)

        sourcePreferences.enabledLanguages.set(setOf("en"))
        advanceUntilIdle()
        viewModel.state.value.groups.shouldBeEmpty()
    }

    @Test
    fun `installed mature extensions are hidden with the switch off, and their index entries stay hidden too`() =
        runTest(testDispatcher) {
            // Ruling R55: upstream hides installed mature extensions as well as available ones. The
            // package is still on disk, so its index entry must not come back as installable.
            sourcePreferences.showNsfwSource.set(false)
            val mature = installed(PACKAGE_DUP, name = "Fixture Mature", isNsfw = true)
            val matureUpdate = installed(PACKAGE_HAS_UPDATE, name = "Fixture Mature Update", hasUpdate = true, isNsfw = true)
            installedFlow.value = listOf(installed(PACKAGE_NO_UPDATE, name = "Fixture Clean"), mature, matureUpdate)
            updatesFlow.value = listOf(matureUpdate)
            availableFlow.value = listOf(
                available(PACKAGE_DUP, name = "Fixture Mature", isNsfw = true),
                available(PACKAGE_HAS_UPDATE, name = "Fixture Mature Update", isNsfw = true),
            )

            val viewModel = buildViewModel()
            advanceUntilIdle()

            val state = viewModel.state.value
            state.groups.map { it.first } shouldContainExactly listOf(ExtensionGroup.Installed)
            state.rows(ExtensionGroup.Installed).map { it.packageName } shouldContainExactly listOf(PACKAGE_NO_UPDATE)
            state.updatesCount shouldBe 1

            sourcePreferences.showNsfwSource.set(true)
            advanceUntilIdle()

            viewModel.state.value.rows(ExtensionGroup.Installed).map { it.packageName } shouldContainExactly
                listOf(PACKAGE_NO_UPDATE, PACKAGE_DUP)
            viewModel.state.value.rows(ExtensionGroup.Updates).map { it.packageName } shouldContainExactly
                listOf(PACKAGE_HAS_UPDATE)
        }

    @Test
    fun `opening the page with nothing available fetches the index once, without the store pass`() =
        runTest(testDispatcher) {
            val viewModel = buildViewModel()
            viewModel.state.value.isRefreshing shouldBe true
            advanceUntilIdle()

            coVerify(exactly = 1) { extensionManager.refreshAvailable() }
            coVerify(exactly = 0) { updateExtensionStores.await() }
            viewModel.state.value.isRefreshing shouldBe false
            viewModel.state.value.refreshError.shouldBeNull()
        }

    @Test
    fun `opening the page with the index already loaded does not fetch it again`() = runTest(testDispatcher) {
        availableFlow.value = listOf(available(PACKAGE_AVAILABLE, name = "Fixture Available"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { extensionManager.refreshAvailable() }
        viewModel.state.value.isRefreshing shouldBe false
    }

    @Test
    fun `a failed fetch on open reaches the banner`() = runTest(testDispatcher) {
        val failure = IllegalStateException("fixture open refresh failure")
        coEvery { extensionManager.refreshAvailable() } returns Result.failure(failure)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.refreshError shouldBe failure
        viewModel.state.value.isRefreshing shouldBe false
    }

    @Test
    fun `a repository being read on its own shows the page as refreshing until it is read`() = runTest(testDispatcher) {
        availableFlow.value = listOf(available(PACKAGE_AVAILABLE, name = "Fixture Available"))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.state.value.isRefreshingStore shouldBe false

        refreshingStoresFlow.value = setOf("https://example.invalid/added/index.json")
        advanceUntilIdle()
        viewModel.state.value.isRefreshingStore shouldBe true

        // An emission from the manager underneath keeps it, as it keeps the refresh flag.
        availableFlow.value = emptyList()
        advanceUntilIdle()
        viewModel.state.value.isRefreshingStore shouldBe true

        refreshingStoresFlow.value = emptySet()
        advanceUntilIdle()
        viewModel.state.value.isRefreshingStore shouldBe false
    }

    @Test
    fun `a repository that could not be read on its own reaches the banner`() = runTest(testDispatcher) {
        availableFlow.value = listOf(available(PACKAGE_AVAILABLE, name = "Fixture Available"))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        val failure = IllegalStateException("fixture added repository failure")

        storeRefreshFailuresFlow.emit(failure)
        advanceUntilIdle()

        viewModel.state.value.refreshError shouldBe failure
    }

    @Test
    fun `an obsolete installed extension leads the installed group`() = runTest(testDispatcher) {
        installedFlow.value = listOf(
            installed(PACKAGE_A, name = "Fixture A"),
            installed(PACKAGE_NO_UPDATE, name = "Fixture Z").copy(isObsolete = true),
        )
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.rows(ExtensionGroup.Installed).map { it.packageName } shouldContainExactly
            listOf(PACKAGE_NO_UPDATE, PACKAGE_A)
    }

    @Test
    fun `language groups lead with all and en, then read alphabetically`() = runTest(testDispatcher) {
        availableFlow.value = listOf(
            available("dev.example.fixture.de", name = "Fixture German", lang = "de"),
            available("dev.example.fixture.ja", name = "Fixture Japanese", lang = "ja"),
            available("dev.example.fixture.en", name = "Fixture English", lang = "en"),
            available("dev.example.fixture.all", name = "Fixture Multi", lang = "all"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.state.value.groups.map { it.first } shouldContainExactly listOf(
            ExtensionGroup.AvailableLanguage("all"),
            ExtensionGroup.AvailableLanguage("en"),
            ExtensionGroup.AvailableLanguage("de"),
            ExtensionGroup.AvailableLanguage("ja"),
        )
    }

    @Test
    fun `the query narrows every group at once, on name, package and source name`() = runTest(testDispatcher) {
        installedFlow.value = listOf(
            installed(PACKAGE_NO_UPDATE, name = "Fixture Alpha", sourceName = "Alpha Reader"),
            installed(PACKAGE_A, name = "Fixture Beta", sourceName = "Beta Reader"),
        )
        availableFlow.value = listOf(available(PACKAGE_AVAILABLE, name = "Fixture Gamma"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.search("alpha reader")
        advanceUntilIdle()
        viewModel.state.value.rows(ExtensionGroup.Installed)
            .map { it.packageName } shouldContainExactly listOf(PACKAGE_NO_UPDATE)
        viewModel.state.value.rows(ExtensionGroup.AvailableLanguage("en")).shouldBeEmpty()

        viewModel.search(PACKAGE_AVAILABLE)
        advanceUntilIdle()
        viewModel.state.value.groups.map { it.first } shouldContainExactly listOf(
            ExtensionGroup.AvailableLanguage("en"),
        )

        viewModel.search("nothing matches this")
        advanceUntilIdle()
        viewModel.state.value.isEmpty shouldBe true
        viewModel.state.value.searchQuery shouldBe "nothing matches this"
    }

    @Test
    fun `a blank query is the same as no query`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed(PACKAGE_NO_UPDATE, name = "Fixture"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.search("   ")
        advanceUntilIdle()

        viewModel.state.value.rows(ExtensionGroup.Installed).map { it.packageName } shouldContainExactly
            listOf(PACKAGE_NO_UPDATE)
    }

    @Test
    fun `a failed install's reason reaches the page beside its step`() = runTest(testDispatcher) {
        val refusal = InstallFailedException(InstallRejection.DOWNGRADE)
        availableFlow.value = listOf(available(PACKAGE_AVAILABLE, name = "Fixture Available"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        installStepsFlow.value = mapOf(PACKAGE_AVAILABLE to InstallStep.Error)
        installFailuresFlow.value = mapOf(PACKAGE_AVAILABLE to refusal)
        advanceUntilIdle()

        viewModel.state.value.installSteps[PACKAGE_AVAILABLE] shouldBe InstallStep.Error
        viewModel.state.value.installFailures[PACKAGE_AVAILABLE] shouldBe refusal
    }

    @Test
    fun `an entry built for a lib this host does not load is labelled, installed or not`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed(PACKAGE_NO_UPDATE, name = "Fixture No Update"))
        availableFlow.value = listOf(
            // The catalog moved the installed package to lib 1.7: shown, never offered.
            available(PACKAGE_NO_UPDATE, name = "Fixture No Update", versionCode = 2L, libVersion = 1.7),
            available(PACKAGE_AVAILABLE, name = "Fixture Available", libVersion = 1.7),
            available(PACKAGE_AVAILABLE_JA, name = "Fixture Japanese", lang = "ja"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        state.rows(ExtensionGroup.Installed) shouldContainExactly listOf(
            ExtensionUi.Installed(installed(PACKAGE_NO_UPDATE, name = "Fixture No Update"), updateNeedsNewerApp = true),
        )
        (state.rows(ExtensionGroup.AvailableLanguage("en")).single() as ExtensionUi.Available).libSupported shouldBe false
        (state.rows(ExtensionGroup.AvailableLanguage("ja")).single() as ExtensionUi.Available).libSupported shouldBe true
    }

    @Test
    fun `install looks the package up in available extensions and installs that exact extension`() = runTest(testDispatcher) {
        val extension = available(PACKAGE_AVAILABLE, name = "Fixture Available")
        availableFlow.value = listOf(extension)
        every { extensionManager.install(extension) } returns Job()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.install(extension)

        verify(exactly = 1) { extensionManager.install(extension) }
    }

    @Test
    fun `install does nothing when the package left the index between the frame and the tap`() = runTest(testDispatcher) {
        val stale = available(PACKAGE_MISSING, name = "Fixture Missing")

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.install(stale)

        verify(exactly = 0) { extensionManager.install(any()) }
    }

    @Test
    fun `update looks the package up in installed extensions and updates that exact extension`() = runTest(testDispatcher) {
        val extension = installed(PACKAGE_HAS_UPDATE, name = "Fixture Has Update", hasUpdate = true)
        installedFlow.value = listOf(extension)
        updatesFlow.value = listOf(extension)
        every { extensionManager.update(extension) } returns Job()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.update(extension)

        verify(exactly = 1) { extensionManager.update(extension) }
    }

    @Test
    fun `updateAll updates every pending update, whatever the query is hiding`() = runTest(testDispatcher) {
        val first = installed(PACKAGE_HAS_UPDATE, name = "Fixture One", hasUpdate = true)
        val second = installed(PACKAGE_A, name = "Fixture Two", hasUpdate = true)
        installedFlow.value = listOf(first, second)
        updatesFlow.value = listOf(first, second)
        every { extensionManager.update(any()) } returns Job()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.search("fixture one")
        advanceUntilIdle()

        viewModel.updateAll()

        verify(exactly = 1) { extensionManager.update(first) }
        verify(exactly = 1) { extensionManager.update(second) }
    }

    @Test
    fun `an extension that did not load and has an update is offered it, counted and covered by update all`() =
        runTest(testDispatcher) {
            val broken = LoadResult.Error(PACKAGE_BROKEN, LoadError.CLASS_LOAD_FAILED, versionCode = 1L, libVersion = 1.6)
            loadErrorsFlow.value = listOf(broken)
            untrustedFlow.value = listOf(untrusted(PACKAGE_UNTRUSTED, name = "Fixture Untrusted"))
            notLoadedUpdatesFlow.value = setOf(PACKAGE_BROKEN)
            every { extensionManager.updatePackage(PACKAGE_BROKEN) } returns Job()

            val viewModel = buildViewModel()
            advanceUntilIdle()

            val state = viewModel.state.value
            state.rows(ExtensionGroup.Errors) shouldContainExactly listOf(ExtensionUi.LoadError(broken, hasUpdate = true))
            (state.rows(ExtensionGroup.Untrusted).single() as ExtensionUi.Untrusted).hasUpdate shouldBe false
            state.updatesCount shouldBe 1
            state.hasUpdates shouldBe true

            viewModel.updateAll()

            verify(exactly = 1) { extensionManager.updatePackage(PACKAGE_BROKEN) }
        }

    @Test
    fun `a row that did not load is uninstalled through its load result`() = runTest(testDispatcher) {
        // A file with no package name has only its path to go by.
        val unreadable = LoadResult.Error(null, LoadError.NOT_AN_EXTENSION, file = File("broken.ext"))
        loadErrorsFlow.value = listOf(unreadable)
        coEvery { extensionManager.uninstall(unreadable) } just Runs

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.uninstall(ExtensionUi.LoadError(unreadable))
        advanceUntilIdle()

        coVerify(exactly = 1) { extensionManager.uninstall(unreadable) }
    }

    @Test
    fun `cancelInstall cancels the job install returned, and only the once`() = runTest(testDispatcher) {
        val extension = available(PACKAGE_AVAILABLE, name = "Fixture Available")
        availableFlow.value = listOf(extension)
        val job = Job()
        every { extensionManager.install(extension) } returns job

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.install(extension)
        viewModel.cancelInstall(PACKAGE_AVAILABLE)

        job.isCancelled shouldBe true

        // The entry is gone, so a second tap on a row that has not caught up cannot cancel a job
        // some later install put in its place.
        viewModel.cancelInstall(PACKAGE_AVAILABLE)
        verify(exactly = 1) { extensionManager.install(extension) }
    }

    @Test
    fun `a second install of a package still installing starts nothing, and cancel stops the first`() =
        runTest(testDispatcher) {
            // A tap on the row during its install used to start a second job for the same package,
            // which took the first one's place in the map: cancel stopped only the newer download.
            val extension = available(PACKAGE_AVAILABLE, name = "Fixture Available")
            availableFlow.value = listOf(extension)
            val job = Job()
            every { extensionManager.install(extension) } returns job

            val viewModel = buildViewModel()
            advanceUntilIdle()

            viewModel.install(extension)
            viewModel.install(extension)
            verify(exactly = 1) { extensionManager.install(extension) }

            viewModel.cancelInstall(PACKAGE_AVAILABLE)
            job.isCancelled shouldBe true
        }

    @Test
    fun `a package whose install has ended can be installed again`() = runTest(testDispatcher) {
        // Error is an ended install too, so Retry and a tap on the row both start a new one.
        val extension = available(PACKAGE_AVAILABLE, name = "Fixture Available")
        availableFlow.value = listOf(extension)
        val ended = Job().apply { complete() }
        every { extensionManager.install(extension) } returnsMany listOf(ended, Job())

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.install(extension)
        viewModel.install(extension)

        verify(exactly = 2) { extensionManager.install(extension) }
    }

    @Test
    fun `update all while an update is running starts no second one for that package`() = runTest(testDispatcher) {
        val first = installed(PACKAGE_HAS_UPDATE, name = "Fixture One", hasUpdate = true)
        val broken = LoadResult.Error(PACKAGE_BROKEN, LoadError.CLASS_LOAD_FAILED, versionCode = 1L, libVersion = 1.6)
        installedFlow.value = listOf(first)
        updatesFlow.value = listOf(first)
        loadErrorsFlow.value = listOf(broken)
        notLoadedUpdatesFlow.value = setOf(PACKAGE_BROKEN)
        every { extensionManager.update(first) } returns Job()
        every { extensionManager.updatePackage(PACKAGE_BROKEN) } returns Job()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.update(first)
        viewModel.updateNotLoaded(PACKAGE_BROKEN)
        viewModel.updateAll()

        verify(exactly = 1) { extensionManager.update(first) }
        verify(exactly = 1) { extensionManager.updatePackage(PACKAGE_BROKEN) }
    }

    @Test
    fun `cancelInstall is a no op for a package with no job`() = runTest(testDispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.cancelInstall(PACKAGE_MISSING)

        viewModel.state.value.dialog.shouldBeNull()
    }

    @Test
    fun `the uninstall dialog is what calls the manager, and it closes behind the confirmation`() = runTest(testDispatcher) {
        val extension = installed(PACKAGE_NO_UPDATE, name = "Fixture")
        installedFlow.value = listOf(extension)
        coEvery { extensionManager.uninstall(PACKAGE_NO_UPDATE) } just Runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val row = ExtensionUi.Installed(extension)
        viewModel.showDialog(ExtensionsViewModel.Dialog.Uninstall(row))
        viewModel.state.value.dialog shouldBe ExtensionsViewModel.Dialog.Uninstall(row)

        viewModel.uninstall(row)
        advanceUntilIdle()

        viewModel.state.value.dialog.shouldBeNull()
        coVerify(exactly = 1) { extensionManager.uninstall(PACKAGE_NO_UPDATE) }
    }

    @Test
    fun `the trust dialog carries the signer, and trusting looks the package up again`() = runTest(testDispatcher) {
        val extension = untrusted(PACKAGE_UNTRUSTED, name = "Fixture Untrusted")
        untrustedFlow.value = listOf(extension)
        coEvery { extensionManager.trust(extension) } just Runs

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.showDialog(ExtensionsViewModel.Dialog.Trust(extension))
        val dialog = viewModel.state.value.dialog as ExtensionsViewModel.Dialog.Trust
        dialog.ext.signatureHash shouldBe "fixture-hash"

        viewModel.trust(extension)
        advanceUntilIdle()

        viewModel.state.value.dialog.shouldBeNull()
        coVerify(exactly = 1) { extensionManager.trust(extension) }
    }

    @Test
    fun `a dialog survives an emission from the manager underneath it`() = runTest(testDispatcher) {
        val extension = untrusted(PACKAGE_UNTRUSTED, name = "Fixture Untrusted")
        untrustedFlow.value = listOf(extension)

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.showDialog(ExtensionsViewModel.Dialog.Trust(extension))

        installStepsFlow.value = mapOf(PACKAGE_AVAILABLE to InstallStep.Downloading)
        advanceUntilIdle()

        viewModel.state.value.dialog shouldBe ExtensionsViewModel.Dialog.Trust(extension)
        viewModel.state.value.installSteps shouldBe mapOf(PACKAGE_AVAILABLE to InstallStep.Downloading)
    }

    @Test
    fun `refresh updates stores then refreshes available and sends a snackbar event on success`() = runTest(testDispatcher) {
        indexAlreadyLoaded()
        coEvery { updateExtensionStores.await() } returns 0
        coEvery { extensionManager.refreshAvailable() } returns Result.success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()

            val event = awaitItem()
            event shouldBe ExtensionsViewModel.Event.Snackbar(R.string.ext_stores_refreshed)
        }
        viewModel.state.value.refreshError.shouldBeNull()
        coVerify(exactly = 1) { updateExtensionStores.await() }
        coVerify(exactly = 1) { extensionManager.refreshAvailable() }
    }

    @Test
    fun `refresh reports a refreshAvailable failure as an event and as the banner`() = runTest(testDispatcher) {
        indexAlreadyLoaded()
        val failure = RuntimeException("fixture refresh failure")
        coEvery { updateExtensionStores.await() } returns 0
        coEvery { extensionManager.refreshAvailable() } returns Result.failure(failure)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()

            val event = awaitItem() as ExtensionsViewModel.Event.SnackbarError
            event.messageRes shouldBe R.string.ext_refresh_failed
            event.error shouldBe failure
        }
        viewModel.state.value.refreshError shouldBe failure

        viewModel.dismissRefreshError()
        viewModel.state.value.refreshError.shouldBeNull()
    }

    @Test
    fun `the banner outlives an emission from the manager, so a slow index cannot hide it`() = runTest(testDispatcher) {
        indexAlreadyLoaded()
        val failure = RuntimeException("fixture refresh failure")
        coEvery { updateExtensionStores.await() } returns 0
        coEvery { extensionManager.refreshAvailable() } returns Result.failure(failure)

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        installedFlow.value = listOf(installed(PACKAGE_NO_UPDATE, name = "Fixture"))
        advanceUntilIdle()

        viewModel.state.value.refreshError shouldBe failure
    }

    @Test
    fun `a store refetch that throws still clears refreshing and reports the throwable`() = runTest(testDispatcher) {
        indexAlreadyLoaded()
        // The regression guard for the failure that leaves the screen stuck: without the catch in
        // refresh(), a throw here kills the coroutine, isRefreshing stays true for the life of the
        // view model, the spinner never stops and the in-flight guard swallows every later refresh.
        val failure = IllegalStateException("fixture store read failure")
        coEvery { updateExtensionStores.await() } throws failure
        coEvery { extensionManager.refreshAvailable() } returns Result.success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.refresh()
            advanceUntilIdle()

            val event = awaitItem() as ExtensionsViewModel.Event.SnackbarError
            event.messageRes shouldBe R.string.ext_refresh_failed
            event.error shouldBe failure
        }
        viewModel.state.value.isRefreshing shouldBe false
        coVerify(exactly = 0) { extensionManager.refreshAvailable() }

        // The screen is usable again: a second refresh runs instead of hitting the guard.
        coEvery { updateExtensionStores.await() } returns 0
        viewModel.refresh()
        advanceUntilIdle()
        coVerify(exactly = 1) { extensionManager.refreshAvailable() }
    }

    @Test
    fun `refresh is a no op while a previous refresh is already in flight`() = runTest(testDispatcher) {
        indexAlreadyLoaded()
        coEvery { updateExtensionStores.await() } returns 0
        coEvery { extensionManager.refreshAvailable() } returns Result.success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.refresh()
            viewModel.state.value.isRefreshing shouldBe true
            viewModel.refresh()
            advanceUntilIdle()

            awaitItem()
            viewModel.state.value.isRefreshing shouldBe false
        }
        coVerify(exactly = 1) { updateExtensionStores.await() }
    }

    /**
     * The manual refresh cases start from a page whose index was already fetched, so the fetch on
     * open stays out of the counts they assert.
     */
    private fun indexAlreadyLoaded() {
        availableFlow.value = listOf(available(PACKAGE_AVAILABLE, name = "Fixture Available"))
    }

    @Test
    fun `a flag that reaches the page before the loaded list keeps the installed rows`() = runTest(testDispatcher) {
        val loading = LaggingStateFlow<List<Extension.Installed>>(emptyList())
        every { extensionManager.installedExtensions } returns loading
        initializedFlow.value = false

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.state.value.isInitialized shouldBe false

        // The end of the manager's first load, in its order: the lists, then the flag. The flag's
        // collector inside the combine resumes first; the installed list's has not yet.
        loading.publish(listOf(installed(PACKAGE_NO_UPDATE, name = "Fixture No Update")))
        initializedFlow.value = true
        advanceUntilIdle()

        viewModel.state.value.isInitialized shouldBe true
        viewModel.state.value.rows(ExtensionGroup.Installed).map { it.packageName } shouldContainExactly
            listOf(PACKAGE_NO_UPDATE)

        loading.deliver()
        advanceUntilIdle()
        viewModel.state.value.rows(ExtensionGroup.Installed).map { it.packageName } shouldContainExactly
            listOf(PACKAGE_NO_UPDATE)
    }

    private fun installed(
        packageName: String,
        name: String,
        hasUpdate: Boolean = false,
        sourceName: String = "Fixture Source",
        isNsfw: Boolean = false,
    ) = Extension.Installed(
        packageName = packageName,
        name = name,
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = isNsfw,
        sources = listOf(StubSource(id = 1L, name = sourceName, lang = "en")),
        hasUpdate = hasUpdate,
        extFile = File("fixture.ext"),
    )

    private fun available(
        packageName: String,
        name: String,
        lang: String = "en",
        isNsfw: Boolean = false,
        sourceLangs: List<String> = listOf(lang),
        versionCode: Long = 1L,
        libVersion: Double = 1.6,
    ) = Extension.Available(
        packageName = packageName,
        name = name,
        versionName = "1.0",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = lang,
        isNsfw = isNsfw,
        sources = sourceLangs.mapIndexed { index, sourceLang ->
            AvailableSource(id = 2L + index, name = "Fixture Index Source", lang = sourceLang, homeUrl = "")
        },
        apkUrl = "https://example.invalid/fixture.apk",
        iconUrl = "https://example.invalid/fixture.png",
        jarUrl = null,
        store = extensionStore(),
    )

    private fun untrusted(packageName: String, name: String) = Extension.Untrusted(
        packageName = packageName,
        name = name,
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        signatureHash = "fixture-hash",
    )

    private fun extensionStore() = ExtensionStore(
        indexUrl = "https://example.invalid/index.json",
        name = "Fixture Index",
        badgeLabel = "FI",
        signingKey = "fixture-signing-key",
        contact = ExtensionStore.Contact("https://example.invalid", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private companion object {
        const val PACKAGE_NO_UPDATE = "dev.example.fixture.noupdate"
        const val PACKAGE_HAS_UPDATE = "dev.example.fixture.hasupdate"
        const val PACKAGE_AVAILABLE = "dev.example.fixture.available"
        const val PACKAGE_AVAILABLE_JA = "dev.example.fixture.availableja"
        const val PACKAGE_UNTRUSTED = "dev.example.fixture.untrusted"
        const val PACKAGE_BROKEN = "dev.example.fixture.broken"
        const val PACKAGE_DUP = "dev.example.fixture.duplicate"
        const val PACKAGE_MISSING = "dev.example.fixture.missing"
        const val PACKAGE_A = "dev.example.fixture.a"
    }
}
