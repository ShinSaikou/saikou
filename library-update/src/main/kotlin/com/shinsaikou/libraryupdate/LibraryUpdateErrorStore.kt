package com.shinsaikou.libraryupdate

import android.content.Context
import com.shinsaikou.domain.error.ErrorKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * One entry that failed in the last run. [at] is when it failed, in epoch milliseconds.
 *
 * [key] is what went wrong, turned into text only when the row is shown, so the text follows the
 * device's language. [detail] is the status code for [ErrorKey.HTTP], the site's host for
 * [ErrorKey.SITE_STOPPED], and null for every other named key an update meets. For
 * [ErrorKey.GENERIC] it is the failure's own [ErrorKey.message], an extension's text shown as it is,
 * or the exception's class name when it has none (and in every row stored before messages were).
 *
 * A row written before keys were stored has a null [key], and [detail] is the text it was stored
 * with. It lasts until a run checks its entry again.
 */
data class LibraryUpdateError(
    val mangaId: Long,
    val title: String,
    val sourceId: Long,
    val key: ErrorKey?,
    val detail: String?,
    val at: Long,
)

/**
 * The per-manga errors of the library updates, kept in one JSON file rather than a table (contract
 * 06 decision F4). The file is rewritten whole by [merge] and [replace], never appended to. Each row
 * is its entry's last check: a run replaces only the rows of the entries it checked ([merge]), so a
 * category run or a cancelled one leaves the others' rows as they were.
 *
 * [errors] starts empty and is filled by [load]; the file is never read on the caller's thread.
 */
@SingleIn(AppScope::class)
@Inject
class LibraryUpdateErrorStore(
    private val context: Context,
    private val json: Json,
) {

    /** `filesDir/library-update/last-errors.json`. */
    val file: File
        get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private val _errors = MutableStateFlow<List<LibraryUpdateError>>(emptyList())
    val errors: StateFlow<List<LibraryUpdateError>> = _errors.asStateFlow()

    /** Reads the file into [errors]. Safe to call more than once. */
    suspend fun load() {
        _errors.value = withContext(Dispatchers.IO) { read() }
    }

    /** Replaces the stored list with [failures], both on disk and in [errors]. */
    suspend fun replace(failures: List<FlatLibraryUpdate.Failure>) {
        val entries = failures.toErrors()
        withContext(Dispatchers.IO) { write(entries) }
        _errors.value = entries
    }

    /**
     * Writes one run's [failures] into the stored list. The row of an entry the run [checked] is
     * replaced by its new failure, or dropped when the entry updated fine; any other row stays while
     * [keep] answers true for its entry, which is how a row whose entry left the library goes. A run
     * used to replace the whole list, so a category run or a cancelled one lost every other row.
     *
     * New failures go to the end, and the kept row of an entry the run was [asked] for but did not
     * check (one fetch-once passes over, or one a stop never reached) just before them: Retry takes
     * the front of the list, which such rows would otherwise hold on every Retry.
     *
     * The file is read here rather than [errors], which a process that never called [load] holds
     * empty.
     */
    suspend fun merge(
        failures: List<FlatLibraryUpdate.Failure>,
        checked: Set<Long>,
        asked: Set<Long> = emptySet(),
        keep: suspend (mangaId: Long) -> Boolean,
    ) {
        val fresh = failures.toErrors()
        val covered = checked + fresh.map { it.mangaId }
        val previous = withContext(Dispatchers.IO) { read() }
        val (passedOver, untouched) = previous
            .filter { it.mangaId !in covered && keep(it.mangaId) }
            .partition { it.mangaId in asked }
        val entries = untouched + passedOver + fresh
        withContext(Dispatchers.IO) { write(entries) }
        _errors.value = entries
    }

    private fun List<FlatLibraryUpdate.Failure>.toErrors(): List<LibraryUpdateError> {
        val now = System.currentTimeMillis()
        return map { (manga, error) ->
            val key = ErrorKey.of(error)
            LibraryUpdateError(
                mangaId = manga.id,
                title = manga.displayTitle,
                sourceId = manga.source,
                key = key,
                // An unknown failure keeps the source's own words, often the only thing that says why
                // (R162 item 8); its class name was all the row used to say.
                detail = if (key == ErrorKey.GENERIC) {
                    ErrorKey.message(error) ?: ErrorKey.detail(error)
                } else {
                    ErrorKey.detail(error)
                },
                at = now,
            )
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { file.delete() }
        _errors.value = emptyList()
    }

    private fun read(): List<LibraryUpdateError> {
        val file = file
        if (!file.exists()) return emptyList()
        return try {
            json.parseToJsonElement(file.readText()).jsonArray.map { element ->
                val entry = element.jsonObject
                val id = entry[KEY_ERROR]?.jsonPrimitive?.contentOrNull
                LibraryUpdateError(
                    mangaId = entry.getValue(KEY_MANGA_ID).jsonPrimitive.long,
                    title = entry.getValue(KEY_TITLE).jsonPrimitive.contentOrNull.orEmpty(),
                    sourceId = entry.getValue(KEY_SOURCE_ID).jsonPrimitive.long,
                    // A key this build does not know still reads as a failure, with the generic text.
                    key = id?.let { ErrorKey.fromId(it) ?: ErrorKey.GENERIC },
                    detail = if (id != null) {
                        entry[KEY_DETAIL]?.jsonPrimitive?.contentOrNull
                    } else {
                        entry.getValue(KEY_MESSAGE).jsonPrimitive.contentOrNull.orEmpty()
                    },
                    at = entry.getValue(KEY_AT).jsonPrimitive.long,
                )
            }
        } catch (e: Exception) {
            // A truncated or hand-edited file is not worth crashing a background run over.
            logcat(LogPriority.WARN, e) { "Could not read the library update error list" }
            emptyList()
        }
    }

    private fun write(entries: List<LibraryUpdateError>) {
        val file = file
        file.parentFile?.mkdirs()
        val array = buildJsonArray {
            entries.forEach { error ->
                addJsonObject {
                    put(KEY_MANGA_ID, error.mangaId)
                    put(KEY_TITLE, error.title)
                    put(KEY_SOURCE_ID, error.sourceId)
                    if (error.key != null) {
                        put(KEY_ERROR, error.key.id)
                        error.detail?.let { put(KEY_DETAIL, it) }
                    } else {
                        put(KEY_MESSAGE, error.detail.orEmpty())
                    }
                    put(KEY_AT, error.at)
                }
            }
        }
        file.writeText(json.encodeToString(JsonArray.serializer(), array))
    }

    private companion object {
        // The module has no serialization plugin, so the file is built from JSON elements by hand.
        const val KEY_MANGA_ID = "mangaId"
        const val KEY_TITLE = "title"
        const val KEY_SOURCE_ID = "sourceId"
        const val KEY_ERROR = "error"
        const val KEY_DETAIL = "detail"

        // The text a row carried before keys were stored. Read, never written for a new failure.
        const val KEY_MESSAGE = "message"
        const val KEY_AT = "at"

        const val DIRECTORY = "library-update"
        const val FILE_NAME = "last-errors.json"
    }
}
