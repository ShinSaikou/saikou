package com.shinsaikou.core.common.crash

import android.content.Context
import com.shinsaikou.core.common.preferences.BasePreferences
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

class CrashLogFormatTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var preferences: BasePreferences
    private lateinit var crashLog: CrashLog
    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        every { context.filesDir } returns filesDir
        preferences = BasePreferences(InMemoryPreferenceStore())
        crashLog = CrashLog(context, preferences)
    }

    @Test
    fun `a written crash carries the header, the thread and the stack trace`() {
        crashLog.setAppVersion("1.2.3", 45)
        crashLog.extensionSnapshot = { listOf("dev.example.one 12 lib 1.6") }

        crashLog.write(Thread.currentThread(), IllegalStateException("boom"))

        val text = crashLog.latest()!!.readText()
        text shouldContain "Saikou 1.2.3 (45)"
        text shouldContain "Thread: ${Thread.currentThread().name}"
        text shouldContain "Installed extensions:\ndev.example.one 12 lib 1.6"
        text shouldContain "java.lang.IllegalStateException: boom"
    }

    @Test
    fun `only the newest ten files survive`() {
        repeat(12) { index ->
            File(crashLog.directory, "crash-$index.txt").apply {
                writeText("old")
                setLastModified(1_000_000L + index * 1_000L)
            }
        }
        crashLog.write(Thread.currentThread(), IllegalStateException("boom"))

        val remaining = crashLog.list()
        remaining shouldHaveSize CrashLog.MAX_FILES
        remaining.none { it.name == "crash-0.txt" } shouldBe true
        remaining.first().readText() shouldContain "boom"
    }

    @Test
    fun `a crash counts as unexported until it is marked`() {
        crashLog.hasUnexportedCrash() shouldBe false

        crashLog.write(Thread.currentThread(), IllegalStateException("boom"))
        crashLog.hasUnexportedCrash() shouldBe true

        crashLog.markExported()
        crashLog.hasUnexportedCrash() shouldBe false

        crashLog.clear()
        crashLog.list() shouldHaveSize 0
        crashLog.latest() shouldBe null
    }

    /**
     * The crash screen settles the banner from its own process, through a file rather than the
     * preference, and a crash written after it raises the banner again.
     */
    @Test
    fun `a crash the crash screen showed raises no banner, and a later one does`() {
        crashLog.write(Thread.currentThread(), IllegalStateException("shown"))
        crashLog.hasUnexportedCrash() shouldBe true

        CrashLog.markShownOnCrashScreen(context)
        crashLog.hasUnexportedCrash() shouldBe false

        crashLog.write(Thread.currentThread(), IllegalStateException("later"))
            .setLastModified(System.currentTimeMillis() + 1_000L)
        crashLog.hasUnexportedCrash() shouldBe true
    }
}
