package com.shinsaikou.domain.storage

import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Copies a folder into a folder of the storage location, file by file, keeping the relative paths:
 * the move of automatic backups and Local series when a storage location is chosen ([move] true),
 * the move of the previous location's folders into a new one (a [UniFile] source), and the export of
 * the downloads ([move] false).
 *
 * A file already in the target with the same size is skipped, so a run cut short and run again does
 * only what is left. Otherwise the file is written whole (a target of another size, the remains of an
 * interrupted write, is replaced) and its size checked. Only then, and only for a move, is the source
 * file deleted, so a stop or a failure at any point leaves the file in one place or both, never in
 * neither. A file that fails is counted and its source kept; the run goes on with the next.
 *
 * Each target folder is listed once and remembered, since a storage-access tree answers every
 * `findFile` with a listing of the whole folder.
 */
object FolderCopy {

    data class Progress(val done: Int, val total: Int)

    data class Result(val copied: Int, val skipped: Int, val failed: Int, val bytesCopied: Long) {
        val total: Int get() = copied + skipped + failed
    }

    /**
     * Copies every file under [source] that [include] keeps (a folder it refuses is not entered) into
     * [target]. [onProgress] hears each file handled; a cancel between two files ends the run with
     * nothing half done.
     */
    suspend fun copy(
        source: File,
        target: UniFile,
        move: Boolean,
        include: (File) -> Boolean = { true },
        onProgress: suspend (Progress) -> Unit = {},
    ): Result = copy(FileSource(source), target, move, include, onProgress)

    /**
     * [copy] from a folder of a picked tree: the same contract, through the provider. For the
     * children of the previous storage location that could not be moved whole. Each source folder
     * is read once through [trees] (kind and size in one listing), and [include] sees those entries.
     */
    suspend fun copy(
        source: UniFile,
        target: UniFile,
        move: Boolean,
        trees: StorageTrees = PlainTrees,
        include: (StorageTrees.Entry) -> Boolean = { true },
        onProgress: suspend (Progress) -> Unit = {},
    ): Result = copy(TreeSource(StorageTrees.Entry(source, source.name.orEmpty(), true, 0L), trees), target, move, include, onProgress)

    /** Every file [copy] would take from [source], in a stable order; for a count before the run. */
    fun filesUnder(source: File, include: (File) -> Boolean = { true }): List<File> =
        walk(FileSource(source), include).map { it.second.handle }

    private suspend fun <T> copy(
        source: Source<T>,
        target: UniFile,
        move: Boolean,
        include: (T) -> Boolean,
        onProgress: suspend (Progress) -> Unit,
    ): Result {
        val files = walk(source, include)
        val folders = TargetFolders(target)
        var copied = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        files.forEachIndexed { index, (relative, file) ->
            currentCoroutineContext().ensureActive()
            try {
                when (val outcome = copyOne(file, relative, folders)) {
                    is Outcome.Copied -> {
                        copied++
                        bytes += outcome.bytes
                    }
                    Outcome.Skipped -> skipped++
                }
                if (move && !file.delete()) logcat(LogPriority.WARN) { "A moved file could not be deleted: $relative" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "A file could not be copied: $relative" }
                failed++
            }
            onProgress(Progress(done = index + 1, total = files.size))
        }
        if (move) deleteEmptyFolders(source)
        return Result(copied, skipped, failed, bytes)
    }

    /** Every file under [source] with its path relative to it, sorted by name at each level. */
    private fun <T> walk(source: Source<T>, include: (T) -> Boolean): List<Pair<String, Source<T>>> {
        if (!source.isDirectory) return emptyList()
        val out = mutableListOf<Pair<String, Source<T>>>()
        fun walk(folder: Source<T>, path: String) {
            folder.children().sortedBy { it.name }.forEach { child ->
                if (!include(child.handle)) return@forEach
                val relative = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (child.isDirectory) walk(child, relative) else if (child.isFile) out += relative to child
            }
        }
        walk(source, "")
        return out
    }

    private sealed interface Outcome {
        class Copied(val bytes: Long) : Outcome
        data object Skipped : Outcome
    }

    private fun copyOne(file: Source<*>, relative: String, folders: TargetFolders): Outcome {
        val parentPath = relative.substringBeforeLast('/', missingDelimiterValue = "")
        val name = relative.substringAfterLast('/')
        val size = file.length()
        val folder = folders.folder(parentPath) ?: throw IOException("The target folder cannot be made: $parentPath")
        val existing = folders.child(parentPath, name)
        if (existing != null && existing.isFile && existing.length() == size) return Outcome.Skipped
        // Some providers open "w" without truncating, so a longer leftover is deleted, not overwritten.
        if (existing != null && !existing.delete()) throw IOException("A partial copy cannot be replaced: $relative")
        val created = folder.createFile(name) ?: throw IOException("The target file cannot be made: $relative")
        folders.remember(parentPath, name, created)
        (created.openOutputStream() ?: throw IOException("The target file cannot be written: $relative")).use { out ->
            file.openInputStream().use { it.copyTo(out) }
        }
        val written = created.length()
        // A provider that does not know a size reports 0; trust the stream then, as upstream does.
        if (written > 0 && written != size) throw IOException("The copy of $relative has the wrong size")
        return Outcome.Copied(size)
    }

    private fun deleteEmptyFolders(folder: Source<*>) {
        folder.children().filter { it.isDirectory }.forEach { child ->
            deleteEmptyFolders(child)
            if (child.children().isEmpty()) child.delete()
        }
    }

    /**
     * What [copy] reads: a java.io file of app storage or a document of a picked tree, wrapping
     * [handle], which the caller's filter sees.
     */
    private interface Source<T> {
        val handle: T
        val name: String
        val isDirectory: Boolean
        val isFile: Boolean
        fun length(): Long
        fun children(): List<Source<T>>
        fun openInputStream(): InputStream
        fun delete(): Boolean
    }

    private class FileSource(override val handle: File) : Source<File> {
        override val name: String get() = handle.name
        override val isDirectory: Boolean get() = handle.isDirectory
        override val isFile: Boolean get() = handle.isFile
        override fun length(): Long = handle.length()
        override fun children(): List<Source<File>> = handle.listFiles().orEmpty().map(::FileSource)
        override fun openInputStream(): InputStream = handle.inputStream()
        override fun delete(): Boolean = handle.delete()
    }

    /** A document as its folder's listing described it; only reading, deleting and listing ask the provider again. */
    private class TreeSource(override val handle: StorageTrees.Entry, private val trees: StorageTrees) : Source<StorageTrees.Entry> {
        override val name: String get() = handle.name
        override val isDirectory: Boolean get() = handle.isDirectory
        override val isFile: Boolean get() = !handle.isDirectory
        override fun length(): Long = handle.length
        override fun children(): List<Source<StorageTrees.Entry>> =
            trees.list(handle.file).filter { it.name.isNotEmpty() }.map { TreeSource(it, trees) }
        override fun openInputStream(): InputStream =
            handle.file.openInputStream() ?: throw IOException("The file cannot be read: ${handle.name}")
        override fun delete(): Boolean = handle.file.delete()
    }

    /** Lists through UniFile alone, for a caller with no [StorageTrees] of its own. */
    private object PlainTrees : StorageTrees {
        override fun open(uri: String): UniFile? = null
    }

    /** The target's folders by relative path, made on first use, each listed at most once. */
    private class TargetFolders(private val root: UniFile) {
        private val folders = mutableMapOf<String, UniFile?>("" to root)
        private val listings = mutableMapOf<String, MutableMap<String, UniFile>>()

        fun folder(path: String): UniFile? = folders.getOrPut(path) {
            val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
            val name = path.substringAfterLast('/')
            val parent = folder(parentPath) ?: return@getOrPut null
            val existing = child(parentPath, name)
            when {
                existing == null -> parent.createDirectory(name)?.also { remember(parentPath, name, it) }
                existing.isDirectory -> existing
                else -> null
            }
        }

        fun child(path: String, name: String): UniFile? = listing(path)?.get(name)

        fun remember(path: String, name: String, file: UniFile) {
            listing(path)?.put(name, file)
        }

        private fun listing(path: String): MutableMap<String, UniFile>? {
            listings[path]?.let { return it }
            val folder = folder(path) ?: return null
            return folder.listFiles().orEmpty()
                .mapNotNull { child -> child.name?.let { it to child } }
                .toMap(mutableMapOf())
                .also { listings[path] = it }
        }
    }
}
