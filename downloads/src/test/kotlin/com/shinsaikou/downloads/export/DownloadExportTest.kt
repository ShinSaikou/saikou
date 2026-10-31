package com.shinsaikou.downloads.export

import android.text.TextUtils
import com.hippo.unifile.UniFile
import com.shinsaikou.domain.storage.FolderCopy
import com.shinsaikou.downloads.DownloadRoot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.measureTime

/**
 * Export downloads (F4): the app's download tree copied into the storage location's `downloads`
 * folder in the same `<source>/<manga>/<chapter>` layout, chapters still downloading left out, files
 * already there with the same size skipped, a stopped run finished by the next, the downloads left
 * where they are.
 */
class DownloadExportTest {

    @TempDir
    lateinit var temp: File

    private val downloads: File get() = File(temp, "files/downloads")
    private val exported: File get() = File(temp, "tree/downloads").apply { mkdirs() }
    private val target: UniFile get() = UniFile.fromFile(exported)!!

    private val root = object : DownloadRoot {
        override fun root(): File = downloads
        override fun sourceDir(sourceName: String): File = File(downloads, sourceName)
        override fun mangaDir(sourceName: String, mangaTitle: String): File = File(sourceDir(sourceName), mangaTitle)
        override fun availableBytes(): Long = -1
    }
    private val export = DownloadExport(root)

    /** UniFile's raw file guards every name with TextUtils.isEmpty, a platform stub on the JVM. */
    @BeforeEach
    fun stubTextUtils() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    }

    @AfterEach
    fun unstub() {
        unmockkStatic(TextUtils::class)
    }

    private fun write(path: String, text: String = "page") =
        File(downloads, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun File.paths(): List<String> =
        walkTopDown().filter { it.isFile }.map { it.relativeTo(this).invariantSeparatorsPath }.sorted().toList()

    @Test
    fun `the export has upstream's layout, leaves out chapters still downloading, and keeps the downloads`() = runTest {
        write("Source A (EN)/Series One/Chapter 1/001.jpg")
        write("Source A (EN)/Series One/Chapter 1/002.jpg")
        write("Source A (EN)/Series One/Chapter 2.cbz", "zip")
        write("Source A (EN)/Series One/Chapter 3_tmp/001.jpg")
        write("Source A (EN)/Series One/Chapter 4.cbz_tmp", "partial")

        val result = export.run(target)

        result.copied shouldBe 3
        exported.paths() shouldContainExactly listOf(
            "Source A (EN)/Series One/Chapter 1/001.jpg",
            "Source A (EN)/Series One/Chapter 1/002.jpg",
            "Source A (EN)/Series One/Chapter 2.cbz",
        )
        downloads.paths().size shouldBe 5
    }

    @Test
    fun `a second export copies only what is new or of another size`() = runTest {
        write("S/M/Chapter 1/001.jpg")
        export.run(target)
        write("S/M/Chapter 2/001.jpg")
        File(exported, "S/M/Chapter 1/001.jpg").writeText("cut")

        val second = export.run(target)

        second shouldBe FolderCopy.Result(copied = 2, skipped = 0, failed = 0, bytesCopied = 8)
        export.run(target) shouldBe FolderCopy.Result(copied = 0, skipped = 2, failed = 0, bytesCopied = 0)
    }

    @Test
    fun `a stopped export is finished by the next run`() = runTest {
        (1..5).forEach { write("S/M/Chapter $it/001.jpg") }

        shouldThrow<CancellationException> {
            export.run(target) { if (it.done == 2) throw CancellationException("stopped") }
        }
        exported.paths().size shouldBe 2

        val rest = export.run(target)

        rest.copied shouldBe 3
        rest.skipped shouldBe 2
        exported.paths().size shouldBe 5
    }

    /**
     * The 40,000 rule's figure for the brief: 1,000 chapters of 20 small pages each into a plain
     * folder, then the same again with everything there. Printed, not asserted, since the host is
     * shared; the report quotes it.
     */
    @Test
    fun `cost of 1,000 chapters`() = runTest {
        val page = "x".repeat(1024)
        (1..1000).forEach { chapter -> (1..20).forEach { write("S/M ${chapter / 100}/Chapter $chapter/%03d.jpg".format(it), page) } }

        val first: FolderCopy.Result
        val copyTime = measureTime { first = export.run(target) }
        val second: FolderCopy.Result
        val skipTime = measureTime { second = export.run(target) }

        first.copied shouldBe 20_000
        second.skipped shouldBe 20_000
        println("EXPORT_COST chapters=1000 files=20000 bytes=${first.bytesCopied} copy_ms=${copyTime.inWholeMilliseconds} rerun_ms=${skipTime.inWholeMilliseconds}")
    }
}
