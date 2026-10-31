package com.shinsaikou.ui.crash

import app.cash.turbine.test
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.i18n.R
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The crash log screen's list, its two destructive actions, and Share, the one way a log leaves
 * the device once the launch banner is gone. The files are real, on a temp directory, because the
 * size and time shown on a row, the per-row delete and the check before a share are about the
 * filesystem rather than about a call being forwarded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashLogViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val crashLog = mockk<CrashLog>(relaxed = true)

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        every { crashLog.list() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = CrashLogViewModel(
        crashLog = crashLog,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    /** [kib] kibibytes exactly, so the row's size is an assertion rather than a rounding. */
    private fun crashFile(name: String, kib: Int = 2): File =
        File(tempDir, name).apply { writeBytes(ByteArray(kib * 1024) { 'x'.code.toByte() }) }

    @Test
    fun `the crash files reach state newest first with each file's size and time`() = runTest(testDispatcher) {
        val newest = crashFile("crash-2.txt", kib = 3).apply { setLastModified(NEWEST_MILLIS) }
        val oldest = crashFile("crash-1.txt", kib = 1).apply { setLastModified(OLDEST_MILLIS) }
        every { crashLog.list() } returns listOf(newest, oldest)

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.loading shouldBe false
        viewModel.state.value.files shouldContainExactly listOf(
            CrashLogViewModel.LogFile(
                name = "crash-2.txt",
                sizeKib = 3,
                path = newest.absolutePath,
                modifiedAt = NEWEST_MILLIS,
            ),
            CrashLogViewModel.LogFile(
                name = "crash-1.txt",
                sizeKib = 1,
                path = oldest.absolutePath,
                modifiedAt = OLDEST_MILLIS,
            ),
        )
    }

    @Test
    fun `delete removes only that row from state`() = runTest(testDispatcher) {
        val first = crashFile("crash-2.txt")
        val second = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(first, second)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete(first.absolutePath)
        advanceUntilIdle()

        viewModel.state.value.files.map { it.path } shouldContainExactly listOf(second.absolutePath)
    }

    @Test
    fun `delete removes the file from disk`() = runTest(testDispatcher) {
        val file = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(file)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete(file.absolutePath)
        advanceUntilIdle()

        file.exists() shouldBe false
    }

    @Test
    fun `delete closes the viewer when the open log is the one deleted`() = runTest(testDispatcher) {
        val open = crashFile("crash-2.txt")
        val other = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(open, other)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.open(open.absolutePath)
        advanceUntilIdle()
        viewModel.state.value.selected shouldBe open.absolutePath

        viewModel.delete(open.absolutePath)
        advanceUntilIdle()

        viewModel.state.value.selected shouldBe null
        viewModel.state.value.selectedText shouldBe ""
    }

    @Test
    fun `clear empties the list and the viewer`() = runTest(testDispatcher) {
        val file = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(file)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.open(file.absolutePath)
        advanceUntilIdle()

        viewModel.clear()
        advanceUntilIdle()

        verify(exactly = 1) { crashLog.clear() }
        viewModel.state.value.files shouldBe emptyList()
        viewModel.state.value.selected shouldBe null
        viewModel.state.value.selectedText shouldBe ""
    }

    @Test
    fun `viewing a log settles the crash banner`() = runTest(testDispatcher) {
        val file = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(file)
        val viewModel = viewModel()
        advanceUntilIdle()
        verify(exactly = 0) { crashLog.markExported() }

        viewModel.open(file.absolutePath)
        advanceUntilIdle()

        verify(exactly = 1) { crashLog.markExported() }
    }

    @Test
    fun `share hands the screen that file and settles the crash banner`() = runTest(testDispatcher) {
        val shared = crashFile("crash-2.txt")
        val other = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(shared, other)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.share(shared.absolutePath)
            advanceUntilIdle()

            awaitItem() shouldBe CrashLogViewModel.Event.Share(shared.absolutePath)
            expectNoEvents()
        }
        verify(exactly = 1) { crashLog.markExported() }
        shared.exists() shouldBe true
        viewModel.state.value.files.map { it.path } shouldContainExactly listOf(shared.absolutePath, other.absolutePath)
    }

    @Test
    fun `share from the viewer shares the open log and leaves the viewer open`() = runTest(testDispatcher) {
        val file = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(file)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.open(file.absolutePath)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.share(checkNotNull(viewModel.state.value.selected))
            advanceUntilIdle()

            awaitItem() shouldBe CrashLogViewModel.Event.Share(file.absolutePath)
        }
        viewModel.state.value.selected shouldBe file.absolutePath
    }

    @Test
    fun `sharing a log gone since the list was read says so and settles nothing`() = runTest(testDispatcher) {
        val file = crashFile("crash-1.txt")
        every { crashLog.list() } returns listOf(file)
        val viewModel = viewModel()
        advanceUntilIdle()
        file.delete()

        viewModel.events.test {
            viewModel.share(file.absolutePath)
            advanceUntilIdle()

            awaitItem() shouldBe CrashLogViewModel.Event.Snackbar(R.string.crash_read_failed)
            expectNoEvents()
        }
        verify(exactly = 0) { crashLog.markExported() }
    }

    private companion object {
        /** Whole seconds, which every filesystem a temp directory sits on keeps exactly. */
        const val NEWEST_MILLIS = 1_790_000_000_000L
        const val OLDEST_MILLIS = 1_780_000_000_000L
    }
}
