package com.shinsaikou.ui.browse.detail

import app.cash.turbine.test
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.ConfigurableSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.ui.fixture.LaggingStateFlow
import eu.kanade.tachiyomi.extension.model.InstallStep
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
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.io.File
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The bug this file exists to prevent is the inverted switch. `SourcePreferences.disabledSources`
 * holds the ids that are switched OFF, while every row on the screen shows whether a source is ON,
 * so the assertions below check the SET that lands in the preference rather than the flag that went
 * in: a straight-through write reads correctly on screen and does the opposite to the app.
 *
 * The second thing pinned here is that the screen is driven by the manager's flows. An uninstall
 * that happens anywhere has to reach this state as `NotFound` plus one `Back`, not as a stale
 * extension the owner can keep tapping.
 */
class ExtensionDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val appDispatchers = AppDispatchers(
        io = testDispatcher,
        default = testDispatcher,
        main = testDispatcher,
    )

    private val initializedFlow = MutableStateFlow(true)
    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val availableFlow = MutableStateFlow<List<Extension.Available>>(emptyList())
    private val installStepsFlow = MutableStateFlow<Map<String, InstallStep>>(emptyMap())

    private val extensionManager = mockk<ExtensionManager> {
        every { isInitialized } returns initializedFlow
        every { installedExtensions } returns installedFlow
        every { availableExtensions } returns availableFlow
        every { installSteps } returns installStepsFlow
    }
    private val sourceManager = mockk<SourceManager>()
    private val sourcePreferences = SourcePreferences(InMemoryPreferenceStore())

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        // Nothing is registered in the source registry under test, so every row falls back to the
        // adapter the extension itself published.
        every { sourceManager.get(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    @Test
    fun `the state stays Loading until the manager finishes its first pass`() = runTest(testDispatcher) {
        initializedFlow.value = false

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value shouldBe ExtensionDetailViewModel.State.Loading

        installedFlow.value = listOf(installed(sources = listOf(source(1L, "Beta"), source(2L, "alpha"))))
        initializedFlow.value = true
        advanceUntilIdle()

        val state = viewModel.state.value as ExtensionDetailViewModel.State.Success
        state.ext.packageName shouldBe PACKAGE
        // Sorted by name, case insensitively, so the panel does not reorder on a locale change.
        state.sources.map { it.name } shouldContainExactly listOf("alpha", "Beta")
        state.sources.map { it.enabled } shouldContainExactly listOf(true, true)
    }

    @Test
    fun `an empty installed list before initialisation is not mistaken for an uninstall`() = runTest(testDispatcher) {
        initializedFlow.value = false

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value shouldBe ExtensionDetailViewModel.State.Loading
    }

    @Test
    fun `the package leaving the installed list becomes NotFound and asks the screen to pop`() =
        runTest(testDispatcher) {
            installedFlow.value = listOf(installed())

            val viewModel = viewModel()
            advanceUntilIdle()
            (viewModel.state.value as ExtensionDetailViewModel.State.Success).ext.packageName shouldBe PACKAGE

            viewModel.events.test {
                installedFlow.value = emptyList()
                advanceUntilIdle()

                awaitItem() shouldBe ExtensionDetailViewModel.Event.Back
            }
            viewModel.state.value shouldBe ExtensionDetailViewModel.State.NotFound
        }

    @Test
    fun `a flag that reaches the screen before the loaded list is not read as an uninstall`() =
        runTest(testDispatcher) {
            val loading = LaggingStateFlow<List<Extension.Installed>>(emptyList())
            every { extensionManager.installedExtensions } returns loading
            initializedFlow.value = false

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value shouldBe ExtensionDetailViewModel.State.Loading

            viewModel.events.test {
                // The end of the manager's first load, in its order: the list is published, then the
                // flag is set. On a thread pool the flag's collector can resume before the list's,
                // which is the moment frozen here.
                loading.publish(listOf(installed()))
                initializedFlow.value = true
                advanceUntilIdle()

                (viewModel.state.value as ExtensionDetailViewModel.State.Success).ext.packageName shouldBe PACKAGE
                expectNoEvents()

                loading.deliver()
                advanceUntilIdle()

                (viewModel.state.value as ExtensionDetailViewModel.State.Success).ext.packageName shouldBe PACKAGE
                expectNoEvents()
            }
        }

    @Test
    fun `switching a source off writes its id into the disabled set`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed(sources = listOf(source(SOURCE_ID))))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleSource(SOURCE_ID, enabled = false)
        advanceUntilIdle()

        sourcePreferences.disabledSources.get() shouldBe setOf(SOURCE_ID.toString())
        val state = viewModel.state.value as ExtensionDetailViewModel.State.Success
        state.sources.single().enabled shouldBe false
    }

    @Test
    fun `switching a source on removes its id and leaves every other id alone`() = runTest(testDispatcher) {
        sourcePreferences.disabledSources.set(setOf(SOURCE_ID.toString(), FOREIGN_SOURCE_ID))
        installedFlow.value = listOf(installed(sources = listOf(source(SOURCE_ID))))

        val viewModel = viewModel()
        advanceUntilIdle()
        (viewModel.state.value as ExtensionDetailViewModel.State.Success).sources.single().enabled shouldBe false

        viewModel.toggleSource(SOURCE_ID, enabled = true)
        advanceUntilIdle()

        // The inversion guard: writing the flag straight through would have added the id again.
        sourcePreferences.disabledSources.get() shouldBe setOf(FOREIGN_SOURCE_ID)
        (viewModel.state.value as ExtensionDetailViewModel.State.Success).sources.single().enabled shouldBe true
    }

    @Test
    fun `disableAll adds every source of this extension and touches nothing else`() = runTest(testDispatcher) {
        sourcePreferences.disabledSources.set(setOf(FOREIGN_SOURCE_ID))
        installedFlow.value = listOf(installed(sources = listOf(source(1L), source(2L))))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.disableAll()
        advanceUntilIdle()

        sourcePreferences.disabledSources.get() shouldBe setOf(FOREIGN_SOURCE_ID, "1", "2")
        val state = viewModel.state.value as ExtensionDetailViewModel.State.Success
        state.sources.map { it.enabled } shouldContainExactly listOf(false, false)
    }

    @Test
    fun `enableAll removes every source of this extension and touches nothing else`() = runTest(testDispatcher) {
        sourcePreferences.disabledSources.set(setOf("1", "2", FOREIGN_SOURCE_ID))
        installedFlow.value = listOf(installed(sources = listOf(source(1L), source(2L))))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.enableAll()
        advanceUntilIdle()

        sourcePreferences.disabledSources.get() shouldBe setOf(FOREIGN_SOURCE_ID)
        val state = viewModel.state.value as ExtensionDetailViewModel.State.Success
        state.sources.map { it.enabled } shouldContainExactly listOf(true, true)
    }

    @Test
    fun `incognito is stored by package name and shows on every row`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed(sources = listOf(source(SOURCE_ID))))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleIncognito(enabled = true)
        advanceUntilIdle()

        // Keyed by the package, not by a source id: the whole extension goes quiet at once.
        sourcePreferences.incognitoExtensions.get() shouldBe setOf(PACKAGE)
        val state = viewModel.state.value as ExtensionDetailViewModel.State.Success
        state.incognito shouldBe true
        state.sources.map { it.incognito } shouldContainExactly listOf(true)

        viewModel.toggleIncognito(enabled = false)
        advanceUntilIdle()

        sourcePreferences.incognitoExtensions.get() shouldBe emptySet()
        (viewModel.state.value as ExtensionDetailViewModel.State.Success).incognito shouldBe false
    }

    @Test
    fun `an update reports its install step and only its own package's step`() = runTest(testDispatcher) {
        val extension = installed(hasUpdate = true)
        installedFlow.value = listOf(extension)
        availableFlow.value = listOf(available())
        every { extensionManager.update(extension) } returns Job()

        val viewModel = viewModel()
        advanceUntilIdle()
        (viewModel.state.value as ExtensionDetailViewModel.State.Success).canUpdate shouldBe true

        viewModel.update()
        installStepsFlow.value = mapOf(PACKAGE to InstallStep.Downloading)
        advanceUntilIdle()

        verify(exactly = 1) { extensionManager.update(extension) }
        val downloading = viewModel.state.value as ExtensionDetailViewModel.State.Success
        downloading.installStep shouldBe InstallStep.Downloading
        downloading.busy shouldBe true

        installStepsFlow.value = mapOf(PACKAGE to InstallStep.Installed)
        advanceUntilIdle()
        (viewModel.state.value as ExtensionDetailViewModel.State.Success).busy shouldBe false

        // Another package installing in the background must not spin this screen's buttons.
        installStepsFlow.value = mapOf(OTHER_PACKAGE to InstallStep.Downloading)
        advanceUntilIdle()
        val idle = viewModel.state.value as ExtensionDetailViewModel.State.Success
        idle.installStep.shouldBeNull()
        idle.busy shouldBe false
    }

    @Test
    fun `update with no matching index entry offers no update`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed(hasUpdate = true))

        val viewModel = viewModel()
        advanceUntilIdle()

        (viewModel.state.value as ExtensionDetailViewModel.State.Success).canUpdate shouldBe false
    }

    @Test
    fun `uninstall runs only after the dialog is confirmed`() = runTest(testDispatcher) {
        coEvery { extensionManager.uninstall(PACKAGE) } just Runs
        installedFlow.value = listOf(installed())

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.requestUninstall()
        (viewModel.state.value as ExtensionDetailViewModel.State.Success).dialog shouldBe
            ExtensionDetailViewModel.Dialog.Uninstall

        viewModel.dismissDialog()
        advanceUntilIdle()
        coVerify(exactly = 0) { extensionManager.uninstall(any<String>()) }

        viewModel.requestUninstall()
        viewModel.uninstall()
        advanceUntilIdle()

        coVerify(exactly = 1) { extensionManager.uninstall(PACKAGE) }
        (viewModel.state.value as ExtensionDetailViewModel.State.Success).dialog.shouldBeNull()
    }

    @Test
    fun `an install step arriving mid confirm leaves the dialog open`() = runTest(testDispatcher) {
        installedFlow.value = listOf(installed())

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.requestUninstall()
        installStepsFlow.value = mapOf(PACKAGE to InstallStep.Downloading)
        advanceUntilIdle()

        (viewModel.state.value as ExtensionDetailViewModel.State.Success).dialog shouldBe
            ExtensionDetailViewModel.Dialog.Uninstall
    }

    @Test
    fun `a configurable source with a home address carries both row actions`() = runTest(testDispatcher) {
        val configurable = mockk<ConfigurableSource> {
            every { id } returns SOURCE_ID
            every { name } returns "Configurable"
            every { lang } returns "en"
            every { homeUrl } returns HOME_URL
        }
        installedFlow.value = listOf(installed(sources = listOf(configurable, source(2L, "Plain"))))

        val viewModel = viewModel()
        advanceUntilIdle()

        val rows = (viewModel.state.value as ExtensionDetailViewModel.State.Success).sources
        rows.map { it.configurable } shouldContainExactly listOf(true, false)
        rows.map { it.homeUrl } shouldContainExactly listOf(HOME_URL, null)

        viewModel.events.test {
            viewModel.openSourceSettings(SOURCE_ID)
            advanceUntilIdle()
            awaitItem() shouldBe ExtensionDetailViewModel.Event.OpenSourceSettings(SOURCE_ID)

            viewModel.openWebView(rows.first())
            advanceUntilIdle()
            awaitItem() shouldBe ExtensionDetailViewModel.Event.OpenWebView(
                url = HOME_URL,
                sourceId = SOURCE_ID,
                title = "Configurable",
            )

            // A source with no page produces no event at all rather than an empty address.
            viewModel.openWebView(rows.last())
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    private fun viewModel() = ExtensionDetailViewModel(
        packageName = PACKAGE,
        extensionManager = extensionManager,
        sourcePreferences = sourcePreferences,
        sourceManager = sourceManager,
        dispatchers = appDispatchers,
    )

    private fun source(id: Long, name: String = "Fixture Source $id"): Source = StubSource(id, name, "en")

    private fun installed(
        hasUpdate: Boolean = false,
        sources: List<Source> = emptyList(),
    ) = Extension.Installed(
        packageName = PACKAGE,
        name = "Fixture Extension",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = sources,
        hasUpdate = hasUpdate,
        store = ExtensionStore(
            indexUrl = "https://example.invalid/index.json",
            name = "Fixture Index",
            badgeLabel = "FI",
            signingKey = "0011223344556677",
            contact = ExtensionStore.Contact("https://example.invalid", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
        extFile = File("fixture.ext"),
    )

    private fun available() = Extension.Available(
        packageName = PACKAGE,
        name = "Fixture Extension",
        versionName = "2.0",
        versionCode = 2L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkUrl = "https://example.invalid/fixture.apk",
        iconUrl = "https://example.invalid/fixture.png",
        jarUrl = null,
        store = ExtensionStore(
            indexUrl = "https://example.invalid/index.json",
            name = "Fixture Index",
            badgeLabel = "FI",
            signingKey = "0011223344556677",
            contact = ExtensionStore.Contact("https://example.invalid", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )

    private companion object {
        const val PACKAGE = "dev.example.fixture.detail"
        const val OTHER_PACKAGE = "dev.example.fixture.other"
        const val SOURCE_ID = 11L
        const val FOREIGN_SOURCE_ID = "9999"
        const val HOME_URL = "https://example.invalid/home"
    }
}
