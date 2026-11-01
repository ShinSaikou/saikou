package com.shinsaikou.extensions

import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.extensions.install.ExtensionInstaller
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadError
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import com.shinsaikou.extensions.repo.FetchedIndex
import com.shinsaikou.extensions.repo.RepoFetchException
import com.shinsaikou.extensions.repo.RepoRefreshException
import com.shinsaikou.extensions.trust.TrustStore
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import kotlin.time.Duration.Companion.hours

/**
 * The manager behaviours the extension list leans on and nothing else exercises: the install step
 * map heals when an install job is cancelled, and the pending update count reaches the preference
 * the navigation badge is drawn from only when it is known (ruling R46).
 *
 * The manager gets a scope of its own rather than the test's: [ExtensionManager.updates] is a
 * `stateIn(scope, Eagerly)` that never completes, so handing it the test scope would make `runTest`
 * wait for a child that never ends. `backgroundScope` is not the answer either, because
 * `advanceUntilIdle` deliberately leaves background work alone and nothing in the manager would
 * run at all. The scope is cancelled after each test instead.
 */
class ExtensionManagerTest {

    private val loader = mockk<ExtensionLoader>()
    private val trustStore = mockk<TrustStore>()
    private val repoClient = mockk<ExtensionRepoClient>()
    private val stores = mockk<ExtensionStoreRepository>()
    private val installer = mockk<ExtensionInstaller>()
    private val preferences = ExtensionsPreferences(InMemoryPreferenceStore())

    private var managerScope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        managerScope?.cancel()
    }

    private fun TestScope.buildManager(startMode: StartMode = StartMode.NORMAL): ExtensionManager {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        managerScope = scope
        return ExtensionManager(
            loader = loader,
            trustStore = trustStore,
            repoClient = repoClient,
            stores = stores,
            installer = installer,
            preferences = preferences,
            libVersionPolicy = LibVersionPolicy(preferences),
            startMode = startMode,
            scope = scope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    @Test
    fun `cancelling an install job drops the package's step instead of leaving it mid install`() = runTest {
        // The regression guard for a row that spins for ever: without the manager clearing an
        // incomplete step, cancelling the job leaves Downloading in the map, the browse row keeps
        // its progress indicator and nothing short of a restart takes it away.
        every { installer.downloadAndInstall(PACKAGE, APK_URL) } returns flow {
            emit(InstallStep.Pending)
            emit(InstallStep.Downloading)
            awaitCancellation()
        }
        val manager = buildManager()

        val job = manager.install(available())
        advanceUntilIdle()
        manager.installSteps.value shouldContainKey PACKAGE
        manager.installSteps.value[PACKAGE] shouldBe InstallStep.Downloading

        job.cancel()
        advanceUntilIdle()

        manager.installSteps.value shouldNotContainKey PACKAGE
    }

    @Test
    fun `a finished install keeps its step, so the row reports the outcome`() = runTest {
        every { installer.downloadAndInstall(PACKAGE, APK_URL) } returns flow {
            emit(InstallStep.Pending)
            emit(InstallStep.Error)
        }
        val manager = buildManager()

        manager.install(available())
        advanceUntilIdle()

        manager.installSteps.value[PACKAGE] shouldBe InstallStep.Error
    }

    @Test
    fun `a failed install keeps its reason until the package is tried again`() = runTest {
        // The silent failure this guards: the step alone says Error, and the row went back to its
        // button with no word on why. The refusal now stays beside the step.
        val refusal = InstallFailedException(InstallRejection.SIGNER_MISMATCH)
        every { installer.downloadAndInstall(PACKAGE, APK_URL) } returns flow {
            emit(InstallStep.Pending)
            throw refusal
        }
        val manager = buildManager()

        manager.install(available())
        advanceUntilIdle()

        manager.installSteps.value[PACKAGE] shouldBe InstallStep.Error
        manager.installFailures.value[PACKAGE] shouldBe refusal

        // A retry clears the old reason as soon as it starts.
        every { installer.downloadAndInstall(PACKAGE, APK_URL) } returns flow {
            emit(InstallStep.Pending)
            awaitCancellation()
        }
        val retry = manager.install(available())
        advanceUntilIdle()
        manager.installFailures.value shouldNotContainKey PACKAGE
        retry.cancel()
    }

    @Test
    fun `the pending update count is derived from a fetched index, not from the loader`() = runTest {
        // The loader never sets hasUpdate, so the only way a count above zero is honest is through
        // an index fetch: the index carries a newer version code for one package and the same
        // version for the other.
        coEvery { loader.loadAll() } returns listOf(
            LoadResult.Success(installed(PACKAGE)),
            LoadResult.Success(installed(OTHER_PACKAGE)),
        )
        seedIndex(available(PACKAGE, versionCode = 2L), available(OTHER_PACKAGE, versionCode = 1L))
        preferences.lastIndexRefresh.set(System.currentTimeMillis())
        val manager = buildManager()

        manager.start()
        advanceUntilIdle()
        manager.updates.value.shouldBeEmpty()

        manager.refreshAvailable().isSuccess shouldBe true
        advanceUntilIdle()

        manager.updates.value.map { it.packageName } shouldBe listOf(PACKAGE)
        preferences.extensionUpdatesCount.get() shouldBe 1
    }

    @Test
    fun `an index entry moved to an unsupported lib is not offered as an update`() = runTest {
        // The catalog bumped its lib: one package now needs lib 1.7, which this host does not load.
        // Offering it would replace a working extension with one that cannot load.
        coEvery { loader.loadAll() } returns listOf(
            LoadResult.Success(installed(PACKAGE)),
            LoadResult.Success(installed(OTHER_PACKAGE)),
        )
        seedIndex(
            available(PACKAGE, versionCode = 2L, libVersion = 1.7),
            available(OTHER_PACKAGE, versionCode = 2L),
        )
        preferences.lastIndexRefresh.set(System.currentTimeMillis())
        val manager = buildManager()
        manager.start()
        advanceUntilIdle()

        manager.refreshAvailable()
        advanceUntilIdle()

        manager.updates.value.map { it.packageName } shouldBe listOf(OTHER_PACKAGE)
        preferences.extensionUpdatesCount.get() shouldBe 1
    }

    @Test
    fun `a cold start inside the refresh window keeps the persisted count`() = runTest {
        // The defect ruling R46 fixed: the last process fetched an hour ago and counted three
        // updates. This start does not fetch, so it knows nothing about updates and must leave
        // the badge alone rather than write the zero its empty available list implies.
        preferences.extensionUpdatesCount.set(3)
        preferences.lastIndexRefresh.set(System.currentTimeMillis() - 1.hours.inWholeMilliseconds)
        coEvery { loader.loadAll() } returns listOf(
            LoadResult.Success(installed(PACKAGE)),
            LoadResult.Success(installed(OTHER_PACKAGE)),
        )
        seedIndex(available(PACKAGE, versionCode = 2L))
        val manager = buildManager()

        manager.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { repoClient.fetch(any()) }
        manager.isInitialized.value shouldBe true
        preferences.extensionUpdatesCount.get() shouldBe 3
    }

    @Test
    fun `a stale index is refreshed at start while the automatic check is on`() = runTest {
        // The positive half of the switch below: without it, "no fetch" would pass for a manager
        // that never fetches at start at all.
        preferences.extensionUpdatesCount.set(3)
        coEvery { loader.loadAll() } returns listOf(LoadResult.Success(installed(PACKAGE)))
        seedIndex(available(PACKAGE, versionCode = 2L))
        val manager = buildManager()

        manager.start()
        advanceUntilIdle()

        coVerify(exactly = 1) { repoClient.fetch(INDEX_URL) }
        preferences.extensionUpdatesCount.get() shouldBe 1
    }

    @Test
    fun `turning the automatic check off stops the refresh at start`() = runTest {
        preferences.autoCheckUpdates.set(false)
        preferences.extensionUpdatesCount.set(3)
        coEvery { loader.loadAll() } returns listOf(LoadResult.Success(installed(PACKAGE)))
        seedIndex(available(PACKAGE, versionCode = 2L))
        val manager = buildManager()

        manager.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { repoClient.fetch(any()) }
        manager.availableExtensions.value.shouldBeEmpty()
        preferences.extensionUpdatesCount.get() shouldBe 3
    }

    @Test
    fun `nothing installed writes a zero, so a stale badge from the last session clears`() = runTest {
        preferences.extensionUpdatesCount.set(4)
        coEvery { loader.loadAll() } returns emptyList()
        coEvery { stores.getAll() } returns emptyList()
        val manager = buildManager()

        manager.start()
        advanceUntilIdle()

        preferences.extensionUpdatesCount.get() shouldBe 0
    }

    @Test
    fun `a first load that throws still ends initialized and leaves the persisted count alone`() = runTest {
        // Every worker that resolves sources waits on this flag through the registry, so a load
        // that throws and leaves it false would hang them all. Nothing is known about what is
        // installed, so the badge keeps the count the last process wrote.
        preferences.extensionUpdatesCount.set(3)
        coEvery { loader.loadAll() } throws IllegalStateException("fixture load failure")
        seedIndex(available(PACKAGE, versionCode = 2L))
        val manager = buildManager()

        manager.start()
        advanceUntilIdle()

        manager.isInitialized.value shouldBe true
        manager.sources.value.shouldBeEmpty()
        coVerify(exactly = 0) { repoClient.fetch(any()) }
        preferences.extensionUpdatesCount.get() shouldBe 3
    }

    @Test
    fun `uninstalling the extension with the update lowers the count once an index is loaded`() = runTest {
        coEvery { loader.loadAll() } returns listOf(
            LoadResult.Success(installed(PACKAGE)),
            LoadResult.Success(installed(OTHER_PACKAGE)),
        )
        coEvery { loader.uninstallPrivateExtension(PACKAGE) } just Runs
        seedIndex(available(PACKAGE, versionCode = 2L), available(OTHER_PACKAGE, versionCode = 2L))
        preferences.lastIndexRefresh.set(System.currentTimeMillis())
        val manager = buildManager()
        manager.start()
        advanceUntilIdle()
        manager.refreshAvailable()
        preferences.extensionUpdatesCount.get() shouldBe 2

        manager.uninstall(PACKAGE)

        preferences.extensionUpdatesCount.get() shouldBe 1
    }

    @Test
    fun `reloading an updated package recounts once an index is loaded`() = runTest {
        coEvery { loader.loadAll() } returns listOf(LoadResult.Success(installed(PACKAGE)))
        coEvery { loader.loadPackage(PACKAGE) } returns LoadResult.Success(installed(PACKAGE, versionCode = 2L))
        seedIndex(available(PACKAGE, versionCode = 2L))
        preferences.lastIndexRefresh.set(System.currentTimeMillis())
        val manager = buildManager()
        manager.start()
        advanceUntilIdle()
        manager.refreshAvailable()
        preferences.extensionUpdatesCount.get() shouldBe 1

        manager.reload(PACKAGE)

        preferences.extensionUpdatesCount.get() shouldBe 0
    }

    @Test
    fun `an uninstall before any index was read leaves the count alone`() = runTest {
        preferences.extensionUpdatesCount.set(2)
        preferences.lastIndexRefresh.set(System.currentTimeMillis())
        coEvery { loader.loadAll() } returns listOf(
            LoadResult.Success(installed(PACKAGE)),
            LoadResult.Success(installed(OTHER_PACKAGE)),
        )
        coEvery { loader.uninstallPrivateExtension(PACKAGE) } just Runs
        seedIndex()
        val manager = buildManager()
        manager.start()
        advanceUntilIdle()

        manager.uninstall(PACKAGE)

        preferences.extensionUpdatesCount.get() shouldBe 2
    }

    @Test
    fun `a repository that fails keeps its last entries listed and is named in the failure`() = runTest {
        // One repository down used to take its extensions out of the list with only a log line.
        val first = store()
        val second = store(indexUrl = OTHER_INDEX_URL, name = "Other Fixture Index")
        val firstIndex = FetchedIndex(store = first, extensions = emptyList(), fetchedAtElapsedMs = 0L)
        val secondIndex = FetchedIndex(store = second, extensions = emptyList(), fetchedAtElapsedMs = 0L)
        coEvery { stores.getAll() } returns listOf(first, second)
        coEvery { repoClient.fetch(INDEX_URL) } returns Result.success(firstIndex)
        coEvery { repoClient.fetch(OTHER_INDEX_URL) } returns Result.success(secondIndex)
        coEvery { repoClient.extensions(firstIndex) } returns Result.success(listOf(available(PACKAGE)))
        coEvery { repoClient.extensions(secondIndex) } returns Result.success(listOf(available(OTHER_PACKAGE)))
        val manager = buildManager()
        manager.refreshAvailable().isSuccess shouldBe true

        val down = RepoFetchException(OTHER_INDEX_URL, RepoFetchException.Kind.NETWORK)
        coEvery { repoClient.fetch(OTHER_INDEX_URL) } returns Result.failure(down)
        val result = manager.refreshAvailable()

        manager.availableExtensions.value.map { it.packageName }.sorted() shouldBe listOf(PACKAGE, OTHER_PACKAGE)
        val failure = result.exceptionOrNull().shouldBeInstanceOf<RepoRefreshException>()
        failure.failures shouldBe listOf(RepoRefreshException.Failure(second, down))
    }

    @Test
    fun `a repository removed from the configuration takes its kept entries with it`() = runTest {
        val first = store()
        val second = store(indexUrl = OTHER_INDEX_URL, name = "Other Fixture Index")
        val firstIndex = FetchedIndex(store = first, extensions = emptyList(), fetchedAtElapsedMs = 0L)
        val secondIndex = FetchedIndex(store = second, extensions = emptyList(), fetchedAtElapsedMs = 0L)
        coEvery { stores.getAll() } returns listOf(first, second)
        coEvery { repoClient.fetch(INDEX_URL) } returns Result.success(firstIndex)
        coEvery { repoClient.fetch(OTHER_INDEX_URL) } returns Result.success(secondIndex)
        coEvery { repoClient.extensions(firstIndex) } returns Result.success(listOf(available(PACKAGE)))
        coEvery { repoClient.extensions(secondIndex) } returns Result.success(listOf(available(OTHER_PACKAGE)))
        val manager = buildManager()
        manager.refreshAvailable()

        coEvery { stores.getAll() } returns listOf(first)
        manager.refreshAvailable().isSuccess shouldBe true

        manager.availableExtensions.value.map { it.packageName } shouldBe listOf(PACKAGE)
    }

    @Test
    fun `an extension that did not load is offered its update and counted`() = runTest {
        // Upstream's 2d86a70ce: a broken or untrusted copy is often fixed by its update, and the
        // badge used to ignore it.
        coEvery { loader.loadAll() } returns listOf(
            LoadResult.Error(PACKAGE, LoadError.CLASS_LOAD_FAILED, versionCode = 1L, libVersion = 1.6),
            LoadResult.Untrusted(untrusted(OTHER_PACKAGE)),
        )
        seedIndex(available(PACKAGE, versionCode = 2L), available(OTHER_PACKAGE, versionCode = 2L))
        preferences.lastIndexRefresh.set(System.currentTimeMillis())
        val manager = buildManager()
        manager.start()
        advanceUntilIdle()

        manager.refreshAvailable()
        advanceUntilIdle()

        manager.notLoadedUpdates.value shouldBe setOf(PACKAGE, OTHER_PACKAGE)
        manager.updates.value.shouldBeEmpty()
        preferences.extensionUpdatesCount.get() shouldBe 2
    }

    @Test
    fun `a file that could not be read as a package is removed by its path`() = runTest {
        val file = File("unreadable.ext")
        val unreadable = LoadResult.Error(null, LoadError.NOT_AN_EXTENSION, file = file)
        coEvery { loader.loadAll() } returns listOf(unreadable)
        coEvery { loader.deleteUnreadable(file) } just Runs
        coEvery { stores.getAll() } returns emptyList()
        val manager = buildManager()
        manager.start()
        advanceUntilIdle()
        manager.loadErrors.value shouldBe listOf(unreadable)

        manager.uninstall(unreadable)

        coVerify(exactly = 1) { loader.deleteUnreadable(file) }
        manager.loadErrors.value.shouldBeEmpty()
    }

    /**
     * The crash screen's way out of an extension that takes the app down at start: nothing is
     * loaded, every package is still listed so it can be removed, the flag still turns so nothing
     * waiting on the registry hangs, and no index is fetched on the way.
     */
    @Test
    fun `a start without extensions lists every package unloaded and still ends initialized`() = runTest {
        val off = LoadResult.Error(PACKAGE, LoadError.EXTENSIONS_OFF, versionCode = 1L, libVersion = 1.6)
        coEvery { loader.readAll() } returns listOf(off)
        preferences.extensionUpdatesCount.set(3)
        seedIndex(available(PACKAGE, versionCode = 2L))
        val manager = buildManager(StartMode(withoutExtensions = true))

        manager.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { loader.loadAll() }
        manager.isInitialized.value shouldBe true
        manager.sources.value.shouldBeEmpty()
        manager.installedExtensions.value.shouldBeEmpty()
        manager.loadErrors.value shouldBe listOf(off)
        coVerify(exactly = 0) { repoClient.fetch(any()) }
        preferences.extensionUpdatesCount.get() shouldBe 3
    }

    @Test
    fun `without extensions an installed or updated package is read, not loaded`() = runTest {
        val off = LoadResult.Error(PACKAGE, LoadError.EXTENSIONS_OFF, versionCode = 2L, libVersion = 1.6)
        coEvery { loader.readAll() } returns emptyList()
        coEvery { loader.readPackage(PACKAGE) } returns off
        coEvery { stores.getAll() } returns emptyList()
        val manager = buildManager(StartMode(withoutExtensions = true))
        manager.start()
        advanceUntilIdle()

        manager.reload(PACKAGE) shouldBe off

        coVerify(exactly = 0) { loader.loadPackage(any()) }
        manager.loadErrors.value shouldBe listOf(off)
        manager.sources.value.shouldBeEmpty()
    }

    /** One configured store whose index lists [entries]. */
    private fun seedIndex(vararg entries: Extension.Available) {
        val fetched = FetchedIndex(store = store(), extensions = emptyList(), fetchedAtElapsedMs = 0L)
        coEvery { stores.getAll() } returns listOf(store())
        coEvery { repoClient.fetch(INDEX_URL) } returns Result.success(fetched)
        coEvery { repoClient.extensions(fetched) } returns Result.success(entries.toList())
    }

    private fun installed(packageName: String, versionCode: Long = 1L) = Extension.Installed(
        packageName = packageName,
        name = "Fixture $packageName",
        versionName = "1.0",
        versionCode = versionCode,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        extFile = File("fixture.ext"),
    )

    private fun untrusted(packageName: String) = Extension.Untrusted(
        packageName = packageName,
        name = "Fixture $packageName",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "",
        isNsfw = false,
        signatureHash = "fixture-hash",
    )

    private fun available(
        packageName: String = PACKAGE,
        versionCode: Long = 1L,
        libVersion: Double = 1.6,
    ) = Extension.Available(
        packageName = packageName,
        name = "Fixture Available",
        versionName = "1.0",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkUrl = APK_URL,
        iconUrl = "https://example.invalid/fixture.png",
        jarUrl = null,
        store = store(),
    )

    private fun store(indexUrl: String = INDEX_URL, name: String = "Fixture Index") = ExtensionStore(
        indexUrl = indexUrl,
        name = name,
        badgeLabel = "FI",
        signingKey = "fixture-signing-key",
        contact = ExtensionStore.Contact("https://example.invalid", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private companion object {
        const val PACKAGE = "dev.example.fixture.one"
        const val OTHER_PACKAGE = "dev.example.fixture.two"
        const val APK_URL = "https://example.invalid/fixture.apk"
        const val INDEX_URL = "https://example.invalid/index.json"
        const val OTHER_INDEX_URL = "https://example.invalid/other/index.json"
    }
}
