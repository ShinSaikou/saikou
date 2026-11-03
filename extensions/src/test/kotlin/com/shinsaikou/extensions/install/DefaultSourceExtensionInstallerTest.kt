package com.shinsaikou.extensions.install

import com.shinsaikou.domain.extension.SourceExtensionInstaller.Failure
import com.shinsaikou.domain.extension.SourceExtensionInstaller.FromAddedStore
import com.shinsaikou.domain.extension.SourceExtensionInstaller.KnownStore
import com.shinsaikou.domain.extension.SourceExtensionInstaller.Progress
import com.shinsaikou.domain.extension.SourceExtensionInstaller.WantedSource
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.repo.RepoRefreshException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.extension.model.ExtensionStore
import java.io.IOException

class DefaultSourceExtensionInstallerTest {

    private val manager = mockk<ExtensionManager>()
    private val storeRows = mutableListOf(STORE)
    private val stores = mockk<ExtensionStoreRepository> {
        coEvery { getAll() } answers { storeRows.toList() }
    }
    private val subject = DefaultSourceExtensionInstaller(manager, stores)

    /** [listed] from [STORE], the one store this device had before the restore. */
    private fun listedBeforeTheRestore(vararg listed: Extension.Available) {
        coEvery { manager.availableFrom(setOf(STORE.indexUrl)) } returns listed.toList()
        coEvery { manager.availableFrom(emptySet()) } returns emptyList()
    }

    @Test
    fun `refreshes, then installs each matched extension in turn and reports each outcome`() = runTest {
        val one = available("fixture.one", 11L)
        val two = available("fixture.two", 21L)
        val onDisk = available("fixture.ondisk", 31L)
        coEvery { manager.refreshAvailable() } returns Result.success(Unit)
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one, two, onDisk))
        listedBeforeTheRestore(one, two, onDisk)
        every { manager.packagesOnDisk() } returns setOf("fixture.ondisk")
        coEvery { manager.installAndWait(one) } returns true
        coEvery { manager.installAndWait(two) } returns false
        every { manager.installFailures } returns MutableStateFlow(mapOf("fixture.two" to IOException("HTTP 404")))
        val progress = mutableListOf<Progress>()

        val outcome = subject.install(
            listOf(WantedSource(11L, "A"), WantedSource(21L, "B"), WantedSource(31L, "C"), WantedSource(41L, "D")),
            storesBefore = listOf(KnownStore(STORE.indexUrl, STORE.signingKey)),
        ) { progress += it }

        coVerifyOrder {
            manager.refreshAvailable()
            manager.installAndWait(one)
            manager.installAndWait(two)
        }
        // Counted by the installs through, so a bar drawn from it never runs ahead of them.
        progress shouldContainExactly listOf(Progress(0, 2, one.name), Progress(1, 2, two.name))
        outcome.installed shouldContainExactly listOf(one.name)
        outcome.failed shouldContainExactly listOf(Failure(two.name, "HTTP 404"))
        outcome.alreadyInstalled shouldContainExactly listOf(onDisk.name)
        outcome.notFound shouldContainExactly listOf(WantedSource(41L, "D"))
        outcome.unreadStores.shouldBeEmpty()
        outcome.fromAddedStores.shouldBeEmpty()
    }

    @Test
    fun `a store the restore added is not installed from, and its extensions come back with the store named`() = runTest {
        val added = STORE.copy(indexUrl = "https://example.invalid/added/index.pb", name = "Fixture Added", signingKey = "1".repeat(64))
        storeRows += added
        val fromBefore = available("fixture.before", 11L)
        val fromAdded = available("fixture.added", 21L, store = added)
        coEvery { manager.refreshAvailable() } returns Result.success(Unit)
        every { manager.availableExtensions } returns MutableStateFlow(listOf(fromBefore, fromAdded))
        listedBeforeTheRestore(fromBefore)
        every { manager.packagesOnDisk() } returns emptySet()
        coEvery { manager.installAndWait(fromBefore) } returns true

        val outcome = subject.install(
            listOf(WantedSource(11L, "A"), WantedSource(21L, "B")),
            storesBefore = listOf(KnownStore(STORE.indexUrl, STORE.signingKey)),
        ) {}

        coVerify(exactly = 1) { manager.installAndWait(any()) }
        outcome.installed shouldContainExactly listOf(fromBefore.name)
        outcome.fromAddedStores shouldContainExactly listOf(
            FromAddedStore(fromAdded.name, "fixture.added", "Fixture Added", "https://example.invalid/added/index.pb"),
        )
        outcome.notFound.shouldBeEmpty()
    }

    @Test
    fun `on a device with no store before the restore, every extension waits for the tap`() = runTest {
        val one = available("fixture.one", 11L)
        coEvery { manager.refreshAvailable() } returns Result.success(Unit)
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one))
        coEvery { manager.availableFrom(emptySet()) } returns emptyList()
        every { manager.packagesOnDisk() } returns emptySet()

        val outcome = subject.install(listOf(WantedSource(11L, "A")), storesBefore = emptyList()) {}

        coVerify(exactly = 0) { manager.installAndWait(any()) }
        outcome.fromAddedStores.map { it.packageName } shouldContainExactly listOf("fixture.one")
    }

    @Test
    fun `a store before the restore whose key the backup changed counts as added`() = runTest {
        val one = available("fixture.one", 11L)
        coEvery { manager.refreshAvailable() } returns Result.success(Unit)
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one))
        coEvery { manager.availableFrom(emptySet()) } returns emptyList()
        every { manager.packagesOnDisk() } returns emptySet()

        // The row had no key before; a restore that gave it one chose the key.
        val outcome = subject.install(listOf(WantedSource(11L, "A")), listOf(KnownStore(STORE.indexUrl, ""))) {}

        coVerify(exactly = 0) { manager.installAndWait(any()) }
        outcome.fromAddedStores.map { it.packageName } shouldContainExactly listOf("fixture.one")
    }

    @Test
    fun `a store before the restore that it merged into another address of the same repository is still one`() = runTest {
        // Before: the legacy descriptor address. After the merge: the named index row, same key.
        val one = available("fixture.one", 11L)
        coEvery { manager.refreshAvailable() } returns Result.success(Unit)
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one))
        listedBeforeTheRestore(one)
        every { manager.packagesOnDisk() } returns emptySet()
        coEvery { manager.installAndWait(one) } returns true

        val outcome = subject.install(
            listOf(WantedSource(11L, "A")),
            listOf(KnownStore("http://example.invalid/repo.json", STORE.signingKey)),
        ) {}

        outcome.installed shouldContainExactly listOf(one.name)
    }

    @Test
    fun `an index that could not be read is named, and what the others list still installs`() = runTest {
        val one = available("fixture.one", 11L)
        val unread = STORE.copy(indexUrl = "https://example.invalid/down/index.pb", name = "Fixture Down")
        coEvery { manager.refreshAvailable() } returns
            Result.failure(RepoRefreshException(listOf(RepoRefreshException.Failure(unread, IOException("timeout")))))
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one))
        listedBeforeTheRestore(one)
        every { manager.packagesOnDisk() } returns emptySet()
        coEvery { manager.installAndWait(one) } returns true

        val outcome = subject.install(
            listOf(WantedSource(11L, "A"), WantedSource(51L, "E")),
            listOf(KnownStore(STORE.indexUrl, STORE.signingKey)),
        ) {}

        outcome.installed shouldContainExactly listOf(one.name)
        outcome.notFound shouldContainExactly listOf(WantedSource(51L, "E"))
        outcome.unreadStores shouldContainExactly listOf("Fixture Down")
    }

    @Test
    fun `a refresh that throws carries on with what is listed, and names no store`() = runTest {
        val one = available("fixture.one", 11L)
        coEvery { manager.refreshAvailable() } throws IllegalStateException("the database is locked")
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one))
        listedBeforeTheRestore(one)
        every { manager.packagesOnDisk() } returns emptySet()
        coEvery { manager.installAndWait(one) } returns true

        val outcome = subject.install(listOf(WantedSource(11L, "A")), listOf(KnownStore(STORE.indexUrl, STORE.signingKey))) {}

        outcome.installed shouldContainExactly listOf(one.name)
        outcome.unreadStores.shouldBeEmpty()
    }

    @Test
    fun `an install that throws is a failure of its own, and the next one still installs`() = runTest {
        val one = available("fixture.one", 11L)
        val two = available("fixture.two", 21L)
        coEvery { manager.refreshAvailable() } returns Result.success(Unit)
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one, two))
        listedBeforeTheRestore(one, two)
        every { manager.packagesOnDisk() } returns emptySet()
        coEvery { manager.installAndWait(one) } throws IllegalStateException("the reload failed")
        coEvery { manager.installAndWait(two) } returns true

        val outcome = subject.install(
            listOf(WantedSource(11L, "A"), WantedSource(21L, "B")),
            listOf(KnownStore(STORE.indexUrl, STORE.signingKey)),
        ) {}

        outcome.failed shouldContainExactly listOf(Failure(one.name, "the reload failed"))
        outcome.installed shouldContainExactly listOf(two.name)
    }

    @Test
    fun `a cancel during an install is thrown, not recorded`() = runTest {
        val one = available("fixture.one", 11L)
        coEvery { manager.refreshAvailable() } returns Result.success(Unit)
        every { manager.availableExtensions } returns MutableStateFlow(listOf(one))
        listedBeforeTheRestore(one)
        every { manager.packagesOnDisk() } returns emptySet()
        coEvery { manager.installAndWait(one) } throws CancellationException("stopped")

        shouldThrow<CancellationException> {
            subject.install(listOf(WantedSource(11L, "A")), listOf(KnownStore(STORE.indexUrl, STORE.signingKey))) {}
        }
    }

    @Test
    fun `no sources asks nothing of the manager`() = runTest {
        subject.install(emptyList(), emptyList()) {}.installed.size shouldBe 0

        coVerify(exactly = 0) { manager.refreshAvailable() }
    }

    private fun available(packageName: String, sourceId: Long, store: ExtensionStore = STORE) = Extension.Available(
        packageName = packageName,
        name = "Fixture $packageName",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        sources = listOf(AvailableSource(sourceId, "Fixture Source $sourceId", "en", "")),
        apkUrl = "https://example.invalid/apk/$packageName.apk",
        iconUrl = "https://example.invalid/icon/$packageName.png",
        jarUrl = null,
        store = store,
    )

    private companion object {
        val STORE = ExtensionStore(
            indexUrl = "https://example.invalid/index.pb",
            name = "Fixture Index",
            badgeLabel = "FI",
            signingKey = "0".repeat(64),
            contact = ExtensionStore.Contact("https://example.invalid", null),
            isLegacy = false,
            extensionListUrl = null,
        )
    }
}
