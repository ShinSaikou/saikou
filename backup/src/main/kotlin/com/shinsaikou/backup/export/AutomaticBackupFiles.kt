package com.shinsaikou.backup.export

import android.content.Context
import com.hippo.unifile.UniFile
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.StorageLocation
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Where an automatic export is written and what it is named: the storage location's `autobackup`
 * folder, else the older backup folder key if a value was written to it, else app-private storage,
 * holding the newest files named by [BackupFileNames.suggest], as many as the owner keeps. A storage
 * location that can no longer be reached (its folder deleted, its grant revoked) falls back to
 * app-private storage, so a backup is never lost for want of a folder; the Storage location row says
 * the folder cannot be reached. The automatic run of [BackupExportWorker] writes through [write].
 * Sync's backup before a backend's first pull (phase 3 unit G) writes through [createSync] into the
 * same folder under its own names ([BackupFileNames.suggestSync]), which the automatic rotation
 * never touches; [pruneSync] keeps the newest [BackupFileNames.SYNC_KEPT] of those. [list] is what
 * the Backup screen's Automatic backups shows.
 */
@Inject
class AutomaticBackupFiles(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
    private val storageLocation: StorageLocation,
) {

    /**
     * Writes one automatic file, named for [now], and returns it. [write] fills a file under
     * [BackupFileNames.pending]'s name, which takes its own name once [write] has returned (by a copy
     * where the folder's provider refuses renames); only then
     * are the oldest automatic files beyond the newest [keep], the new one among them, deleted. A
     * lower count than before deletes the extra files here, with the next backup. A write or a rename
     * that fails deletes its file and costs no older one. The next run deletes the file a killed run
     * left unfinished, all but a copy [copyUnder] was making, and gives back its name to a
     * same-minute file that run had set aside. The storage location is a tree URI, so the files are
     * handled through UniFile rather than java.io. With no location set the files stay app-private,
     * which needs no permission.
     * Rotation only ever looks at names [BackupFileNames.suggest] produces, so a manual export or a
     * safety backup in the same directory is never deleted.
     */
    suspend fun write(
        now: Long,
        keep: Int = BackupFileNames.MAX_KEPT,
        write: suspend (UniFile) -> Unit,
    ): UniFile = withContext(Dispatchers.IO) {
        val directory = directory()
        val name = BackupFileNames.suggest(now)
        // What a killed run left: its unfinished file goes, and a same-minute file it had set aside
        // takes its name back, or goes too when the new file had the name already.
        (directory.listFiles() ?: emptyArray()).forEach { leftover ->
            val leftoverName = leftover.name.orEmpty()
            val setAsideFrom = BackupFileNames.REPLACED_REGEX.matchEntire(leftoverName)?.groupValues?.get(1)
            when {
                BackupFileNames.PENDING_REGEX.matches(leftoverName) -> leftover.delete()
                setAsideFrom == null -> Unit
                directory.findFile(setAsideFrom) == null -> leftover.renameTo(setAsideFrom)
                else -> leftover.delete()
            }
        }

        val file = checkNotNull(directory.createFile(BackupFileNames.pending(now))) {
            "The backup file could not be created"
        }
        // A file named for the same minute is replaced, as writing over it used to, but only once
        // the new one has the name: until then it is set aside, and it takes the name back when the
        // new one cannot be named, whether naming it fails or throws.
        var setAside: UniFile? = null
        val named = try {
            write(file)
            directory.findFile(name)?.let { earlier ->
                check(earlier.renameTo(BackupFileNames.replaced(now))) {
                    "The earlier backup file could not be set aside"
                }
                setAside = earlier
            }
            // A provider without rename support refuses every rename, so the finished file is copied
            // under its name instead; otherwise every automatic backup in such a folder would fail.
            checkNotNull(file.takeIf { it.renameTo(name) } ?: copyUnder(directory, file, name)) {
                "The backup file could not be renamed"
            }
        } catch (e: Throwable) {
            file.delete()
            setAside?.renameTo(name)
            throw e
        }
        setAside?.delete()
        if (named !== file) file.delete()

        // Names sort chronologically by construction, so the newest are the last by name.
        (directory.listFiles() ?: emptyArray())
            .filter { BackupFileNames.AUTO_BACKUP_REGEX.matches(it.name.orEmpty()) && it.name != name }
            .sortedBy { it.name.orEmpty() }
            .dropLast(keep.coerceAtLeast(1) - 1)
            .forEach { it.delete() }
        named
    }

    /**
     * Copies [source] into a new file named [name] in [directory] and returns it, or null when the
     * copy cannot be made, in which case no part of it is left behind. It runs only where the
     * folder's provider refuses renames, which leaves no step that gives a whole file its name at
     * once: a process killed during the copy leaves part of a file under [name], which the list
     * shows, as a killed run did when every automatic file was written under its name. Failing
     * instead would fail every automatic backup in such a folder.
     */
    private fun copyUnder(directory: UniFile, source: UniFile, name: String): UniFile? {
        val target = directory.createFile(name) ?: return null
        return try {
            source.openInputStream().use { input -> target.openOutputStream().use { input.copyTo(it) } }
            target
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "The backup file could not be copied under its name" }
            target.delete()
            null
        }
    }

    /**
     * Creates the safety backup's file, named for [now] by [BackupFileNames.suggestSync], and deletes
     * nothing. UniFile hands back a file that already has the name, so [SyncBackupFile.existed] says
     * whether it did (a sign-in in the same minute as the one before it): the caller must never delete
     * such a file when its write fails.
     */
    suspend fun createSync(now: Long): SyncBackupFile = withContext(Dispatchers.IO) {
        val directory = directory()
        val name = BackupFileNames.suggestSync(now)
        val existed = (directory.listFiles() ?: emptyArray()).any { it.name == name }
        val file = checkNotNull(directory.createFile(name)) { "The backup file could not be created" }
        SyncBackupFile(file, existed)
    }

    /**
     * Deletes the safety backups beyond the newest [BackupFileNames.SYNC_KEPT], counting [keep] (the
     * one just completed) as one of them whatever its name sorts as. Called only once [keep] is
     * complete, so a failed write never costs an older file.
     */
    suspend fun pruneSync(keep: String): Unit = withContext(Dispatchers.IO) {
        (directory().listFiles() ?: emptyArray())
            .filter { BackupFileNames.SYNC_BACKUP_REGEX.matches(it.name.orEmpty()) && it.name != keep }
            .sortedBy { it.name.orEmpty() }
            .dropLast(BackupFileNames.SYNC_KEPT - 1)
            .forEach { it.delete() }
    }

    /**
     * Every kept file, newest first: those in the folder new files go to and, when that is not the
     * app-private folder, those still in it (written while the storage location could not be
     * reached, or never moved). Files the app did not name are not listed.
     */
    suspend fun list(): List<AutomaticBackup> = withContext(Dispatchers.IO) {
        val current = directory()
        val app = appDirectory()
        val folders = if (current.filePath == app.path) {
            listOf(current to true)
        } else {
            listOfNotNull(current to false, UniFile.fromFile(app)?.let { it to true })
        }
        folders
            .flatMap { (folder, isApp) -> (folder.listFiles() ?: emptyArray()).mapNotNull { it.toBackup(isApp) } }
            .sortedWith(compareByDescending<AutomaticBackup> { it.createdAt }.thenByDescending { it.name })
    }

    /** The kept file named [name], in either folder [list] reads, or null once it is gone. */
    suspend fun find(name: String): AutomaticBackup? = list().firstOrNull { it.name == name }

    /** The kept files in the app-private folder, which choosing a storage location moves into it. */
    fun appFiles(): List<File> =
        appDirectory().listFiles().orEmpty().filter { it.isFile && BackupFileNames.kindOf(it.name) != null }

    /** `filesDir/backups`, where the files live with no folder configured. */
    fun appDirectory(): File = File(context.filesDir, LOCAL_DIRECTORY)

    private fun directory(): UniFile {
        storageLocation.folder(StorageLocation.AUTOBACKUP)?.let { return it }
        if (storageLocation.status() == StorageLocation.Status.UNREACHABLE) {
            logcat(LogPriority.WARN) { "The storage location cannot be reached; automatic backups stay in the app" }
        }
        val configured = storagePreferences.backupDirectory.get()
        if (configured.isNotEmpty()) {
            storageLocation.openTree(configured)?.let { return it }
            logcat(LogPriority.WARN) { "The backup folder cannot be reached; automatic backups stay in the app" }
        }
        val directory = UniFile.fromFile(appDirectory().apply { mkdirs() })
        return checkNotNull(directory) { "The backup directory is no longer reachable" }
    }

    private fun UniFile.toBackup(inApp: Boolean): AutomaticBackup? {
        val name = name ?: return null
        val kind = BackupFileNames.kindOf(name) ?: return null
        if (!isFile) return null
        return AutomaticBackup(
            file = this,
            name = name,
            kind = kind,
            createdAt = BackupFileNames.timeOf(name) ?: lastModified(),
            sizeBytes = length().coerceAtLeast(0),
            inApp = inApp,
        )
    }

    private companion object {
        const val LOCAL_DIRECTORY = "backups"
    }
}

/**
 * One kept file of [AutomaticBackupFiles]: an automatic export or a sync safety backup, the minute
 * its name was given for, its size, and whether it is in the app-private folder rather than the
 * storage location.
 */
class AutomaticBackup(
    val file: UniFile,
    val name: String,
    val kind: Kind,
    val createdAt: Long,
    val sizeBytes: Long,
    val inApp: Boolean,
) {
    enum class Kind { AUTOMATIC, SYNC_SAFETY }
}

/** A safety backup's file from [AutomaticBackupFiles.createSync], and whether its name was there before the call. */
class SyncBackupFile(val file: UniFile, val existed: Boolean)
