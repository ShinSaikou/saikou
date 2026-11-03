package com.shinsaikou.extensions.store

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import com.shinsaikou.extensions.repo.FetchedIndex
import com.shinsaikou.extensions.repo.RepoFetchException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class UpdateExtensionStoresTest {

    private fun store(
        indexUrl: String,
        name: String = "Fixture Index",
        signingKey: String = "a".repeat(64),
    ) = ExtensionStore(
        indexUrl = indexUrl,
        name = name,
        badgeLabel = "FI",
        signingKey = signingKey,
        contact = ExtensionStore.Contact("https://example.invalid/contact", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun fetched(store: ExtensionStore) = FetchedIndex(
        store = store,
        extensions = null,
        fetchedAtElapsedMs = 0L,
    )

    @Test
    fun `every stored row is refetched and the count of updated rows is returned`() = runTest {
        val urlA = "https://example.invalid/index-a.pb"
        val urlB = "https://example.invalid/index-b.pb"
        val rowA = store(urlA, name = "Old A")
        val rowB = store(urlB, name = "Old B")
        val newA = rowA.copy(name = "New A")
        val newB = rowB.copy(name = "New B")

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(urlA) } returns Result.success(fetched(newA))
        coEvery { repoClient.fetch(urlB) } returns Result.success(fetched(newB))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(rowA, rowB)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        val subject = UpdateExtensionStores(repoClient, stores)

        subject.await() shouldBe 2
        coVerify(exactly = 1) { stores.updateIfSigningKey(newA, "a".repeat(64)) }
        coVerify(exactly = 1) { stores.updateIfSigningKey(newB, "a".repeat(64)) }
    }

    @Test
    fun `a row whose refetched descriptor is unchanged is not written back`() = runTest {
        val url = "https://example.invalid/index-unchanged.pb"
        val row = store(url)

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(url) } returns Result.success(fetched(row))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        val subject = UpdateExtensionStores(repoClient, stores)

        subject.await() shouldBe 0
        coVerify(exactly = 0) { stores.updateIfSigningKey(any(), any()) }
    }

    @Test
    fun `a row whose refetch resolves to a different index url is left alone`() = runTest {
        // The index URL is the primary key. If a refresh followed a redirect and wrote back a
        // different URL, it would rewrite the row the owner configured instead of updating it.
        val originalUrl = "https://example.invalid/index-original.pb"
        val redirectedUrl = "https://example.invalid/index-redirected.pb"
        val row = store(originalUrl)
        val redirected = row.copy(indexUrl = redirectedUrl)

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(originalUrl) } returns Result.success(fetched(redirected))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        val subject = UpdateExtensionStores(repoClient, stores)

        subject.await() shouldBe 0
        coVerify(exactly = 0) { stores.updateIfSigningKey(any(), any()) }
    }

    @Test
    fun `a row with no name takes the name of the index its descriptor redirects to and keeps its address`() = runTest {
        // What upstream's legacy repository set restores to: an address, a legacy flag, nothing else.
        val legacyUrl = "https://example.invalid/repo/repo.json"
        val row = store(legacyUrl, name = "", signingKey = "").copy(
            badgeLabel = "",
            contact = ExtensionStore.Contact("https://example.invalid/repo", null),
            isLegacy = true,
        )
        val index = store("https://example.invalid/elsewhere/index.pb", name = "Fixture Index")

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(legacyUrl) } returns Result.success(fetched(index))
        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        UpdateExtensionStores(repoClient, stores).await() shouldBe 1

        coVerify(exactly = 1) {
            stores.updateIfSigningKey(
                row.copy(name = index.name, badgeLabel = index.badgeLabel, signingKey = index.signingKey, contact = index.contact),
                expectedSigningKey = "",
            )
        }
    }

    @Test
    fun `a row with no name but a pinned key ignores a redirect that names another key`() = runTest {
        val legacyUrl = "https://example.invalid/repo/repo.json"
        val row = store(legacyUrl, name = "")
        val index = store("https://example.invalid/elsewhere/index.pb", signingKey = "b".repeat(64))

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(legacyUrl) } returns Result.success(fetched(index))
        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)

        UpdateExtensionStores(repoClient, stores).await() shouldBe 0

        coVerify(exactly = 0) { stores.updateIfSigningKey(any(), any()) }
    }

    @Test
    fun `one host that throws does not cancel the refresh of the others`() = runTest {
        val failingUrl = "https://example.invalid/index-failing.pb"
        val okUrl = "https://example.invalid/index-ok.pb"
        val failingRow = store(failingUrl, name = "Failing")
        val okRow = store(okUrl, name = "Old Ok")
        val newOk = okRow.copy(name = "New Ok")

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(failingUrl) } throws RuntimeException("boom")
        coEvery { repoClient.fetch(okUrl) } returns Result.success(fetched(newOk))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(failingRow, okRow)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        val subject = UpdateExtensionStores(repoClient, stores)

        // supervisorScope stops the failing child from cancelling its sibling, so the sibling still
        // reaches its write back. awaitAll rethrows the first failure, so the call itself throws.
        shouldThrow<RuntimeException> { subject.await() }
        coVerify(exactly = 1) { stores.updateIfSigningKey(newOk, "a".repeat(64)) }
    }

    @Test
    fun `a row whose index cannot be read is left exactly as it was`() = runTest {
        val url = "https://example.invalid/index-unreadable.pb"
        val row = store(url)

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(url) } returns
            Result.failure(RepoFetchException(url, RepoFetchException.Kind.NETWORK))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        val subject = UpdateExtensionStores(repoClient, stores)

        subject.await() shouldBe 0
        coVerify(exactly = 0) { stores.updateIfSigningKey(any(), any()) }
    }

    @Test
    fun `a descriptor that names another signing key is not written, name included`() = runTest {
        // Trust is "signed by a configured key": writing the new key back would trust whatever the
        // index host, or the network path to it, now signs.
        val url = "https://example.invalid/index-rotated.pb"
        val row = store(url, name = "Old")
        val swapped = row.copy(name = "New", signingKey = "b".repeat(64))

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(url) } returns Result.success(fetched(swapped))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        val subject = UpdateExtensionStores(repoClient, stores)

        subject.await() shouldBe 0
        coVerify(exactly = 0) { stores.updateIfSigningKey(any(), any()) }
    }

    @Test
    fun `a row with no key yet takes the key its index names`() = runTest {
        val url = "https://example.invalid/index-unkeyed.pb"
        val row = store(url, signingKey = "")
        val keyed = row.copy(signingKey = "a".repeat(64))

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(url) } returns Result.success(fetched(keyed))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns true

        val subject = UpdateExtensionStores(repoClient, stores)

        subject.await() shouldBe 1
        coVerify(exactly = 1) { stores.updateIfSigningKey(keyed, "") }
    }

    @Test
    fun `a repository removed while its index is fetched is not written back`() = runTest {
        // The write is conditional on the row the check read; the repository reports no row written,
        // so nothing is counted and nothing is inserted in its place.
        val url = "https://example.invalid/index-removed.pb"
        val row = store(url, name = "Old")
        val renamed = row.copy(name = "New")

        val repoClient = mockk<ExtensionRepoClient>()
        coEvery { repoClient.fetch(url) } returns Result.success(fetched(renamed))

        val stores = mockk<ExtensionStoreRepository>()
        coEvery { stores.getAll() } returns listOf(row)
        coEvery { stores.updateIfSigningKey(any(), any()) } returns false

        val subject = UpdateExtensionStores(repoClient, stores)

        subject.await() shouldBe 0
        coVerify(exactly = 1) { stores.updateIfSigningKey(renamed, row.signingKey) }
        coVerify(exactly = 0) { stores.upsert(any()) }
    }
}
