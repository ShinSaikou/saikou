package com.shinsaikou.extensions.loader

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * An update writes the new `.ext` file beside the old one and renames it into place, so a copy
 * that fails leaves the working extension where it was.
 */
class ExtensionFilesTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a copy replaces the target and leaves it read-only, with no partial file behind`() {
        val target = File(dir, "exts/fixture.ext").apply { parentFile.mkdirs(); writeText("old") }
        val source = File(dir, "download.apk").apply { writeText("new") }

        source.copyAndSetReadOnlyTo(target)

        target.readText() shouldBe "new"
        target.canWrite() shouldBe false
        File(target.parentFile, "fixture.ext$PARTIAL_SUFFIX").exists() shouldBe false
    }

    @Test
    fun `a copy that fails leaves the installed file as it was`() {
        val target = File(dir, "exts/fixture.ext").apply { parentFile.mkdirs(); writeText("working") }
        // A directory exists but cannot be opened for reading, so the copy fails part way.
        val unreadable = File(dir, "download.apk").apply { mkdirs() }

        shouldThrowAny { unreadable.copyAndSetReadOnlyTo(target) }

        target.readText() shouldBe "working"
        File(target.parentFile, "fixture.ext$PARTIAL_SUFFIX").exists() shouldBe false
    }
}
