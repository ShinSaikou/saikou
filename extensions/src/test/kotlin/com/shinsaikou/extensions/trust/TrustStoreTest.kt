package com.shinsaikou.extensions.trust

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TrustStoreTest {

    private val repoKey = "a".repeat(64)
    private val otherKey = "b".repeat(64)

    private class FakeStores(private val rows: List<ExtensionStore>) : ExtensionStoreRepository {
        override suspend fun get(indexUrl: String): ExtensionStore? = rows.firstOrNull { it.indexUrl == indexUrl }
        override suspend fun getAll(): List<ExtensionStore> = rows
        override fun getAllAsFlow(): Flow<List<ExtensionStore>> = flowOf(rows)
        override fun getCountAsFlow(): Flow<Long> = flowOf(rows.size.toLong())
        override suspend fun upsert(store: ExtensionStore) = Unit
        override suspend fun updateIfSigningKey(store: ExtensionStore, expectedSigningKey: String) = false
        override suspend fun remove(indexUrl: String) = Unit
        override suspend fun mergeDuplicates() = 0
    }

    private fun storeRow(signingKey: String) = ExtensionStore(
        indexUrl = "https://example.invalid/index.pb",
        name = "Fixture Index",
        badgeLabel = "FI",
        signingKey = signingKey,
        contact = ExtensionStore.Contact("https://example.invalid", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private val preferences = ExtensionsPreferences(InMemoryPreferenceStore())

    private fun trustStore(rows: List<ExtensionStore>) =
        TrustStore(FakeStores(rows), preferences, UnconfinedTestDispatcher())

    @Test
    fun `a signer that matches a configured index key is trusted with no prompt`() = runTest {
        val store = trustStore(listOf(storeRow(repoKey)))

        store.isTrusted("com.shinsaikou.fixture", 1, listOf(repoKey)) shouldBe true
    }

    @Test
    fun `trust is by fingerprint, not by which index served the APK`() = runTest {
        val store = trustStore(listOf(storeRow(repoKey), storeRow(otherKey)))

        store.isTrusted("com.shinsaikou.fixture", 1, listOf(otherKey)) shouldBe true
    }

    @Test
    fun `an unknown signer is untrusted until the user says otherwise`() = runTest {
        val store = trustStore(listOf(storeRow(repoKey)))

        store.isTrusted("com.shinsaikou.fixture", 1, listOf(otherKey)) shouldBe false

        store.trust("com.shinsaikou.fixture", 1, otherKey)

        store.isTrusted("com.shinsaikou.fixture", 1, listOf(otherKey)) shouldBe true
    }

    @Test
    fun `an unsigned extension is never trusted`() = runTest {
        val store = trustStore(listOf(storeRow(repoKey)))

        store.isTrusted("com.shinsaikou.fixture", 1, emptyList()) shouldBe false
    }

    @Test
    fun `trusting an update replaces the package's older entry`() = runTest {
        val store = trustStore(emptyList())

        store.trust("com.shinsaikou.fixture", 1, otherKey)
        store.trust("com.shinsaikou.fixture", 2, otherKey)

        preferences.trustedExtensions.get().shouldContainExactly("com.shinsaikou.fixture:2:$otherKey")
        store.isTrusted("com.shinsaikou.fixture", 1, listOf(otherKey)) shouldBe false
        store.isTrusted("com.shinsaikou.fixture", 2, listOf(otherKey)) shouldBe true
    }

    @Test
    fun `revokeAll clears manual overrides and leaves index-rooted trust alone`() = runTest {
        val store = trustStore(listOf(storeRow(repoKey)))
        store.trust("com.shinsaikou.fixture", 1, otherKey)

        store.revokeAll()

        store.isTrusted("com.shinsaikou.fixture", 1, listOf(otherKey)) shouldBe false
        store.isTrusted("com.shinsaikou.fixture", 1, listOf(repoKey)) shouldBe true
    }

    @Test
    fun `the manual entry is keyed on the last signer in the list`() = runTest {
        val store = trustStore(emptyList())
        store.trust("com.shinsaikou.fixture", 1, otherKey)

        store.isTrusted("com.shinsaikou.fixture", 1, listOf(otherKey, repoKey)) shouldBe false
        store.isTrusted("com.shinsaikou.fixture", 1, listOf(repoKey, otherKey)) shouldBe true
    }
}
