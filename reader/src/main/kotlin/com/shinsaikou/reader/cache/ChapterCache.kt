package com.shinsaikou.reader.cache

import android.content.Context
import android.text.format.Formatter
import com.jakewharton.disklrucache.DiskLruCache
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.reader.pipeline.SplitPlanner
import com.shinsaikou.source.model.PageInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The reader's disk cache: one entry per page, one per band of a split page, one per chapter page
 * list. Contract 07 part 1b section 4.9.2.
 *
 * Adapted from upstream v0.20.4 `app/src/main/java/eu/kanade/tachiyomi/data/cache/ChapterCache.kt`.
 * That file takes an OkHttp `Response`, a `eu.kanade.tachiyomi.source.model.Page` and Injekt's
 * `Json`, none of which exist here, so the shape is kept and the four real differences are the
 * part entries, the page-list payload, the access refresh and the busy-editor error.
 *
 * Every member except [readableSize] and [clear] is blocking and must be called on IO; the
 * reader's callers are already inside `dispatchers.io`.
 */
@SingleIn(AppScope::class)
@Inject
class ChapterCache(
    private val context: Context,
    private val json: Json,
    private val dispatchers: Dispatchers,
) {

    /**
     * Lazy, because `DiskLruCache.open` reads and may rebuild a journal and nothing in `:reader`
     * may cost anything at process start.
     */
    private val diskCache by lazy {
        DiskLruCache.open(File(context.cacheDir, DIRECTORY), APP_VERSION, VALUE_COUNT, MAX_SIZE_BYTES)
    }

    // ---- Page list ----------------------------------------------------------------------------

    /** Null on a miss, on an IO failure and on a payload that no longer decodes. Never throws. */
    fun getPageListFromCache(chapter: Chapter): List<PageInfo>? = try {
        diskCache.get(DiskKeys.pageList(chapter.mangaId, chapter.url))?.use { snapshot ->
            json.decodeFromString<List<CachedPage>>(snapshot.getString(0))
                .map { PageInfo(index = it.index, url = it.url, imageUrl = it.imageUrl) }
        }
    } catch (e: IOException) {
        logcat(LogPriority.WARN, e) { "Failed to read a page list from the cache" }
        null
    } catch (e: SerializationException) {
        logcat(LogPriority.WARN, e) { "Cached page list no longer decodes" }
        null
    }

    /** Swallows its own failures: a page list that did not cache is a slower open, not an error. */
    fun putPageListToCache(chapter: Chapter, pages: List<PageInfo>) {
        val payload = json.encodeToString(pages.map { CachedPage(it.index, it.url, it.imageUrl) })
        try {
            writeEntry(DiskKeys.pageList(chapter.mangaId, chapter.url)) { it.write(payload.toByteArray()) }
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "Failed to write a page list to the cache" }
        }
    }

    // ---- Whole pages --------------------------------------------------------------------------

    fun isImageInCache(key: String): Boolean = entryExists(DiskKeys.image(key))

    /**
     * The entry is touched before the `File` is handed out, so reading a page refreshes its
     * position in the journal. Upstream's version builds the `File` from the key and never calls
     * `get`, which makes its eviction order download order rather than access order.
     */
    fun getImageFile(key: String): File = refreshed(DiskKeys.image(key))

    @Throws(IOException::class)
    fun putImageToCache(key: String, source: BufferedSource) {
        writeEntry(DiskKeys.image(key)) { source.inputStream().copyTo(it, COPY_BUFFER_BYTES) }
    }

    // ---- Parts of a split page ----------------------------------------------------------------

    /** The length of the run of parts present from index 0 upward. 0 when the page is not split. */
    fun partsInCache(key: String): Int {
        var index = 0
        while (index < SplitPlanner.MAX_PARTS && entryExists(DiskKeys.part(key, index))) {
            index++
        }
        return index
    }

    fun getPartFile(key: String, index: Int): File = refreshed(DiskKeys.part(key, index))

    @Throws(IOException::class)
    fun putPartToCache(key: String, index: Int, write: (OutputStream) -> Unit) {
        writeEntry(DiskKeys.part(key, index), write)
    }

    /**
     * Removes every part of [key], including one stranded past a hole an eviction opened, which is
     * why this scans the directory rather than walking indices.
     */
    fun removeParts(key: String) {
        val prefix = DiskKeys.image(key) + DiskKeys.PART_INFIX
        try {
            diskCache.directory.listFiles().orEmpty()
                .map { it.name.substringBeforeLast('.') }
                .filter { it.startsWith(prefix) }
                .distinct()
                .forEach { diskCache.remove(it) }
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "Failed to remove the parts of a split page" }
        }
    }

    // ---- For :app's Data and storage screen ---------------------------------------------------

    /** Walks the whole directory, so it is not something to poll. */
    suspend fun readableSize(): String = withContext(dispatchers.io) {
        Formatter.formatFileSize(context, DiskUtil.getDirectorySize(diskCache.directory))
    }

    /** Returns the number of entries removed. */
    suspend fun clear(): Int = withContext(dispatchers.io) {
        var removed = 0
        diskCache.directory.listFiles().orEmpty().forEach {
            if (removeFileFromCache(it.name)) removed++
        }
        removed
    }

    // ---- Internals ----------------------------------------------------------------------------

    private fun removeFileFromCache(fileName: String): Boolean {
        // The journal is the cache's own bookkeeping and is never an entry.
        if (fileName == JOURNAL_FILE || fileName.startsWith("$JOURNAL_FILE.")) return false
        return try {
            diskCache.remove(fileName.substringBeforeLast('.'))
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "Failed to remove a cache entry" }
            false
        }
    }

    private fun entryExists(entryKey: String): Boolean = try {
        val inJournal = diskCache.get(entryKey)?.use { true } == true
        inJournal && fileFor(entryKey).exists()
    } catch (_: IOException) {
        false
    }

    private fun refreshed(entryKey: String): File {
        try {
            diskCache.get(entryKey)?.close()
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "Failed to refresh a cache entry" }
        }
        return fileFor(entryKey)
    }

    private fun fileFor(entryKey: String) = File(diskCache.directory, "$entryKey.$VALUE_INDEX")

    /**
     * An editor is null when another edit of the same key is in flight. Upstream returns silently,
     * which leaves the caller with a missing file and an unexplained failure two steps later.
     */
    @Throws(IOException::class)
    private fun writeEntry(entryKey: String, block: (OutputStream) -> Unit) {
        val editor = diskCache.edit(entryKey) ?: throw IOException("cache entry busy")
        try {
            editor.newOutputStream(VALUE_INDEX).use(block)
            diskCache.flush()
            editor.commit()
        } finally {
            editor.abortUnlessCommitted()
        }
    }

    /**
     * `PageInfo` is not `@Serializable` and carries a progress hook, so the page list has its own
     * payload type. Changing its shape means bumping [APP_VERSION].
     */
    @Serializable
    private data class CachedPage(val index: Int, val url: String = "", val imageUrl: String? = null)

    companion object {
        const val DIRECTORY = "chapter_disk_cache"

        /** Bumped whenever the part-key scheme or the page-list payload changes; the bump wipes. */
        const val APP_VERSION = 1

        /** Fixed at open, which is why a band cannot be another value of its parent's entry. */
        const val VALUE_COUNT = 1

        /**
         * Sized from the failure it prevents, which is evicting the chapter being read: a worst
         * realistic webtoon chapter plus its bands is about 120 MiB, and the next chapter's fetch
         * window adds about 24 MiB. Not a preference: `:reader` may not add a key to `:domain`'s
         * merged `ReaderPreferences`.
         */
        const val MAX_SIZE_BYTES = 160L * 1024 * 1024

        const val COPY_BUFFER_BYTES = 8 * 1024

        private const val VALUE_INDEX = 0
        private const val JOURNAL_FILE = "journal"
    }
}
