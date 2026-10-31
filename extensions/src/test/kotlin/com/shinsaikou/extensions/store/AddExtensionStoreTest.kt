package com.shinsaikou.extensions.store

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import com.shinsaikou.extensions.repo.FetchedIndex
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.extension.model.ExtensionStore

/** Each refusal has a type of its own, so the repository screen can word it in the reader's language. */
class AddExtensionStoreTest {

    private val store = ExtensionStore(
        indexUrl = "https://example.invalid/index.pb",
        name = "Fixture Index",
        badgeLabel = "FI",
        signingKey = "a".repeat(64),
        contact = ExtensionStore.Contact("https://example.invalid/contact", null),
        isLegacy = false,
        extensionListUrl = null,
    )
    private val repoClient = mockk<ExtensionRepoClient>()
    private val stores = mockk<ExtensionStoreRepository>()
    private val subject = AddExtensionStore(repoClient, stores)

    @Test
    fun `an address that is not http or https is refused before any fetch`() = runTest {
        subject.await("ftp://example.invalid/index.pb").exceptionOrNull()
            .shouldBeInstanceOf<InvalidStoreAddressException>()
        coVerify(exactly = 0) { repoClient.fetch(any()) }
    }

    @Test
    fun `an index already configured is refused as a duplicate`() = runTest {
        coEvery { repoClient.fetch(store.indexUrl) } returns
            Result.success(FetchedIndex(store = store, extensions = null, fetchedAtElapsedMs = 0L))
        coEvery { stores.get(store.indexUrl) } returns store

        subject.await(store.indexUrl).exceptionOrNull().shouldBeInstanceOf<StoreAlreadyConfiguredException>()
        coVerify(exactly = 0) { stores.upsert(any()) }
    }
}
