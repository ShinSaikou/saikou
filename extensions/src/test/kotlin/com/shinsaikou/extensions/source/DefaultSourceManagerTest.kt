package com.shinsaikou.extensions.source

import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceProvider
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.StubSourceNames
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

/**
 * The registry is ready only when every provider has finished its first load (phase 2 plan,
 * section 1.6). Before that, a registry holding the Local source alone reads as ready to a worker
 * that starts the process, and every extension entry resolves to a stub.
 *
 * The manager and the extension host get a scope of their own, cancelled after each test, for the
 * reason `ExtensionManagerTest` gives: both hold collectors that never complete.
 */
class DefaultSourceManagerTest {

    private var managerScope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        managerScope?.cancel()
    }

    @Test
    fun `the registry is not ready until a provider that starts empty has finished its first load`() = runTest {
        val extensions = FakeProvider()
        val manager = buildManager(extensions, localProvider())
        advanceUntilIdle()

        // The defect on main: the Local source's list and the host's empty list made it ready here.
        manager.isInitialized.value shouldBe false
        manager.get(LOCAL_ID).shouldBeInstanceOf<FakeSource>()

        // The list alone is not the end of the load.
        extensions.sources.value = listOf(FakeSource(EXTENSION_ID))
        advanceUntilIdle()
        manager.isInitialized.value shouldBe false

        extensions.isInitialized.value = true
        advanceUntilIdle()
        manager.isInitialized.value shouldBe true
    }

    @Test
    fun `the moment the registry is ready it answers with the provider's source`() = runTest {
        val extensions = FakeProvider()
        val source = FakeSource(EXTENSION_ID)
        val manager = buildManager(extensions, localProvider())
        advanceUntilIdle()

        // Unconfined, so the observer runs inside the assignment that sets the flag, before the
        // collector's next statement: a map published after the flag would be seen missing here.
        var seen: Source? = null
        var seenStub: Source? = null
        launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.isInitialized.first { it }
            seen = manager.get(EXTENSION_ID)
            seenStub = manager.getOrStub(EXTENSION_ID)
        }

        extensions.sources.value = listOf(source)
        extensions.isInitialized.value = true
        advanceUntilIdle()

        seen shouldBeSameInstanceAs source
        seenStub shouldBeSameInstanceAs source
    }

    @Test
    fun `an id no provider serves is a stub once the registry is ready`() = runTest {
        val manager = buildManager(FakeProvider(initialized = true), localProvider())
        advanceUntilIdle()

        manager.isInitialized.value shouldBe true
        manager.get(EXTENSION_ID).shouldBeNull()
        manager.getOrStub(EXTENSION_ID).shouldBeInstanceOf<StubSource>()
    }

    @Test
    fun `the extension host is ready after its first load, with the loaded sources in the registry`() = runTest {
        val source = FakeSource(EXTENSION_ID)
        val loader = mockk<ExtensionLoader>()
        coEvery { loader.loadAll() } returns listOf(LoadResult.Success(installed(listOf(source))))
        val host = buildHost(loader)
        val manager = buildManager(host, localProvider())
        advanceUntilIdle()
        manager.isInitialized.value shouldBe false

        host.start()
        advanceUntilIdle()

        host.isInitialized.value shouldBe true
        manager.isInitialized.value shouldBe true
        manager.get(EXTENSION_ID) shouldBeSameInstanceAs source
    }

    @Test
    fun `an extension host whose first load throws still ends initialized, with the Local source`() = runTest {
        val loader = mockk<ExtensionLoader>()
        coEvery { loader.loadAll() } throws IllegalStateException("fixture load failure")
        val host = buildHost(loader)
        val manager = buildManager(host, localProvider())

        host.start()
        advanceUntilIdle()

        host.isInitialized.value shouldBe true
        host.sources.value shouldBe emptyList()
        manager.isInitialized.value shouldBe true
        manager.get(LOCAL_ID).shouldBeInstanceOf<FakeSource>()
    }

    private fun TestScope.buildManager(vararg providers: SourceProvider): DefaultSourceManager {
        val scope = scope()
        return DefaultSourceManager(
            providers = providers.toSet(),
            stubNames = NoStubNames,
            scope = scope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun TestScope.buildHost(loader: ExtensionLoader): ExtensionManager {
        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns emptyList()
        return ExtensionManager(
            loader = loader,
            trustStore = mockk(),
            repoClient = mockk(),
            stores = stores,
            installer = mockk(),
            preferences = ExtensionsPreferences(InMemoryPreferenceStore()),
            libVersionPolicy = mockk(),
            startMode = StartMode.NORMAL,
            scope = scope(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    /** One scope for everything a test builds, so the teardown cancels all of it. */
    private fun TestScope.scope(): CoroutineScope =
        managerScope ?: CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { managerScope = it }

    /** The Local source's provider: one source, ready from the start. */
    private fun localProvider() = FakeProvider(listOf(FakeSource(LOCAL_ID)), initialized = true)

    private class FakeProvider(
        sources: List<Source> = emptyList(),
        initialized: Boolean = false,
    ) : SourceProvider {
        override val sources = MutableStateFlow(sources)
        override val isInitialized = MutableStateFlow(initialized)
    }

    private class FakeSource(override val id: Long) : Source {
        override val name = "Fixture Source $id"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = manga
        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = emptyList()
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = emptyList()
        override suspend fun getImage(page: PageInfo): ImageResult = error("Unused by the registry")
    }

    private object NoStubNames : StubSourceNames {
        override suspend fun find(id: Long): StubSourceNames.Entry? = null
        override suspend fun remember(id: Long, name: String, lang: String) = Unit
    }

    private fun installed(sources: List<Source>) = Extension.Installed(
        packageName = "dev.example.fixture.one",
        name = "Fixture Extension",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = sources,
        extFile = File("fixture.ext"),
    )

    private companion object {
        const val LOCAL_ID = 0L
        const val EXTENSION_ID = 42L
    }
}
