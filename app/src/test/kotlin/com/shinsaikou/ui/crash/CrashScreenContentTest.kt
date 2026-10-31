package com.shinsaikou.ui.crash

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the crash screen shows for the file name its intent carries: that file's text from the crash
 * directory, and nothing for any name that is missing, gone, or reaches outside the directory.
 */
class CrashScreenContentTest {

    @TempDir
    lateinit var root: File

    private val directory get() = File(root, "crash").apply { mkdirs() }

    @Test
    fun `the named crash file is shown, with the file Share hands on`() {
        val file = File(directory, "crash-1700000000000.txt").apply { writeText("java.lang.IllegalStateException: boom") }
        File(directory, "crash-1600000000000.txt").writeText("an older crash")

        CrashScreenContent.load(directory, file.name) shouldBe
            CrashScreenContent.Log(file, "java.lang.IllegalStateException: boom")
    }

    @Test
    fun `no name, or a file that is gone, shows that the log could not be read`() {
        File(directory, "crash-1600000000000.txt").writeText("an older crash")

        CrashScreenContent.load(directory, null) shouldBe CrashScreenContent.Unreadable
        CrashScreenContent.load(directory, "") shouldBe CrashScreenContent.Unreadable
        CrashScreenContent.load(directory, "crash-1700000000000.txt") shouldBe CrashScreenContent.Unreadable
    }

    @Test
    fun `a name that reaches outside the crash directory is never read`() {
        File(root, "secret.txt").writeText("not a crash")
        File(root, "sub").mkdirs()
        File(root, "sub/inner.txt").writeText("not a crash either")

        CrashScreenContent.load(directory, "../secret.txt") shouldBe CrashScreenContent.Unreadable
        CrashScreenContent.load(directory, "..") shouldBe CrashScreenContent.Unreadable
        CrashScreenContent.load(directory, ".") shouldBe CrashScreenContent.Unreadable
        CrashScreenContent.load(directory, File(root, "secret.txt").absolutePath) shouldBe CrashScreenContent.Unreadable
        CrashScreenContent.load(root, "sub/inner.txt") shouldBe CrashScreenContent.Unreadable
    }

    @Test
    fun `a directory under the name is not a log`() {
        File(directory, "crash-1700000000000.txt").mkdirs()

        CrashScreenContent.load(directory, "crash-1700000000000.txt") shouldBe CrashScreenContent.Unreadable
    }
}
