package com.shinsaikou.extensions.install

import android.content.Context
import com.shinsaikou.extensions.loader.ExtensionArchive
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallOutcome
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The download half of an install: the file fetched for one package is handed to the loader only
 * when it is that package, so a download that turns out to be another extension is never written.
 */
class ExtensionInstallerTest {

    @TempDir
    lateinit var cacheDir: File

    /** What the downloaded file reads as. */
    private var downloadedPackage = PACKAGE
    private var downloadedSigners = listOf("KEY")

    private val context = mockk<Context> {
        every { cacheDir } answers { this@ExtensionInstallerTest.cacheDir }
    }
    private val loader = mockk<ExtensionLoader> {
        coEvery { readArchive(any()) } answers {
            mockk<ExtensionArchive> {
                every { packageName } returns downloadedPackage
                every { signerFingerprints } returns downloadedSigners
            }
        }
        coEvery { installPrivateExtensionFile(any()) } answers { InstallOutcome.Installed(downloadedPackage, replaced = true) }
    }
    private val repoClient = mockk<ExtensionRepoClient> {
        coEvery { downloadApk(APK_URL, any(), any()) } answers {
            val target = secondArg<File>()
            target.writeText("fixture apk")
            Result.success(target)
        }
    }

    private fun installer(scheduler: TestCoroutineScheduler) =
        ExtensionInstaller(context, loader, repoClient, StandardTestDispatcher(scheduler))

    /** Every file the install left under the cache directory. */
    private fun leftBehind(): List<File> = cacheDir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `a download of the package asked for installs as before`() = runTest {
        val steps = installer(testScheduler).downloadAndInstall(PACKAGE, APK_URL).toList()

        steps shouldContainExactly listOf(
            InstallStep.Pending,
            InstallStep.Downloading,
            InstallStep.Installing,
            InstallStep.Installed,
        )
        coVerify(exactly = 1) { loader.installPrivateExtensionFile(any()) }
        leftBehind().shouldBeEmpty()
    }

    @Test
    fun `a download that is another package is refused before anything is written`() = runTest {
        downloadedPackage = "fixture.other"

        val failure = shouldThrow<InstallFailedException> {
            installer(testScheduler).downloadAndInstall(PACKAGE, APK_URL).toList()
        }

        failure.reason shouldBe InstallRejection.PACKAGE_MISMATCH
        // Downloaded and read, then refused: the loader never gets the file to write.
        coVerify(exactly = 1) { repoClient.downloadApk(APK_URL, any(), any()) }
        coVerify(exactly = 1) { loader.readArchive(any()) }
        coVerify(exactly = 0) { loader.installPrivateExtensionFile(any()) }
        leftBehind().shouldBeEmpty()
    }

    @Test
    fun `the package check comes before the unsigned check, which still refuses a matching package`() = runTest {
        downloadedSigners = emptyList()
        var caught: Throwable? = null

        installer(testScheduler).downloadAndInstall(PACKAGE, APK_URL).catch { caught = it }.toList()

        (caught as InstallFailedException).reason shouldBe InstallRejection.UNSIGNED
        coVerify(exactly = 0) { loader.installPrivateExtensionFile(any()) }

        downloadedPackage = "fixture.other"
        caught = null
        installer(testScheduler).downloadAndInstall(PACKAGE, APK_URL).catch { caught = it }.toList()

        (caught as InstallFailedException).reason shouldBe InstallRejection.PACKAGE_MISMATCH
    }

    @Test
    fun `a refusal from the loader for a matching package still reaches the caller`() = runTest {
        coEvery { loader.installPrivateExtensionFile(any()) } returns InstallOutcome.Rejected(InstallRejection.SIGNER_MISMATCH)

        val failure = shouldThrow<InstallFailedException> {
            installer(testScheduler).downloadAndInstall(PACKAGE, APK_URL).toList()
        }

        failure.reason shouldBe InstallRejection.SIGNER_MISMATCH
    }

    private companion object {
        const val PACKAGE = "fixture.asked"
        const val APK_URL = "https://files.invalid/fixture.apk"
    }
}
