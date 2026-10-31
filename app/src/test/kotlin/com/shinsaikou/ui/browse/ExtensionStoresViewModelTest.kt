package com.shinsaikou.ui.browse

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
 * confirmation naming the store, and only its confirm removes that store.
 */
class ExtensionStoresViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val stores = MutableStateFlow(listOf(store("First Index", "one"), store("Second Index", "two")))
    private val storeRepository = mockk<ExtensionStoreRepository> {
        every { getAllAsFlow() } returns stores
        coEvery { remove(any()) } just Runs
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = ExtensionStoresViewModel(
        storeRepository = storeRepository,
        addExtensionStore = mockk(),
        updateExtensionStores = mockk(),
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

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
        viewModel.state.value.removing shouldBe null
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
