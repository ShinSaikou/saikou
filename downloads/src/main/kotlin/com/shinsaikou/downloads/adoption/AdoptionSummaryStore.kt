package com.shinsaikou.downloads.adoption

import android.content.Context
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.error.ErrorKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * The summary of the last adoption run, kept in one JSON file the way the library update keeps
 * its error list, and rewritten whole by each run. It lives outside `downloads/`, whose every
 * folder the download locator takes for a source.
 *
 * [summary] is null until [load] has read a file, and stays null when there is none. A file written
 * before the unmatched paths carried their reasons, a list of path strings, reads each path with
 * [AdoptionUnmatchedReason.UNKNOWN].
 */
@SingleIn(AppScope::class)
@Inject
class AdoptionSummaryStore(
    private val context: Context,
    private val json: Json,
    private val dispatchers: Dispatchers,
) {

    /** `filesDir/adoption/last-summary.json`. */
    val file: File
        get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private val _summary = MutableStateFlow<AdoptionSummary?>(null)
    val summary: StateFlow<AdoptionSummary?> = _summary.asStateFlow()

    suspend fun load() {
        _summary.value = withContext(dispatchers.io) { read() }
    }

    suspend fun replace(summary: AdoptionSummary) {
        withContext(dispatchers.io) { write(summary) }
        _summary.value = summary
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read(): AdoptionSummary? {
        val file = file
        if (!file.exists()) return null
        return try {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            AdoptionSummary(
                finishedAt = root.getValue(KEY_FINISHED_AT).jsonPrimitive.long,
                matched = root.getValue(KEY_MATCHED).jsonPrimitive.int,
                copied = root.getValue(KEY_COPIED).jsonPrimitive.int,
                skipped = root.getValue(KEY_SKIPPED).jsonPrimitive.int,
                unmatched = root.getValue(KEY_UNMATCHED).jsonArray.map { it.toUnmatched() },
                failures = root.getValue(KEY_FAILURES).jsonArray.map { it.jsonObject.toFailure() },
                treeUri = root[KEY_TREE_URI]?.jsonPrimitive?.contentOrNull,
            )
        } catch (e: Exception) {
            // A truncated or hand-edited file is not worth failing the settings screen over.
            logcat(LogPriority.WARN, e) { "Could not read the adoption summary" }
            null
        }
    }

    /** A bare path is the form before the reasons were kept; a reason this build does not know reads as unknown. */
    private fun JsonElement.toUnmatched(): AdoptionUnmatched {
        if (this is JsonPrimitive) return AdoptionUnmatched(path = content, reason = AdoptionUnmatchedReason.UNKNOWN)
        val entry = jsonObject
        return AdoptionUnmatched(
            path = entry.getValue(KEY_PATH).jsonPrimitive.content,
            reason = entry[KEY_REASON]?.jsonPrimitive?.contentOrNull
                ?.let { AdoptionUnmatchedReason.fromId(it) }
                ?: AdoptionUnmatchedReason.UNKNOWN,
            sourceName = entry[KEY_SOURCE_NAME]?.jsonPrimitive?.contentOrNull,
            sourceLang = entry[KEY_SOURCE_LANG]?.jsonPrimitive?.contentOrNull,
            title = entry[KEY_TITLE]?.jsonPrimitive?.contentOrNull,
            mangaId = entry[KEY_MANGA_ID]?.jsonPrimitive?.longOrNull,
        )
    }

    /** A reason or a key this build does not know reads as a generic failure. */
    private fun JsonObject.toFailure(): AdoptionFailure {
        val reason = get(KEY_REASON)?.jsonPrimitive?.contentOrNull
            ?.let { AdoptionFailureReason.fromId(it) }
            ?: AdoptionFailureReason.ERROR
        return AdoptionFailure(
            path = getValue(KEY_PATH).jsonPrimitive.content,
            reason = reason,
            key = get(KEY_ERROR)?.jsonPrimitive?.contentOrNull?.let { ErrorKey.fromId(it) ?: ErrorKey.GENERIC }
                ?: ErrorKey.GENERIC.takeIf { reason == AdoptionFailureReason.ERROR },
            detail = get(KEY_DETAIL)?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun write(summary: AdoptionSummary) {
        val file = file
        file.parentFile?.mkdirs()
        val root = buildJsonObject {
            put(KEY_FINISHED_AT, summary.finishedAt)
            put(KEY_MATCHED, summary.matched)
            put(KEY_COPIED, summary.copied)
            put(KEY_SKIPPED, summary.skipped)
            putJsonArray(KEY_UNMATCHED) {
                summary.unmatched.forEach { entry ->
                    addJsonObject {
                        put(KEY_PATH, entry.path)
                        put(KEY_REASON, entry.reason.id)
                        entry.sourceName?.let { put(KEY_SOURCE_NAME, it) }
                        entry.sourceLang?.let { put(KEY_SOURCE_LANG, it) }
                        entry.title?.let { put(KEY_TITLE, it) }
                        entry.mangaId?.let { put(KEY_MANGA_ID, it) }
                    }
                }
            }
            putJsonArray(KEY_FAILURES) {
                summary.failures.forEach { failure ->
                    addJsonObject {
                        put(KEY_PATH, failure.path)
                        put(KEY_REASON, failure.reason.id)
                        failure.key?.let { put(KEY_ERROR, it.id) }
                        failure.detail?.let { put(KEY_DETAIL, it) }
                    }
                }
            }
            summary.treeUri?.let { put(KEY_TREE_URI, it) }
        }
        file.writeText(json.encodeToString(JsonObject.serializer(), root))
    }

    private companion object {
        // The module has no serialization plugin, so the file is built from JSON elements by hand.
        const val KEY_FINISHED_AT = "finishedAt"
        const val KEY_MATCHED = "matched"
        const val KEY_COPIED = "copied"
        const val KEY_SKIPPED = "skipped"
        const val KEY_UNMATCHED = "unmatched"
        const val KEY_FAILURES = "failures"
        const val KEY_PATH = "path"
        const val KEY_REASON = "reason"
        const val KEY_ERROR = "error"
        const val KEY_DETAIL = "detail"
        const val KEY_SOURCE_NAME = "sourceName"
        const val KEY_SOURCE_LANG = "sourceLang"
        const val KEY_TITLE = "title"
        const val KEY_MANGA_ID = "mangaId"
        const val KEY_TREE_URI = "treeUri"

        const val DIRECTORY = "adoption"
        const val FILE_NAME = "last-summary.json"
    }
}
