package com.shinsaikou.extensionupdate

import com.shinsaikou.backup.BackupRun
import com.shinsaikou.backup.BackupRunState
import com.shinsaikou.backup.importer.BackupImportRequest
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.ExtensionArchive
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.ui.backup.RestoreExtensionInstalls
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.extension.model.ExtensionStore
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * One run of the automatic extension updates (R162 item 2): which updates it installs unattended,
 * which it leaves to the owner, and how it waits for and yields to other installs. The manager,
 * the loader and the restore's queue are mocks driven by the flows below; the install itself is a
 * recorded fake that updates those flows the way the manager does.
 *
 * The fields carry a Flow suffix because the mockk configuration block puts ExtensionManager itself
 * in scope as an implicit receiver, as in `ExtensionsViewModelTest`.
 */
class ExtensionAutoUpdateTest {

    private val initializedFlow = MutableStateFlow(true)
    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val availableFlow = MutableStateFlow<List<Extension.Available>>(emptyList())
    private val untrustedFlow = MutableStateFlow<List<Extension.Untrusted>>(emptyList())
    private val stepsFlow = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    private val failuresFlow = MutableStateFlow<Map<String, Throwable>>(emptyMap())
    private val restoreStateFlow = MutableStateFlow(RestoreExtensionInstalls.State())
    private val backupRunFlow = MutableStateFlow<BackupRun?>(null)

    /** The signers each installed copy's file carries. */
    private val signersByFile = mutableMapOf<File, List<String>>()

    /** The configured repositories, and what each lists at its last read. */
    private val rows = mutableListOf<ExtensionStore>()
    private val listings = mutableMapOf<String, List<Extension.Available>>()

    /** The packages installAndWait was called for, in order. */
    private val installs = mutableListOf<String>()
    private var refreshes = 0
    private var refreshResult: () -> Result<Unit> = { Result.success(Unit) }

    /** What an install does, by package; the default installs it as the manager does. */
    private val behaviours = mutableMapOf<String, suspend (Extension.Available) -> Boolean>()

    private val extensionManager = mockk<ExtensionManager> {
        every { isInitialized } returns initializedFlow
        every { installedExtensions } returns installedFlow
        every { availableExtensions } returns availableFlow
        every { untrustedExtensions } returns untrustedFlow
        every { installSteps } returns stepsFlow
        every { installFailures } returns failuresFlow
        coEvery { refreshAvailable() } answers {
            refreshes++
            refreshResult()
        }
        coEvery { availableFrom(any()) } answers { firstArg<Set<String>>().flatMap { listings[it].orEmpty() } }
        coEvery { installAndWait(any()) } coAnswers {
            val extension = firstArg<Extension.Available>()
            installs += extension.packageName
            (behaviours[extension.packageName] ?: ::installForReal).invoke(extension)
        }
    }
    private val loader = mockk<ExtensionLoader> {
        coEvery { readArchive(any()) } answers {
            signersByFile[firstArg()]?.let { signers ->
                mockk<ExtensionArchive> { every { signerFingerprints } returns signers }
            }
        }
    }
    private val stores = mockk<ExtensionStoreRepository> {
        coEvery { getAll() } answers { rows.toList() }
    }
    private val restoreInstalls = mockk<RestoreExtensionInstalls> {
        every { state } returns restoreStateFlow
    }
    private val backupImportRequest = mockk<BackupImportRequest> {
        every { run } returns backupRunFlow
    }

    private val autoUpdate = ExtensionAutoUpdate(extensionManager, loader, stores, restoreInstalls, backupImportRequest)

    /** The manager's own install: the copy is replaced by the new version, and the step ends Installed. */
    private suspend fun installForReal(extension: Extension.Available): Boolean {
        installedFlow.value = installedFlow.value.map {
            if (it.packageName == extension.packageName) {
                it.copy(
                    versionName = extension.versionName,
                    versionCode = extension.versionCode,
                    libVersion = extension.libVersion,
                    hasUpdate = false,
                )
            } else {
                it
            }
        }
        stepsFlow.value = stepsFlow.value + (extension.packageName to InstallStep.Installed)
        return true
    }

    private fun store(url: String, key: String) = ExtensionStore(
        indexUrl = url,
        name = "Repository $url",
        badgeLabel = "",
        signingKey = key,
        contact = ExtensionStore.Contact(website = "", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    /**
     * An installed extension with an update, and a repository that lists it. [signers] are what the
     * installed copy is signed with; [storeKey] is the key the repository pinned.
     */
    private fun add(
        packageName: String,
        signers: List<String> = listOf(KEY),
        storeKey: String = KEY,
        storeUrl: String = STORE,
        hasUpdate: Boolean = true,
        obsolete: Boolean = false,
        listed: Boolean = true,
        apkUrl: String = "https://files.invalid/$packageName.apk",
        listedVersionCode: Long = 2,
        listedLibVersion: Double = 1.4,
    ) {
        val repository = rows.firstOrNull { it.indexUrl == storeUrl } ?: store(storeUrl, storeKey).also { rows += it }
        val file = File("/ext/$packageName.ext")
        signersByFile[file] = signers
        installedFlow.value = installedFlow.value + Extension.Installed(
            packageName = packageName,
            name = "Name of $packageName",
            versionName = "1",
            versionCode = 1,
            libVersion = 1.4,
            lang = "en",
            isNsfw = false,
            sources = emptyList(),
            hasUpdate = hasUpdate,
            isObsolete = obsolete,
            extFile = file,
        )
        val entry = Extension.Available(
            packageName = packageName,
            name = "Name of $packageName",
            versionName = "$listedVersionCode",
            versionCode = listedVersionCode,
            libVersion = listedLibVersion,
            lang = "en",
            isNsfw = false,
            sources = emptyList(),
            apkUrl = apkUrl,
            iconUrl = "",
            jarUrl = null,
            store = repository,
        )
        if (listed) {
            availableFlow.value = availableFlow.value + entry
            listings[storeUrl] = listings[storeUrl].orEmpty() + entry
        }
    }

    private fun name(packageName: String) = "Name of $packageName"

    private fun untrusted(packageName: String) = Extension.Untrusted(
        packageName = packageName,
        name = name(packageName),
        versionName = "1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        signatureHash = "ABC",
    )

    // 1. Selection

    @Test
    fun `an update whose installed copy is signed by the pinned key of a repository that lists it is installed`() = runTest {
        add("a")

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
        installs shouldBe listOf("a")
    }

    @Test
    fun `a copy trusted by hand, signed with something other than the pinned key, is left for the owner`() = runTest {
        add("a", signers = listOf("HAND"))

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(needsYou = listOf(name("a")))
        installs shouldBe emptyList()
    }

    @Test
    fun `an update served by a repository with a different key is left for the owner`() = runTest {
        add("a", signers = listOf(KEY), storeKey = "OTHER")

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(needsYou = listOf(name("a")))
        installs shouldBe emptyList()
    }

    @Test
    fun `one of two repositories serving the file with the installed key is enough`() = runTest {
        add("a", signers = listOf(KEY), storeKey = "OTHER", storeUrl = "https://other.invalid/index")
        // The same file, listed by a second repository that pinned the installed copy's key.
        val second = store(STORE, KEY).also { rows += it }
        val entry = listings.getValue("https://other.invalid/index").single()
        listings[STORE] = listOf(entry.copy(store = second))

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
    }

    @Test
    fun `a repository with no pinned key never vouches for an update`() = runTest {
        add("a", signers = listOf(""), storeKey = "")

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(needsYou = listOf(name("a")))
        installs shouldBe emptyList()
    }

    @Test
    fun `an installed copy whose file cannot be read is left for the owner`() = runTest {
        add("a")
        signersByFile.clear()

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(needsYou = listOf(name("a")))
        installs shouldBe emptyList()
    }

    @Test
    fun `a key that matches only one of several signers of the installed copy is enough`() = runTest {
        add("a", signers = listOf("FIRST", KEY))

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
    }

    @Test
    fun `an update the listing serves from another address is not the one the repository lists now`() = runTest {
        add("a")
        // The merged entry names a file the configured repository does not list.
        availableFlow.value = availableFlow.value.map { it.copy(apkUrl = "https://elsewhere.invalid/a.apk") }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome()
        installs shouldBe emptyList()
    }

    @Test
    fun `a repository removed since the read is skipped without a word`() = runTest {
        add("a")
        rows.clear()

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome()
        installs shouldBe emptyList()
    }

    @Test
    fun `an extension no index lists any more is skipped without a word`() = runTest {
        add("a", listed = false)

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome()
        installs shouldBe emptyList()
    }

    @Test
    fun `an obsolete extension is never installed or reported`() = runTest {
        add("a", obsolete = true)

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome()
        installs shouldBe emptyList()
    }

    @Test
    fun `an extension with no update is left alone`() = runTest {
        add("a", hasUpdate = false)

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome()
        installs shouldBe emptyList()
    }

    @Test
    fun `an untrusted or not-loaded extension is not installed even when an index lists a newer one`() = runTest {
        // Neither is in the installed list, which is the only list a run reads its candidates from.
        untrustedFlow.value = listOf(untrusted("u"))
        add("u", hasUpdate = true)
        installedFlow.value = emptyList()

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome()
        installs shouldBe emptyList()
    }

    @Test
    fun `a run that finds nothing is empty`() = runTest {
        autoUpdate.run().isEmpty shouldBe true
    }

    @Test
    fun `an extension the owner updated meanwhile is not installed again on its turn`() = runTest {
        add("a")
        add("b")
        behaviours["a"] = { extension ->
            // The owner's own Update finished b while a was installing.
            installedFlow.value = installedFlow.value.map { if (it.packageName == "b") it.copy(hasUpdate = false) else it }
            installForReal(extension)
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
        installs shouldBe listOf("a")
    }

    @Test
    fun `the indexes are read again before anything is chosen`() = runTest {
        add("a")

        autoUpdate.run()

        refreshes shouldBe 1
    }

    @Test
    fun `a failed index read still lets the run use what an earlier read listed`() = runTest {
        add("a")
        refreshResult = { Result.failure(IOException("fixture: offline")) }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
    }

    @Test
    fun `an index read that throws still lets the run use what an earlier read listed`() = runTest {
        add("a")
        refreshResult = { throw IOException("fixture: offline") }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
    }

    @Test
    fun `a cancel during the index read propagates`() = runTest {
        add("a")
        refreshResult = { throw CancellationException("fixture: stopped") }

        shouldThrow<CancellationException> { autoUpdate.run() }
        installs shouldBe emptyList()
    }

    @Test
    fun `a run waits for the manager to finish starting`() = runTest {
        add("a")
        initializedFlow.value = false

        val outcome = async { autoUpdate.run() }
        runCurrent()
        outcome.isCompleted shouldBe false
        refreshes shouldBe 0

        initializedFlow.value = true
        runCurrent()
        outcome.await() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
    }

    // 2. One at a time, in order

    @Test
    fun `installs run one at a time, in the order the manager lists them`() = runTest {
        var running = 0
        var most = 0
        for (id in listOf("a", "b", "c")) {
            add(id)
            behaviours[id] = { extension ->
                running++
                most = maxOf(most, running)
                delay(100)
                running--
                installForReal(extension)
            }
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf("a", "b", "c").map(::name))
        installs shouldBe listOf("a", "b", "c")
        most shouldBe 1
    }

    @Test
    fun `an install that throws is reported as failed and the next one still runs`() = runTest {
        add("a")
        add("b")
        behaviours["a"] = { throw IOException("fixture: the download dropped") }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("b")), failed = listOf(name("a")))
        installs shouldBe listOf("a", "b")
    }

    @Test
    fun `an install that ends without installing is reported as failed and the next one still runs`() = runTest {
        add("a")
        add("b")
        behaviours["a"] = { false }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("b")), failed = listOf(name("a")))
    }

    @Test
    fun `an install the loader refused for its signer is left for the owner and the next one still runs`() = runTest {
        add("a")
        add("b")
        behaviours["a"] = {
            failuresFlow.value = mapOf("a" to InstallFailedException(InstallRejection.SIGNER_MISMATCH))
            false
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("b")), needsYou = listOf(name("a")))
        installs shouldBe listOf("a", "b")
    }

    @Test
    fun `an install refused for another reason is a failure, not a wait for the owner`() = runTest {
        add("a")
        behaviours["a"] = {
            failuresFlow.value = mapOf("a" to InstallFailedException(InstallRejection.DOWNGRADE))
            false
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(failed = listOf(name("a")))
    }

    @Test
    fun `a download that turned out to be another package is a failure, not a wait for the owner`() = runTest {
        add("a")
        add("b")
        behaviours["a"] = {
            failuresFlow.value = mapOf("a" to InstallFailedException(InstallRejection.PACKAGE_MISMATCH))
            stepsFlow.value = stepsFlow.value + ("a" to InstallStep.Error)
            false
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("b")), failed = listOf(name("a")))
        installs shouldBe listOf("a", "b")
    }

    @Test
    fun `a signer refusal recorded for another package does not turn a failure into a wait`() = runTest {
        add("a")
        behaviours["a"] = {
            failuresFlow.value = mapOf("zzz" to InstallFailedException(InstallRejection.SIGNER_MISMATCH))
            false
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(failed = listOf(name("a")))
    }

    @Test
    fun `an install that succeeded but left the copy in Needs trust is left for the owner`() = runTest {
        add("a")
        behaviours["a"] = { extension ->
            installedFlow.value = installedFlow.value.filterNot { it.packageName == extension.packageName }
            untrustedFlow.value = listOf(untrusted("a"))
            true
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(needsYou = listOf(name("a")))
    }

    @Test
    fun `an install that succeeded but left no copy loaded or waiting is a failure`() = runTest {
        add("a")
        behaviours["a"] = { extension ->
            installedFlow.value = installedFlow.value.filterNot { it.packageName == extension.packageName }
            true
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(failed = listOf(name("a")))
    }

    @Test
    fun `an install that ends Installed but leaves the older version loaded is a failure`() = runTest {
        add("a")
        add("b")
        // The file the index pointed at was the installed version again, which the loader accepts.
        behaviours["a"] = { extension ->
            stepsFlow.value = stepsFlow.value + (extension.packageName to InstallStep.Installed)
            true
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("b")), failed = listOf(name("a")))
        installedFlow.value.first { it.packageName == "a" }.versionCode shouldBe 1L
    }

    @Test
    fun `an install that loads a version below the listed one is a failure`() = runTest {
        add("a", listedVersionCode = 3)
        behaviours["a"] = { extension ->
            installedFlow.value = installedFlow.value.map {
                if (it.packageName == extension.packageName) it.copy(versionCode = 2, hasUpdate = false) else it
            }
            stepsFlow.value = stepsFlow.value + (extension.packageName to InstallStep.Installed)
            true
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(failed = listOf(name("a")))
    }

    @Test
    fun `a lib-version-only update counts once the newer lib is loaded`() = runTest {
        add("a", listedVersionCode = 1, listedLibVersion = 1.6)

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
        installedFlow.value.single().libVersion shouldBe 1.6
    }

    @Test
    fun `a lib-version-only update that leaves the older lib loaded is a failure`() = runTest {
        add("a", listedVersionCode = 1, listedLibVersion = 1.6)
        behaviours["a"] = { extension ->
            installedFlow.value = installedFlow.value.map {
                if (it.packageName == extension.packageName) it.copy(hasUpdate = false) else it
            }
            stepsFlow.value = stepsFlow.value + (extension.packageName to InstallStep.Installed)
            true
        }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(failed = listOf(name("a")))
    }

    @Test
    fun `a newer version on the same lib counts as updated`() = runTest {
        add("a", listedVersionCode = 5)

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
        installedFlow.value.single().versionCode shouldBe 5L
    }

    @Test
    fun `a cancel during an install propagates and the rest are not started`() = runTest {
        add("a")
        add("b")
        behaviours["a"] = { throw CancellationException("fixture: stopped") }

        shouldThrow<CancellationException> { autoUpdate.run() }
        installs shouldBe listOf("a")
    }

    @Test
    fun `every kind of outcome in one run is reported under its own list`() = runTest {
        add("ok")
        add("hand", signers = listOf("HAND"))
        add("bad")
        add("gone", listed = false)
        behaviours["bad"] = { throw IOException("fixture") }

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(
            updated = listOf(name("ok")),
            failed = listOf(name("bad")),
            needsYou = listOf(name("hand")),
        )
    }

    // 3. Waiting for other installs, and restores

    @Test
    fun `a run waits while an install is in progress and goes on once its step ends`() = runTest {
        add("a")
        stepsFlow.value = mapOf("other" to InstallStep.Downloading)

        val outcome = async { autoUpdate.run() }
        runCurrent()
        installs shouldBe emptyList()
        outcome.isCompleted shouldBe false

        stepsFlow.value = mapOf("other" to InstallStep.Installing)
        runCurrent()
        installs shouldBe emptyList()

        stepsFlow.value = mapOf("other" to InstallStep.Installed)
        runCurrent()
        outcome.await() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
    }

    @Test
    fun `a pending install holds the run back as well`() = runTest {
        add("a")
        stepsFlow.value = mapOf("other" to InstallStep.Pending)

        val outcome = async { autoUpdate.run() }
        runCurrent()
        installs shouldBe emptyList()

        stepsFlow.value = mapOf("other" to InstallStep.Error)
        runCurrent()
        outcome.await()
        installs shouldBe listOf("a")
    }

    @Test
    fun `a run waits while the restore report is installing all, and while it has packages queued`() = runTest {
        add("a")
        restoreStateFlow.value = RestoreExtensionInstalls.State(installingAll = RestoreExtensionInstalls.Progress(1, 2))

        val outcome = async { autoUpdate.run() }
        runCurrent()
        installs shouldBe emptyList()

        // Between two of Install all's installs nothing shows in the manager, but the queue is not empty.
        restoreStateFlow.value = RestoreExtensionInstalls.State(queued = setOf("x"))
        runCurrent()
        installs shouldBe emptyList()
        outcome.isCompleted shouldBe false

        restoreStateFlow.value = RestoreExtensionInstalls.State()
        runCurrent()
        outcome.await() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
    }

    @Test
    fun `the wait is made again before every install, not once for the run`() = runTest {
        add("a")
        add("b")
        behaviours["a"] = { extension ->
            // The owner starts another install as a finishes.
            val done = installForReal(extension)
            stepsFlow.value = stepsFlow.value + ("other" to InstallStep.Downloading)
            done
        }

        val outcome = async { autoUpdate.run() }
        runCurrent()
        installs shouldBe listOf("a")

        stepsFlow.value = stepsFlow.value + ("other" to InstallStep.Installed)
        runCurrent()
        installs shouldBe listOf("a", "b")
        outcome.await() shouldBe ExtensionAutoUpdate.Outcome(updated = listOf("a", "b").map(::name))
    }

    @Test
    fun `a restore running at the start does nothing and says it is busy`() = runTest {
        add("a")
        backupRunFlow.value = BackupRun(UUID.randomUUID(), BackupRunState.ACTIVE)

        autoUpdate.run() shouldBe ExtensionAutoUpdate.Outcome(busy = true)
        installs shouldBe emptyList()
        refreshes shouldBe 0
    }

    @Test
    fun `a restore that has ended, or none, does not stop a run`() = runTest {
        add("a")
        for (state in listOf(BackupRunState.SUCCEEDED, BackupRunState.FAILED, BackupRunState.CANCELLED)) {
            backupRunFlow.value = BackupRun(UUID.randomUUID(), state)
            autoUpdate.run().busy shouldBe false
        }
        backupRunFlow.value = null
        autoUpdate.run().busy shouldBe false
    }

    @Test
    fun `a restore starting mid-run stops it and leaves the rest for the next run`() = runTest {
        add("a")
        add("b")
        add("c")
        behaviours["a"] = { extension ->
            val done = installForReal(extension)
            backupRunFlow.value = BackupRun(UUID.randomUUID(), BackupRunState.ACTIVE)
            done
        }

        val outcome = autoUpdate.run()

        outcome shouldBe ExtensionAutoUpdate.Outcome(updated = listOf(name("a")))
        outcome.busy shouldBe false
        installs shouldBe listOf("a")
    }

    @Test
    fun `a restore starting while the run waits for another install stops it`() = runTest {
        add("a")
        stepsFlow.value = mapOf("other" to InstallStep.Downloading)

        val outcome = async { autoUpdate.run() }
        runCurrent()

        backupRunFlow.value = BackupRun(UUID.randomUUID(), BackupRunState.ACTIVE)
        stepsFlow.value = emptyMap()
        runCurrent()

        outcome.await() shouldBe ExtensionAutoUpdate.Outcome()
        installs shouldBe emptyList()
    }

    private companion object {
        const val KEY = "PINNED"
        const val STORE = "https://store.invalid/index"
    }
}
