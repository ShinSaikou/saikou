package com.shinsaikou.core.common.crash

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The crash screen's request reaches exactly one start, the one after it, and the start after that
 * one, with extensions again, is told so once.
 */
class StartModeTest {

    @Test
    fun `a start with no request loads extensions and follows nothing`(@TempDir filesDir: File) {
        val mode = StartMode.take(StartMode.filesOf(contextIn(filesDir)))

        mode.withoutExtensions shouldBe false
        mode.afterWithoutExtensions shouldBe false
    }

    @Test
    fun `a request turns off the next start only, and the start after it is told once`(@TempDir filesDir: File) {
        val context = contextIn(filesDir)
        StartMode.requestWithoutExtensions(context)

        val without = StartMode.take(StartMode.filesOf(context))
        without.withoutExtensions shouldBe true
        StartMode.filesOf(context).flag.exists() shouldBe false

        val after = StartMode.take(StartMode.filesOf(context))
        after.withoutExtensions shouldBe false
        after.afterWithoutExtensions shouldBe true

        val later = StartMode.take(StartMode.filesOf(context))
        later.withoutExtensions shouldBe false
        later.afterWithoutExtensions shouldBe false
    }

    @Test
    fun `two requests before a start still turn off only that start`(@TempDir filesDir: File) {
        val context = contextIn(filesDir)
        StartMode.requestWithoutExtensions(context)
        StartMode.requestWithoutExtensions(context)

        StartMode.take(StartMode.filesOf(context)).withoutExtensions shouldBe true
        StartMode.take(StartMode.filesOf(context)).withoutExtensions shouldBe false
    }

    @Test
    fun `the files live outside the crash directory, whose files are all crashes`(@TempDir filesDir: File) {
        val files = StartMode.filesOf(contextIn(filesDir))

        files.flag.parentFile shouldBe filesDir
        files.after.parentFile shouldBe filesDir
    }

    private fun contextIn(filesDir: File): Context = mockk { every { this@mockk.filesDir } returns filesDir }
}
