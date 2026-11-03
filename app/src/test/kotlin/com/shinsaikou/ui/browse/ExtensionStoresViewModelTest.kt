package com.shinsaikou.ui.browse

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.store.AddExtensionStore
import com.shinsaikou.i18n.R
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The store row's remove (owner-delegated decision 4.15, phase 4 unit ST): a tap opens upstream's
 * confirmation naming the store, and only its confirm removes that store. A repository added here
 * has its extensions read at once, and one removed has them dropped, through the extension manager.
 * The add outlives the screen: Back pressed while it runs neither cancels it nor loses its failure.
 */
class ExtensionStoresViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stores = MutableStateFlow(listOf(store("First Index", "one"), store("Second Index", "two")))
    private val storeRepository = mockk<ExtensionStoreRepository> {
        every { getAllAsFlow() } returns stores
        coEvery { remove(any()) } just Runs
    }
    private val refreshingStores = MutableStateFlow<Set<String>>(emptySet())
    private val extensionManager = mockk<ExtensionManager> {
        every { refreshingStores } returns this@ExtensionStoresViewModelTest.refreshingStores
        coEvery { forgetStore(any()) } just Runs
        every { reportStoreFailure(any()) } just Runs
    }
    private val addExtensionStore = mockk<AddExtensionStore>()
    private val appScope = CoroutineScope(SupervisorJob() + testDispatcher)

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        appScope.cancel()
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = ExtensionStoresViewModel(
        storeRepository = storeRepository,
        addExtensionStore = addExtensionStore,
        updateExtensionStores = mockk(),
        extensionManager = extensionManager,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
        appScope = appScope,
    )

    /** A view model held by [store] as a screen's is, so clearing the store is that screen closing. */
    private fun viewModelIn(store: ViewModelStore): ExtensionStoresViewModel {
        val factory = viewModelFactory { initializer { viewModel() } }
        return ViewModelProvider.create(store, factory)[ExtensionStoresViewModel::class]
    }

    @Test
    fun `a tap on remove opens the confirmation naming the store and removes nothing`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val second = viewModel.state.value.stores[1]

        viewModel.askRemove(second)
        advanceUntilIdle()

        viewModel.state.value.removing shouldBe ExtensionStoresViewModel.StoreRow("Second Index", indexUrl("two"))
        coVerify(exactly = 0) { storeRepository.remove(any()) }
    }

    @Test
    fun `confirming removes the store the confirmation named, once, and closes it`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.askRemove(viewModel.state.value.stores[1])
        viewModel.confirmRemove()
        viewModel.confirmRemove()
        advanceUntilIdle()

        coVerify(exactly = 1) { storeRepository.remove(indexUrl("two")) }
        coVerify(exactly = 1) { storeRepository.remove(any()) }
        coVerify(exactly = 1) { extensionManager.forgetStore(indexUrl("two")) }
        viewModel.state.value.removing shouldBe null
    }

    @Test
    fun `an added repository has its extensions read, and says so on its row meanwhile`() = runTest(testDispatcher) {
        val added = store("Third Index", "three")
        coEvery { addExtensionStore.await(any()) } returns Result.success(added)
        val read = CompletableDeferred<Result<Unit>>()
        every { extensionManager.refreshStore(added.indexUrl) } answers {
            refreshingStores.value = setOf(added.indexUrl)
            read
        }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.setAddress(" ${added.indexUrl} ")
            viewModel.add()
            advanceUntilIdle()

            awaitItem() shouldBe ExtensionStoresViewModel.Event.Snackbar(R.string.ext_stores_added)
            verify(exactly = 1) { extensionManager.refreshStore(added.indexUrl) }
            viewModel.state.value.refreshing shouldBe setOf(added.indexUrl)

            refreshingStores.value = emptySet()
            read.complete(Result.success(Unit))
            advanceUntilIdle()

            viewModel.state.value.refreshing shouldBe emptySet()
            expectNoEvents()
        }
    }

    @Test
    fun `an added repository whose extensions cannot be read says why`() = runTest(testDispatcher) {
        val added = store("Third Index", "three")
        val failure = IllegalStateException("fixture index failure")
        coEvery { addExtensionStore.await(any()) } returns Result.success(added)
        every { extensionManager.refreshStore(added.indexUrl) } returns CompletableDeferred(Result.failure(failure))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.setAddress(added.indexUrl)
            viewModel.add()
            advanceUntilIdle()

            awaitItem() shouldBe ExtensionStoresViewModel.Event.Snackbar(R.string.ext_stores_added)
            awaitItem() shouldBe ExtensionStoresViewModel.Event.RefreshFailed(failure)
        }
    }

    @Test
    fun `a refused address reads no index`() = runTest(testDispatcher) {
        coEvery { addExtensionStore.await(any()) } returns Result.failure(IllegalStateException("fixture refusal"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setAddress(indexUrl("three"))
        viewModel.add()
        advanceUntilIdle()

        verify(exactly = 0) { extensionManager.refreshStore(any()) }
        // The screen is still open, so its snackbar says why, and the Extensions page stays quiet.
        verify(exactly = 0) { extensionManager.reportStoreFailure(any()) }
    }

    @Test
    fun `an add the screen is left during still completes and has its extensions read`() = runTest(testDispatcher) {
        val added = store("Third Index", "three")
        val pending = CompletableDeferred<Unit>()
        coEvery { addExtensionStore.await(any()) } coAnswers {
            pending.await()
            Result.success(added)
        }
        every { extensionManager.refreshStore(added.indexUrl) } returns CompletableDeferred(Result.success(Unit))
        val screen = ViewModelStore()
        val viewModel = viewModelIn(screen)
        advanceUntilIdle()

        viewModel.setAddress(added.indexUrl)
        viewModel.add()
        advanceUntilIdle()
        screen.clear()
        pending.complete(Unit)
        advanceUntilIdle()

        verify(exactly = 1) { extensionManager.refreshStore(added.indexUrl) }
        verify(exactly = 0) { extensionManager.reportStoreFailure(any()) }
    }

    @Test
    fun `an add that fails after its screen closed reaches the Extensions page`() = runTest(testDispatcher) {
        val failure = IllegalStateException("fixture refusal")
        val pending = CompletableDeferred<Unit>()
        coEvery { addExtensionStore.await(any()) } coAnswers {
            pending.await()
            Result.failure(failure)
        }
        val screen = ViewModelStore()
        val viewModel = viewModelIn(screen)
        advanceUntilIdle()

        viewModel.setAddress(indexUrl("three"))
        viewModel.add()
        advanceUntilIdle()
        screen.clear()
        pending.complete(Unit)
        advanceUntilIdle()

        verify(exactly = 1) { extensionManager.reportStoreFailure(failure) }
        verify(exactly = 0) { extensionManager.refreshStore(any()) }
    }

    @Test
    fun `dismissing closes the confirmation and removes nothing`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.askRemove(viewModel.state.value.stores[0])
        viewModel.dismissRemove()
        viewModel.confirmRemove()
        advanceUntilIdle()

        coVerify(exactly = 0) { storeRepository.remove(any()) }
        viewModel.state.value.removing shouldBe null
    }

    private fun indexUrl(key: String) = "https://example.invalid/$key/index.json"

    private fun store(name: String, key: String) = ExtensionStore(
        indexUrl = indexUrl(key),
        name = name,
        badgeLabel = key.uppercase(),
        signingKey = "fixture-signing-key-$key",
        contact = ExtensionStore.Contact("https://example.invalid", null),
        isLegacy = false,
        extensionListUrl = null,
    )
}
