package com.shinsaikou.data.repository

import app.cash.turbine.test
import com.shinsaikou.data.DatabaseInitializer
import com.shinsaikou.data.extension.ExtensionStoreRepositoryImpl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

/**
 * Every fixture value is a placeholder: no index URL, name, badge or key here names anything real
 * (`CONTRIBUTING.md`). The signing key is sixty-four zeros, as the `.sq` file's comment requires.
 */
class ExtensionStoreRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { ExtensionStoreRepositoryImpl(handler) }

    @Test
    fun `upsert round-trips every column including the nested contact`() = runTest {
        repository.upsert(store())

        repository.get(INDEX_URL) shouldBe store()
    }

    @Test
    fun `upsert on an existing index url replaces the other columns`() = runTest {
        repository.upsert(store())
        repository.upsert(store(name = "Second", isLegacy = true, discord = null))

        repository.getAll() shouldContainExactly listOf(
            store(name = "Second", isLegacy = true, discord = null),
        )
    }

    @Test
    fun `get returns null for an index url that was never stored`() = runTest {
        repository.get(INDEX_URL) shouldBe null
    }

    @Test
    fun `the flows re-emit when a row is added and removed`() = runTest {
        repository.getCountAsFlow().test {
            awaitItem() shouldBe 0L

            repository.upsert(store())
            awaitItem() shouldBe 1L

            repository.remove(INDEX_URL)
            awaitItem() shouldBe 0L
        }
    }

    @Test
    fun `getAllAsFlow emits the stored rows`() = runTest {
        repository.getAllAsFlow().test {
            awaitItem() shouldBe emptyList()

            repository.upsert(store())
            awaitItem() shouldContainExactly listOf(store())
        }
    }

    @Test
    fun `updateIfSigningKey writes over a row that still holds the expected key`() = runTest {
        repository.upsert(store())

        repository.updateIfSigningKey(store(name = "Renamed"), expectedSigningKey = "0".repeat(64)) shouldBe true

        repository.get(INDEX_URL) shouldBe store(name = "Renamed")
    }

    @Test
    fun `updateIfSigningKey never brings back a row that was removed`() = runTest {
        repository.upsert(store())
        repository.remove(INDEX_URL)

        repository.updateIfSigningKey(store(name = "Renamed"), expectedSigningKey = "0".repeat(64)) shouldBe false

        repository.get(INDEX_URL) shouldBe null
    }

    @Test
    fun `updateIfSigningKey leaves a row whose key is no longer the one checked`() = runTest {
        repository.upsert(store(signingKey = "1".repeat(64)))

        repository.updateIfSigningKey(store(name = "Renamed"), expectedSigningKey = "0".repeat(64)) shouldBe false

        repository.get(INDEX_URL) shouldBe store(signingKey = "1".repeat(64))
    }

    @Test
    fun `mergeDuplicates folds a repository listed under two addresses into its named row`() = runTest {
        // What a restore of a backup with upstream's legacy set beside its store list left behind.
        val base = "https://example.invalid/repo"
        val legacy = ExtensionStore(
            indexUrl = "$base/repo.json",
            name = "",
            badgeLabel = "",
            signingKey = "",
            contact = ExtensionStore.Contact(website = base, discord = null),
            isLegacy = true,
            extensionListUrl = null,
        )
        val named = store().copy(indexUrl = "$base/index.pb")
        val other = store().copy(indexUrl = "https://example.invalid/other/index.pb")
        listOf(legacy, named, other).forEach { repository.upsert(it) }

        repository.mergeDuplicates() shouldBe 1

        repository.getAll() shouldContainExactlyInAnyOrder listOf(named, other)
        repository.mergeDuplicates() shouldBe 0
    }

    @Test
    fun `the start-up sweep repairs the duplicates an earlier restore left`() = runTest {
        val base = "https://example.invalid/repo"
        val unnamed = store(name = "").copy(indexUrl = "$base/repo.json", badgeLabel = "", isLegacy = true)
        val named = store().copy(indexUrl = "$base/index.pb", extensionListUrl = null)
        repository.upsert(unnamed)
        repository.upsert(named)

        DatabaseInitializer(handler, driver).initialize()

        repository.getAll() shouldContainExactly listOf(named)
    }

    private fun store(
        name: String = "First",
        isLegacy: Boolean = false,
        discord: String? = "https://example.invalid/chat",
        signingKey: String = "0".repeat(64),
    ) = ExtensionStore(
        indexUrl = INDEX_URL,
        name = name,
        badgeLabel = "label",
        signingKey = signingKey,
        contact = ExtensionStore.Contact(website = "https://example.invalid", discord = discord),
        isLegacy = isLegacy,
        extensionListUrl = "https://example.invalid/list.json",
    )

    private companion object {
        const val INDEX_URL = "https://example.invalid/index.min.json"
    }
}
