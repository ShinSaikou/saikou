package com.shinsaikou.backup.importer

import android.content.Context
import com.shinsaikou.domain.preferences.BackupPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * The four files a run reads and writes, plus the checkpoint itself. The checkpoint is small and
 * rewritten often, so it lives in a preference rather than a table (contract 06 decision F3).
 */
interface ImportCheckpointStore {

    fun load(): ImportCheckpoint?

    fun save(checkpoint: ImportCheckpoint)

    fun clear()

    /** `filesDir/backup-import/current.tachibk`: the private copy the run reads. */
    val importFile: File

    /** `filesDir/backup-import/errors.jsonl`: one [ImportError] per line, appended per batch. */
    val errorLog: File

    /** `filesDir/backup-import/last-report.json`: the last [ImportReport], for the screen. */
    val reportFile: File

    /**
     * `filesDir/backup-import/stores-before.json`: the [StoresBefore] of the file being restored,
     * kept until its run finishes, so a retry still knows which stores the first attempt added.
     */
    val storesBeforeFile: File

    /**
     * The last run's [ImportReport] from [reportFile] (unit restore-report), or null when no run has
     * finished on this install or the file does not read. Decoded whole: call it off the main
     * thread, since a run where every entry failed holds one error per entry.
     */
    fun loadReport(): ImportReport? {
        val file = reportFile
        if (!file.exists()) return null
        return try {
            ImportReport.ReportJson.decodeFromString(ImportReport.serializer(), file.readText())
        } catch (e: Exception) {
            // A damaged report is no report: the next run writes a new one.
            logcat(LogPriority.WARN, e) { "Ignoring an unreadable restore report" }
            null
        }
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PreferenceImportCheckpointStore(
    private val context: Context,
    private val backupPreferences: BackupPreferences,
    private val json: Json,
) : ImportCheckpointStore {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY)

    override val importFile: File
        get() = File(directory, IMPORT_FILE_NAME)

    override val errorLog: File
        get() = File(directory, ERROR_LOG_NAME)

    override val reportFile: File
        get() = File(directory, REPORT_FILE_NAME)

    override val storesBeforeFile: File
        get() = File(directory, STORES_BEFORE_FILE_NAME)

    override fun load(): ImportCheckpoint? {
        val stored = backupPreferences.lastImportCheckpoint.get()
        if (stored.isEmpty()) return null
        return try {
            json.decodeFromString(ImportCheckpoint.serializer(), stored)
        } catch (e: Exception) {
            // A checkpoint written by an older build is worth dropping, not crashing on.
            logcat(LogPriority.WARN, e) { "Discarding an unreadable import checkpoint" }
            clear()
            null
        }
    }

    override fun save(checkpoint: ImportCheckpoint) {
        backupPreferences.lastImportCheckpoint
            .set(json.encodeToString(ImportCheckpoint.serializer(), checkpoint))
    }

    override fun clear() {
        backupPreferences.lastImportCheckpoint.delete()
    }

    private companion object {
        const val DIRECTORY = "backup-import"
        const val IMPORT_FILE_NAME = "current.tachibk"
        const val ERROR_LOG_NAME = "errors.jsonl"
        const val REPORT_FILE_NAME = "last-report.json"
        const val STORES_BEFORE_FILE_NAME = "stores-before.json"
    }
}
