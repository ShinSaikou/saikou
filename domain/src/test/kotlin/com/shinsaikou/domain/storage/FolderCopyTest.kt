package com.shinsaikou.domain.storage

import com.hippo.unifile.UniFile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.measureTime

/**
 * The file-by-file copy behind the storage location's move and the downloads export: paths kept,
 * copy before delete, a file of the same size skipped so a rerun does only what is left, and a
 * failure or a stop leaving every file in one place or both. The same from a folder of a picked tree
 * (a [UniFile] source, unit storage-move-between-folders), opened here as a raw folder.
 */
class FolderCopyTest {

    @TempDir
    lateinit var temp: File

    private val source: File get() = File(temp, "app")
    private val targetDir: File get() = File(temp, "tree")
    private val target: UniFile get() = UniFile.fromFile(targetDir.apply { mkdirs() })!!

    private fun write(path: String, text: String): File =
        File(source, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun File.paths(): List<String> =
        walkTopDown().filter { it.isFile }.map { it.relativeTo(this).invariantSeparatorsPath }.sorted().toList()

    @Test
    fun `a move copies every file under its path, then deletes the source and its empty folders`() = runTest {
        write("One/ch1/001.jpg", "aaa")
        write("One/ch1/002.jpg", "bbbb")
        write("Two/ch1.cbz", "zip")

        val result = FolderCopy.copy(source, target, move = true)

        result shouldBe FolderCopy.Result(copied = 3, skipped = 0, failed = 0, bytesCopied = 10)
        targetDir.paths() shouldContainExactly listOf("One/ch1/001.jpg", "One/ch1/002.jpg", "Two/ch1.cbz")
        File(targetDir, "One/ch1/002.jpg").readText() shouldBe "bbbb"
        source.paths() shouldBe emptyList()
        source.listFiles()!!.toList() shouldBe emptyList()
    }

    @Test
    fun `a copy leaves the source in place`() = runTest {
        write("src/One/ch1/001.jpg", "aaa")

        FolderCopy.copy(source, target, move = false)

        source.paths() shouldContainExactly listOf("src/One/ch1/001.jpg")
        targetDir.paths() shouldContainExactly listOf("src/One/ch1/001.jpg")
    }

    @Test
    fun `a file already there with the same size is skipped, and one of another size is replaced`() = runTest {
        write("One/001.jpg", "same")
        write("One/002.jpg", "whole file")
        File(targetDir, "One").mkdirs()
        File(targetDir, "One/001.jpg").writeText("SAME")
        File(targetDir, "One/002.jpg").writeText("whole file and a longer leftover")

        val result = FolderCopy.copy(source, target, move = false)

        result.copied shouldBe 1
        result.skipped shouldBe 1
        File(targetDir, "One/001.jpg").readText() shouldBe "SAME"
        File(targetDir, "One/002.jpg").readText() shouldBe "whole file"
    }

    @Test
    fun `a move stopped midway is finished by a rerun, which skips what is already there`() = runTest {
        write("a.tachibk", "1")
        write("b.tachibk", "22")
        write("c.tachibk", "333")

        shouldThrow<CancellationException> {
            FolderCopy.copy(source, target, move = true) { progress ->
                if (progress.done == 1) throw CancellationException("stopped")
            }
        }
        source.paths() shouldContainExactly listOf("b.tachibk", "c.tachibk")
        targetDir.paths() shouldContainExactly listOf("a.tachibk")

        // As if the next file had been written but its source not yet deleted.
        File(targetDir, "b.tachibk").writeText("22")
        val rerun = FolderCopy.copy(source, target, move = true)

        rerun shouldBe FolderCopy.Result(copied = 1, skipped = 1, failed = 0, bytesCopied = 3)
        source.paths() shouldBe emptyList()
        targetDir.paths() shouldContainExactly listOf("a.tachibk", "b.tachibk", "c.tachibk")
    }

    @Test
    fun `a file that fails keeps its source, the others move, and a rerun moves it`() = runTest {
        write("a.tachibk", "1")
        val unreadable = write("b.tachibk", "22").apply { setReadable(false) }
        write("c.tachibk", "333")

        val result = FolderCopy.copy(source, target, move = true)

        result.failed shouldBe 1
        result.copied shouldBe 2
        source.paths() shouldContainExactly listOf("b.tachibk")
        unreadable.setReadable(true)
        unreadable.readText() shouldBe "22"

        FolderCopy.copy(source, target, move = true).copied shouldBe 1
        File(targetDir, "b.tachibk").readText() shouldBe "22"
        source.paths() shouldBe emptyList()
    }

    @Test
    fun `a folder the filter refuses is not entered`() = runTest {
        write("One/ch1/001.jpg", "a")
        write("One/ch2_tmp/001.jpg", "b")

        FolderCopy.copy(source, target, move = false, include = { !it.name.endsWith("_tmp") })

        targetDir.paths() shouldContainExactly listOf("One/ch1/001.jpg")
    }

    @Test
    fun `progress counts every file against the total`() = runTest {
        write("a", "1")
        write("b/c", "2")
        val seen = mutableListOf<FolderCopy.Progress>()

        FolderCopy.copy(source, target, move = false) { seen += it }

        seen shouldContainExactly listOf(FolderCopy.Progress(1, 2), FolderCopy.Progress(2, 2))
    }

    private val treeSource: UniFile get() = UniFile.fromFile(source.apply { mkdirs() })!!

    @Test
    fun `a move from a tree copies every file under its path, then deletes the source and its empty folders`() = runTest {
        write("One/ch1/001.jpg", "aaa")
        write("One/ch1/002.jpg", "bbbb")
        write("Two/ch1.cbz", "zip")

        val result = FolderCopy.copy(treeSource, target, move = true)

        result shouldBe FolderCopy.Result(copied = 3, skipped = 0, failed = 0, bytesCopied = 10)
        targetDir.paths() shouldContainExactly listOf("One/ch1/001.jpg", "One/ch1/002.jpg", "Two/ch1.cbz")
        File(targetDir, "Two/ch1.cbz").readText() shouldBe "zip"
        source.listFiles()!!.toList() shouldBe emptyList()
    }

    @Test
    fun `from a tree, a file already there is skipped, one that fails keeps its source, and a rerun moves it`() = runTest {
        write("a.tachibk", "1")
        val unreadable = write("b.tachibk", "22").apply { setReadable(false) }
        write("c.tachibk", "333")
        File(targetDir, "c.tachibk").apply { parentFile!!.mkdirs(); writeText("333") }

        val result = FolderCopy.copy(treeSource, target, move = true)

        result shouldBe FolderCopy.Result(copied = 1, skipped = 1, failed = 1, bytesCopied = 1)
        source.paths() shouldContainExactly listOf("b.tachibk")
        unreadable.setReadable(true)

        FolderCopy.copy(treeSource, target, move = true).copied shouldBe 1
        File(targetDir, "b.tachibk").readText() shouldBe "22"
        source.paths() shouldBe emptyList()
    }

    @Test
    fun `from a tree, a folder the filter refuses is not entered and stays`() = runTest {
        write("auto.tachibk", "1")
        write("Folder/inner.tachibk", "2")

        FolderCopy.copy(treeSource, target, move = true, include = { !it.isDirectory })

        targetDir.paths() shouldContainExactly listOf("auto.tachibk")
        source.paths() shouldContainExactly listOf("Folder/inner.tachibk")
    }

    /**
     * The 40,000 paragraph's copy rate (unit storage-move-between-folders): 1,000 files of 64 KiB
     * from a tree source into a plain folder, moved, so each is read, written, size-checked and
     * deleted. Printed, not asserted, since the host is shared; the report quotes it.
     */
    @Test
    fun `cost of moving 1,000 files from a tree`() = runTest {
        val content = "x".repeat(64 * 1024)
        (1..1000).forEach { write("Source ${it / 100}/Entry ${it / 10}/Chapter $it.cbz", content) }

        val result: FolderCopy.Result
        val time = measureTime { result = FolderCopy.copy(treeSource, target, move = true) }

        result.copied shouldBe 1000
        source.paths() shouldBe emptyList()
        val megabytes = result.bytesCopied / (1024.0 * 1024.0)
        val rate = megabytes / (time.inWholeMilliseconds.coerceAtLeast(1) / 1000.0)
        println("TREE_MOVE_COST files=1000 bytes=${result.bytesCopied} ms=${time.inWholeMilliseconds} mb_per_s=${"%.1f".format(rate)}")
    }
}
