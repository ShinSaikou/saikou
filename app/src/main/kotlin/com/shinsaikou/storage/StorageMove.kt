package com.shinsaikou.storage

import com.hippo.unifile.UniFile
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.backup.export.BackupFileNames
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.FolderCopy
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.domain.storage.StorageTrees
import com.shinsaikou.source.SourceIds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.URLDecoder

/**
 * What choosing a storage location moves into it (units storage-location and
 * storage-move-between-folders): the kept automatic and sync safety backups of `filesDir/backups`
 * into `<location>/autobackup`, every Local series of the app's Local folder into `<location>/local`,
 * and, when a location was set before, that previous location's `autobackup`, `local` and
 * `downloads` folders into the new location's folders of the same names. A manual export or any
 * other file the app did not name stays.
 *
 * The app folders are copied file by file through [FolderCopy] (copy, check, then delete). Each
 * top-level child of a previous location's folder (a backup file, a Local series, a source folder of
 * the export) is first moved whole through [StorageTrees.move], a rename when both trees are on one
 * storage; one that does not move is copied file by file under the same contract. The previous
 * location is kept ([StoragePreferences.previousStorageLocation]) with its grant until its folders
 * hold nothing the app named, so a run that left files behind is finished by the next.
 *
 * A Local entry in the library is keyed by its series folder's name, and its chapters by
 * `<series folder>/<chapter file>` (`LocalSource`), never by the folder they sit in, so the library
 * finds every moved series and chapter under the same keys. Its cover is the one thing named by a
 * full address, the cover file's URI in the old folder, so a moved library entry's cover is pointed
 * at the same file in its new place ([relinkLocalCovers]); until then its cover would draw blank.
 */
@Inject
@SingleIn(AppScope::class)
class StorageMove(
    private val backupFiles: AutomaticBackupFiles,
    private val localFileSystem: LocalSourceFileSystem,
    private val storageLocation: StorageLocation,
    private val mangaRepository: MangaRepository,
    private val preferences: StoragePreferences,
    private val trees: StorageTrees,
    private val grants: StorageGrants,
) {

    /**
     * What a move would take: backup files, Local series (top-level folders), exported chapters (the
     * chapter folders and `.cbz` files of an old location's `downloads`), and their size in bytes.
     * [fromOldLocation] says some of it is in an old storage location rather than in the app, of
     * which [oldLocationBytes] are.
     */
    data class Pending(
        val backups: Int,
        val localSeries: Int,
        val exportedChapters: Int = 0,
        val bytes: Long = 0,
        val fromOldLocation: Boolean = false,
        val oldLocationBytes: Long = 0,
    ) {
        val isEmpty: Boolean get() = backups == 0 && localSeries == 0 && exportedChapters == 0
    }

    /**
     * What [run] did: every file of every source in [files] (a child moved whole counts once), and how
     * many of the failures are still in the previous location, named [previousName].
     */
    data class Moved(val files: FolderCopy.Result, val failedInPrevious: Int = 0, val previousName: String? = null)

    /** What is still to move: the app folders and, while one is kept, the previous location's folders. */
    fun pending(): Pending = count(previousUri()?.let { storageLocation.previousRoot() })

    /**
     * What choosing [uri] would move: the app folders and, when a location is set, can be reached and
     * is not [uri], that location's folders. An unreachable one cannot be counted, so it is left out,
     * and so is one that [uri] lies inside the folders of (or the reverse), which would move into itself.
     */
    fun pendingFor(uri: String): Pending {
        val current = preferences.storageLocation.get()
        val movable = current.isNotBlank() && current != uri && !StorageLocation.overlaps(current, uri)
        return count(if (movable) storageLocation.root() else null)
    }

    /**
     * Sets [uri] as the storage location. When [pending] (from [pendingFor]) counted files in the
     * location it replaces, that one is kept as the previous location with its grant until the move
     * empties it; otherwise its grant is given back at once, as before. An older previous location is
     * forgotten and its grant given back: the files left in it stay in that folder.
     */
    fun setLocation(uri: String, pending: Pending) {
        val current = preferences.storageLocation.get()
        val previous = preferences.previousStorageLocation.get()
        val keepCurrent = pending.fromOldLocation && current.isNotBlank() && current != uri
        preferences.storageLocation.set(uri)
        preferences.previousStorageLocation.set(if (keepCurrent) current else "")
        listOf(current, previous)
            .filter { it.isNotBlank() && it != uri && !(keepCurrent && it == current) }
            .distinct()
            .forEach(grants::release)
    }

    /**
     * Moves everything [pending] counts into the storage location's folders. Throws
     * [FileNotFoundException] when the location cannot be reached, having moved nothing. A previous
     * location that cannot be reached is left for a later run.
     */
    suspend fun run(onProgress: suspend (FolderCopy.Progress) -> Unit = {}): Moved {
        val autobackup = storageLocation.folder(StorageLocation.AUTOBACKUP)
        val local = storageLocation.folder(StorageLocation.LOCAL)
        if (autobackup == null || local == null) throw FileNotFoundException("The storage location cannot be reached")

        val previousUri = previousUri()
        val previous = previousUri?.let { storageLocation.previousRoot() }
        val oldFolders = previous?.let(::oldFolders).orEmpty().map { (name, folder) ->
            val target = storageLocation.folder(name) ?: throw FileNotFoundException("The storage location cannot be reached")
            OldFolder(name, folder, target)
        }

        // Whole children first: on one storage each is a rename, and one that moves needs no count of its files.
        var movedWhole = 0
        oldFolders.forEach { folder ->
            val there = trees.list(folder.target).mapTo(mutableSetOf()) { it.name }
            trees.list(folder.source).filter(folder.include).forEach { child ->
                currentCoroutineContext().ensureActive()
                if (child.name !in there && moveWhole(child, folder)) movedWhole++
            }
        }

        val backupDirectory = backupFiles.appDirectory()
        val localDirectory = localFileSystem.defaultDirectoryFile()
        val backupCount = FolderCopy.filesUnder(backupDirectory, ::isKeptBackup).size
        val appTotal = backupCount + FolderCopy.filesUnder(localDirectory).size
        val total = movedWhole + appTotal + oldFolders.sumOf { filesUnder(it.source, it.include) }
        if (movedWhole > 0) onProgress(FolderCopy.Progress(movedWhole, total))

        val backups = FolderCopy.copy(backupDirectory, autobackup, move = true, include = ::isKeptBackup) {
            onProgress(FolderCopy.Progress(movedWhole + it.done, total))
        }
        val series = FolderCopy.copy(localDirectory, local, move = true) {
            onProgress(FolderCopy.Progress(movedWhole + backupCount + it.done, total))
        }
        val tally = Tally(total, done = movedWhole + appTotal, onProgress)
        oldFolders.forEach { copyInParts(it.source, it.target, it.depth, it.include, tally) }
        val fromPrevious = tally.results

        val oldLocal = oldFolders.firstOrNull { it.name == StorageLocation.LOCAL }?.source
        relinkLocalCovers(listOfNotNull(CoverRoot(localDirectory), oldLocal?.let(::CoverRoot)), local)
        if (previous != null && previousUri != null) finishPrevious(previous, previousUri)

        val results = listOf(backups, series) + fromPrevious
        return Moved(
            files = FolderCopy.Result(
                copied = movedWhole + results.sumOf { it.copied },
                skipped = results.sumOf { it.skipped },
                failed = results.sumOf { it.failed },
                bytesCopied = results.sumOf { it.bytesCopied },
            ),
            failedInPrevious = fromPrevious.sumOf { it.failed },
            previousName = storageLocation.previousDisplayName(),
        )
    }

    /** The previous location's URI while it is kept and is not the location itself. */
    private fun previousUri(): String? = preferences.previousStorageLocation.get()
        .takeIf { it.isNotBlank() && it != preferences.storageLocation.get() }

    /**
     * One folder of the previous location, the new location's folder of the same name, what of it
     * moves, and how many levels down [copyInParts] splits it: a Local series, an entry of the export.
     */
    private class OldFolder(val name: String, val source: UniFile, val target: UniFile) {
        val include: (StorageTrees.Entry) -> Boolean =
            if (name == StorageLocation.AUTOBACKUP) { entry -> BackupFileNames.kindOf(entry.name) != null } else { _ -> true }
        val depth: Int = when (name) {
            StorageLocation.LOCAL -> 1
            StorageLocation.DOWNLOADS -> 2
            else -> 0
        }
    }

    /** What the copies of one run have handled, toward one total across them. */
    private class Tally(val total: Int, var done: Int, val onProgress: suspend (FolderCopy.Progress) -> Unit) {
        val results = mutableListOf<FolderCopy.Result>()
    }

    /**
     * Copies what is left of [source] into [target] one folder [depth] levels down at a time, then the
     * files at this level, so the file list [FolderCopy] holds at once is one Local series' or one
     * export entry's, never the whole export's (a few hundred bytes a file). An emptied folder is
     * deleted; one still holding a file that failed stays.
     */
    private suspend fun copyInParts(
        source: UniFile,
        target: UniFile,
        depth: Int,
        include: (StorageTrees.Entry) -> Boolean,
        tally: Tally,
    ) {
        val split = mutableSetOf<String>()
        if (depth > 0) {
            trees.list(source).filter { it.isDirectory && include(it) }.forEach { part ->
                // A folder that cannot be made is left to the pass below, which counts each of its files as failed.
                val partTarget = StorageLocation.folderIn(target, part.name) ?: return@forEach
                split += part.name
                copyInParts(part.file, partTarget, depth - 1, { true }, tally)
                if (trees.list(part.file).isEmpty()) part.file.delete()
            }
        }
        val base = tally.done
        val rest = FolderCopy.copy(
            source,
            target,
            move = true,
            trees = trees,
            include = { entry -> !(entry.isDirectory && entry.name in split) && include(entry) },
        ) { tally.onProgress(FolderCopy.Progress(base + it.done, tally.total)) }
        tally.done += rest.total
        tally.results += rest
    }

    private fun oldFolders(root: UniFile): List<Pair<String, UniFile>> {
        val folders = trees.list(root).filter { it.isDirectory }.associateBy { it.name }
        return StorageLocation.FOLDERS.mapNotNull { name -> folders[name]?.let { name to it.file } }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun moveWhole(child: StorageTrees.Entry, folder: OldFolder): Boolean = try {
        trees.move(child.file, folder.source, folder.target)
    } catch (e: Exception) {
        logcat(LogPriority.INFO, e) { "${child.name} could not be moved whole; it is copied file by file" }
        false
    }

    private fun filesUnder(folder: UniFile, include: (StorageTrees.Entry) -> Boolean): Int =
        trees.list(folder).filter(include).sumOf { if (it.isDirectory) filesUnder(it.file) { true } else 1 }

    /** The app folders' counts, plus [old]'s three folders when one is given. */
    private fun count(old: UniFile?): Pending {
        val backups = backupFiles.appFiles()
        val localDirectory = localFileSystem.defaultDirectoryFile()
        val localSeries = localDirectory.listFiles().orEmpty().count { it.isDirectory && !it.name.startsWith(".") }
        val appBytes = backups.sumOf { it.length() } + FolderCopy.filesUnder(localDirectory).sumOf { it.length() }
        val inOld = old?.let(::countOld) ?: Pending(0, 0)
        return Pending(
            backups = backups.size + inOld.backups,
            localSeries = localSeries + inOld.localSeries,
            exportedChapters = inOld.exportedChapters,
            bytes = appBytes + inOld.bytes,
            fromOldLocation = !inOld.isEmpty,
            oldLocationBytes = inOld.bytes,
        )
    }

    /** An old location's kept backups, Local series and exported chapters, one listing per folder. */
    private fun countOld(root: UniFile): Pending {
        val folders = oldFolders(root).toMap()
        val backups = folders[StorageLocation.AUTOBACKUP]?.let(trees::list).orEmpty()
            .filter { !it.isDirectory && BackupFileNames.kindOf(it.name) != null }
        val local = folders[StorageLocation.LOCAL]?.let(trees::list).orEmpty()
        var chapters = 0
        // The export's layout is <source>/<entry>/<chapter folder or .cbz>, so a chapter is three levels down.
        val exportBytes = folders[StorageLocation.DOWNLOADS]?.let { downloads ->
            measure(downloads) { entry, level ->
                if (level == CHAPTER_LEVEL && (entry.isDirectory || entry.name.endsWith(".cbz"))) chapters++
            }
        } ?: 0L
        return Pending(
            backups = backups.size,
            localSeries = local.count { it.isDirectory && !it.name.startsWith(".") },
            exportedChapters = chapters,
            bytes = backups.sumOf { it.length } +
                local.sumOf { if (it.isDirectory) measure(it.file) else it.length } +
                exportBytes,
        )
    }

    /** The size of every file under [folder]; [onEntry] hears each entry with its depth, 1 for [folder]'s children. */
    private fun measure(folder: UniFile, level: Int = 1, onEntry: (StorageTrees.Entry, Int) -> Unit = { _, _ -> }): Long =
        trees.list(folder).sumOf { entry ->
            onEntry(entry, level)
            if (entry.isDirectory) measure(entry.file, level + 1, onEntry) else entry.length
        }

    /**
     * Once the previous location's folders hold nothing the app named, its emptied folders are
     * deleted, its grant given back and it is forgotten. Otherwise both stay, and [pending] counts
     * what is left for the row that moves it.
     */
    private fun finishPrevious(previous: UniFile, uri: String) {
        if (!countOld(previous).isEmpty) return
        oldFolders(previous).forEach { (_, folder) ->
            if (trees.list(folder).isEmpty()) folder.delete()
        }
        grants.release(uri)
        preferences.previousStorageLocation.set("")
    }

    /**
     * An old Local folder whose files covers may be named under: the app's own, by file path, or a
     * previous location's, by its tree URI.
     */
    private class CoverRoot(val relative: (String?) -> String?, val stillHas: (String) -> Boolean) {
        constructor(root: File) : this({ relativeCoverPath(it, root) }, { File(root, it).exists() })
        constructor(root: UniFile) : this({ relativeCoverPath(it, root.uri.toString()) }, { find(root, it) != null })
    }

    /**
     * Points each Local library entry whose cover is a file under one of [oldRoots] at that file under
     * [newRoot], once it is there. A cover still in the old folder (its move failed) keeps its address.
     */
    private suspend fun relinkLocalCovers(oldRoots: List<CoverRoot>, newRoot: UniFile) {
        val updates = mangaRepository.getFavorites()
            .filter { it.source == SourceIds.LOCAL }
            .mapNotNull { manga ->
                val (root, relative) = oldRoots.firstNotNullOfOrNull { root ->
                    root.relative(manga.thumbnailUrl)?.let { root to it }
                } ?: return@mapNotNull null
                if (root.stillHas(relative)) return@mapNotNull null
                val moved = find(newRoot, relative)?.takeIf { it.isFile } ?: return@mapNotNull null
                MangaUpdate(id = manga.id, thumbnailUrl = moved.uri.toString())
            }
        if (updates.isNotEmpty()) mangaRepository.updateAll(updates)
    }

    internal companion object {
        private const val CHAPTER_LEVEL = 3

        /** A top-level file the app named; no folder of `backups` is entered. */
        fun isKeptBackup(file: File): Boolean = file.isFile && BackupFileNames.kindOf(file.name) != null

        /** `Series/cover.jpg` under [root], or null. */
        fun find(root: UniFile, relative: String): UniFile? =
            relative.split('/').fold<String, UniFile?>(root) { folder, name -> folder?.findFile(name) }

        /** `file:///<oldRoot>/Series/cover.jpg` (or the bare path) is `Series/cover.jpg`; null for anything else. */
        fun relativeCoverPath(thumbnailUrl: String?, oldRoot: File): String? {
            val url = thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
            val path = when {
                url.startsWith("file:") -> runCatching { URI(url).path }.getOrNull()
                url.startsWith("/") -> url
                else -> null
            } ?: return null
            val root = oldRoot.path.trimEnd('/') + "/"
            return path.takeIf { it.startsWith(root) }?.removePrefix(root)?.takeIf { it.isNotEmpty() }
        }

        /**
         * The same for a folder named by a URI: a `file:` one by its path, a tree document by its
         * document id, so `.../tree/T/document/primary%3ASaikou%2Flocal%2FSeries%2Fcover.jpg` under
         * `.../tree/T/document/primary%3ASaikou%2Flocal` is `Series/cover.jpg`. A provider whose ids are
         * not paths gives null, and that cover refreshes when its entry is opened.
         */
        fun relativeCoverPath(thumbnailUrl: String?, rootUri: String): String? {
            if (rootUri.startsWith("file:")) {
                val root = runCatching { File(URI(rootUri).path) }.getOrNull() ?: return null
                return relativeCoverPath(thumbnailUrl, root)
            }
            val url = thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
            val (rootTree, rootId) = documentOf(rootUri) ?: return null
            val (tree, id) = documentOf(url) ?: return null
            if (tree != rootTree) return null
            val prefix = rootId.trimEnd('/') + "/"
            return id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
        }

        /** `content://a/tree/T/document/D` as (`content://a/tree/T`, D decoded). */
        private fun documentOf(uri: String): Pair<String, String>? {
            val at = uri.indexOf(DOCUMENT_SEGMENT).takeIf { it >= 0 } ?: return null
            val id = runCatching {
                URLDecoder.decode(uri.substring(at + DOCUMENT_SEGMENT.length), Charsets.UTF_8.name())
            }.getOrNull() ?: return null
            return uri.substring(0, at) to id
        }

        private const val DOCUMENT_SEGMENT = "/document/"
    }
}
