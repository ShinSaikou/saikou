package com.shinsaikou.core.common.crash

import android.content.Context
import com.shinsaikou.core.common.preferences.BasePreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

/**
 * The uncaught exception handler and the crash screen's start: every crash is written and reaches
 * the handler that was there before, the screen starts once for it, and the two guards against a
 * loop (the crash screen's own process, a start within the window) start nothing. Files are real,
 * on a temp directory, and the clock is a variable, so the window is an assertion rather than a wait.
 */
class CrashHandlerTest {

    @TempDir
    lateinit var tempDir: File

    private var now = 1_000_000L
    private val started = mutableListOf<File>()
    private val chained = mutableListOf<Throwable>()
    private val previous = Thread.UncaughtExceptionHandler { _, throwable -> chained += throwable }
    private val stamp get() = File(tempDir, "stamp")
    private val crashes get() = File(tempDir, "crash").apply { mkdirs() }

    private var defaultBefore: Thread.UncaughtExceptionHandler? = null

    @BeforeEach
    fun rememberDefault() {
        defaultBefore = Thread.getDefaultUncaughtExceptionHandler()
    }

    @AfterEach
    fun restoreDefault() {
        Thread.setDefaultUncaughtExceptionHandler(defaultBefore)
    }

    private fun launcher(inCrashProcess: Boolean = false, start: (File) -> Unit = { started += it }) =
        CrashScreenLauncher(inCrashProcess = inCrashProcess, stamp = stamp, now = { now }, start = start)

    private var written = 0

    /** Stands in for `CrashLog.write`: one file per crash, holding the message. */
    private val writeToTemp: (Thread, Throwable) -> File = { _, throwable ->
        File(crashes, "crash-${written++}.txt").apply { writeText(throwable.message.orEmpty()) }
    }

    private fun handler(screen: CrashScreenLauncher?, write: (Thread, Throwable) -> File = writeToTemp) =
        CrashHandler(write, screen, previous)

    @Test
    fun `a crash is written, starts the screen with its file once, and reaches the previous handler`() {
        val boom = IllegalStateException("boom")

        handler(launcher()).uncaughtException(Thread.currentThread(), boom)

        val file = File(crashes, "crash-0.txt")
        file.readText() shouldBe "boom"
        started shouldContainExactly listOf(file)
        chained shouldContainExactly listOf(boom)
    }

    @Test
    fun `a second crash inside the window is written but starts no second screen`() {
        val handler = handler(launcher())

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("first"))
        now += CrashScreenLauncher.WINDOW_MILLIS - 1
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("second"))

        crashes.listFiles()!!.toList() shouldHaveSize 2
        started shouldContainExactly listOf(File(crashes, "crash-0.txt"))
        chained shouldHaveSize 2
    }

    @Test
    fun `the window outlives the process, since the next crash reads it from the stamp`() {
        handler(launcher()).uncaughtException(Thread.currentThread(), IllegalStateException("first"))
        now += 1_000

        // A new handler and launcher, as the restarted process builds them.
        handler(launcher()).uncaughtException(Thread.currentThread(), IllegalStateException("second"))

        started shouldHaveSize 1
    }

    @Test
    fun `a crash once the window has passed starts the screen again`() {
        val handler = handler(launcher())

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("first"))
        now += CrashScreenLauncher.WINDOW_MILLIS
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("second"))

        started shouldContainExactly listOf(File(crashes, "crash-0.txt"), File(crashes, "crash-1.txt"))
    }

    @Test
    fun `after Restart or Close the next crash starts the screen at once`() {
        val launcher = launcher()
        val handler = handler(launcher)

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("first"))
        launcher.clear()
        now += 1
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("second"))

        started shouldHaveSize 2
    }

    @Test
    fun `a crash in the crash screen's own process is written and starts nothing`() {
        val boom = IllegalStateException("in the screen")

        handler(launcher(inCrashProcess = true)).uncaughtException(Thread.currentThread(), boom)

        File(crashes, "crash-0.txt").readText() shouldBe "in the screen"
        started shouldHaveSize 0
        stamp.exists() shouldBe false
        chained shouldContainExactly listOf(boom)
    }

    @Test
    fun `a write that fails starts nothing and still reaches the previous handler`() {
        val boom = IllegalStateException("boom")

        handler(launcher()) { _, _ -> throw OutOfMemoryError("no room for the file") }
            .uncaughtException(Thread.currentThread(), boom)

        started shouldHaveSize 0
        chained shouldContainExactly listOf(boom)
    }

    @Test
    fun `a screen that fails to start still reaches the previous handler and leaves no stamp`() {
        val boom = IllegalStateException("boom")

        handler(launcher { throw SecurityException("not allowed to start") })
            .uncaughtException(Thread.currentThread(), boom)

        chained shouldContainExactly listOf(boom)
        stamp.exists() shouldBe false
    }

    @Test
    fun `the installed handler writes through CrashLog, starts the screen and chains to the one before`() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val crashLog = CrashLog(context, BasePreferences(InMemoryPreferenceStore()))
        crashLog.install("1.2.3", 45, launcher())
        val boom = IllegalStateException("through the default handler")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        val file = crashLog.latest()!!
        file.readText() shouldContain "through the default handler"
        started shouldContainExactly listOf(file)
        chained shouldContainExactly listOf(boom)
    }

    @Test
    fun `the crash screen's process writes its own crash and starts nothing`() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        Thread.setDefaultUncaughtExceptionHandler(previous)
        CrashLog.installWithoutGraph(context, "1.2.3", 45, launcher(inCrashProcess = true))
        val boom = IllegalStateException("in the screen")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        val files = CrashLog.directoryOf(context).listFiles()!!.toList()
        files shouldHaveSize 1
        files.single().readText() shouldContain "Saikou 1.2.3 (45)"
        files.single().readText() shouldContain "in the screen"
        started shouldHaveSize 0
        chained shouldContainExactly listOf(boom)
    }
}
