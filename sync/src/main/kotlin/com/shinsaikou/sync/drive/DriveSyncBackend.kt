package com.shinsaikou.sync.drive

import com.shinsaikou.sync.BackendStatus
import com.shinsaikou.sync.RemoteFile
import com.shinsaikou.sync.StreamDigest
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncSignedOutException
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.Closeable
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * E1's [SyncBackend] over Google Drive v3 in the `appDataFolder` (plan unit H), built by
 * [DriveAccount] from the stored sign-in, over [DriveHttp].
 *
 * - **Layout.** Each of Q3's paths is one file's `name`, slashes included, with the `appDataFolder`
 *   as its parent and no folders: one listing call family, no folder ids to cache or race, and a
 *   deletion by name.
 * - **Listing.** `files.list` over the `appDataFolder` space with `trashed = false`, every page
 *   followed, the prefix filtered here with `startsWith`: Google's `name contains` is a term prefix
 *   match whose handling of `/` and `-` it does not document, and a listing decides Q3's gaps and
 *   deletions. Two files with one name are the newest by `createdTime`, then the greatest id.
 *   [RemoteFile.modifiedAt] is `modifiedTime`, the server's clock (R87); a file without `size` or
 *   `modifiedTime` fails the listing.
 * - **Writes.** Up to [multipartLimit] bytes is one multipart upload; more is a resumable session in
 *   chunks of [chunkSize], resumed from the `Range` a 308 names, after a 401 on a chunk (one refresh,
 *   then the session's status), after a transport failure or a 5xx (the status, within item 3's
 *   budget), and on a new session after a 404. Then the file is read back by its id and compared by
 *   length and SHA-256; Drive's own checksums are "if available" and not used. That read-back is the
 *   only one: [createReadsBack] tells the engine not to read the file again (phase 5 unit SC3).
 *   Last, every other file of that name is deleted, so a restarted push that rewrites a range, or a
 *   retried multipart upload whose first answer was lost, leaves one file.
 * - **Reads** take the id from the last listing when it has one, else an exact `name =` query.
 * - **One budget per call** (item 3): each request that [DriveHttp.retrying] wraps (a listing page,
 *   a name query page, a multipart create, a session start, a read, the read-back, one delete) has
 *   its own four attempts, and nothing inside one reaches another; a resumable session's chunks and
 *   status queries count their own attempts in [upload] and are never inside [DriveHttp.retrying].
 *
 * Built with [pageSize], [multipartLimit] and [chunkSize] from a test; [DriveAccount] builds the
 * standard one.
 */
class DriveSyncBackend internal constructor(
    override val id: String,
    private val http: DriveHttp,
    private val pageSize: Int = PAGE_SIZE,
    private val multipartLimit: Long = MULTIPART_LIMIT,
    private val chunkSize: Int = CHUNK_SIZE,
) : SyncBackend {

    init {
        require(pageSize in 1..PAGE_SIZE) { "A files.list page holds 1 to $PAGE_SIZE files" }
        require(chunkSize > 0) { "A resumable chunk holds at least one byte" }
    }

    /** Each name's id in the last listing, and each file this backend wrote or found since. */
    private val ids = ConcurrentHashMap<String, String>()

    override suspend fun probe(): BackendStatus = try {
        http.user()
        BackendStatus(reachable = true, authenticated = true, message = null)
    } catch (e: SyncSignedOutException) {
        BackendStatus(reachable = true, authenticated = false, message = e.message)
    }

    override suspend fun list(prefix: String): List<RemoteFile> {
        val newest = collapse(query(TRASHED_FALSE, LIST_FIELDS, listing = true))
        ids.clear()
        newest.forEach { (name, file) -> ids[name] = file.id }
        return newest.values.filter { it.name.startsWith(prefix) }
            .map { RemoteFile(path = it.name, size = it.size!!, modifiedAt = it.modifiedAt!!) }
    }

    override suspend fun create(path: String, content: SyncContent) {
        val created = if (content.length <= multipartLimit) multipart(path, content) else resumable(path, content)
        verifyStored(path, created, content)
        named(path).filter { it.id != created }.forEach { deleteId(it.id, path) }
        ids[path] = created
    }

    /** [create] reads the new file back by its id in [verifyStored]. */
    override val createReadsBack: Boolean = true

    /** [block] runs in the caller's coroutine context, reading the network stream there. */
    override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T {
        val listed = ids[path]
        val response = listed?.let { media(it, path) } ?: run {
            // A file a listing named can be gone since, deleted by the cleanup after a rewrite.
            if (listed != null) ids.remove(path, listed)
            val found = newest(named(path)) ?: throw FileNotFoundException(path)
            ids[path] = found.id
            media(found.id, path) ?: throw FileNotFoundException(path)
        }
        return response.use { it.body.byteStream().use { stream -> block(stream) } }
    }

    override suspend fun delete(path: String) {
        named(path).forEach { deleteId(it.id, path) }
        ids.remove(path)
    }

    /** The `alt=media` answer for [id], or null when Drive has no such file, within one budget. */
    private suspend fun media(id: String, path: String): Response? = http.retrying { mediaOnce(id, path) }

    /** One attempt at [media]: the caller holds the budget. */
    private suspend fun mediaOnce(id: String, path: String): Response? {
        val response = http.signed("files.get $path") { Request.Builder().url(fileUrl(id, media = true)).get() }
        return when {
            response.isSuccessful -> response
            response.code == DriveHttp.NOT_FOUND -> {
                response.close()
                null
            }
            else -> response.use { throw http.failure(it, "files.get $path") }
        }
    }

    private suspend fun deleteId(id: String, path: String) {
        http.retrying {
            http.signed("files.delete $path") { Request.Builder().url(fileUrl(id)).delete() }.use { response ->
                if (response.code != DriveHttp.NOT_FOUND && !response.isSuccessful) {
                    throw http.failure(response, "files.delete $path")
                }
            }
        }
    }

    /** Every file named [path] exactly, by an exact `name =` query. */
    private suspend fun named(path: String): List<Listed> =
        query("name = '${escape(path)}' and $TRASHED_FALSE", NAME_FIELDS, listing = false).filter { it.name == path }

    /**
     * Every file [q] matches in the `appDataFolder`, following every page. A [listing] needs each
     * file's size and time; a name query needs its id and creation time only.
     */
    private suspend fun query(q: String, fields: String, listing: Boolean): List<Listed> {
        val found = mutableListOf<Listed>()
        var pageToken: String? = null
        do {
            val url = http.files.newBuilder()
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("q", q)
                .addQueryParameter("pageSize", pageSize.toString())
                .addQueryParameter("fields", fields)
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = http.retrying {
                http.signed("files.list") { Request.Builder().url(url).get() }.use { response ->
                    if (!response.isSuccessful) throw http.failure(response, "files.list")
                    http.json<FileList>(response, "files.list")
                }
            }
            page.files.orEmpty().mapTo(found) { it.listed(listing) }
            pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return found
    }

    private suspend fun multipart(path: String, content: SyncContent): String {
        val url = http.upload.newBuilder().addQueryParameter("uploadType", "multipart").addQueryParameter("fields", "id").build()
        return http.retrying {
            http.signed("files.create $path") {
                val body = MultipartBody.Builder().setType(MULTIPART_RELATED)
                    .addPart(metadata(path).toRequestBody(JSON_TYPE))
                    .addPart(ContentBody(content))
                    .build()
                Request.Builder().url(url).post(body)
            }.use { response ->
                if (!response.isSuccessful) throw http.failure(response, "files.create $path")
                createdId(response, path)
            }
        }
    }

    /**
     * A resumable upload of [content], one chunk at a time from the offset the session reports.
     * Failures without progress share item 3's budget: after the last wait, the failure propagates.
     */
    private suspend fun resumable(path: String, content: SyncContent): String {
        val reader = ChunkReader(content)
        try {
            return upload(path, content.length, reader)
        } finally {
            // A cancelled sync still closes the content's stream.
            withContext(NonCancellable + http.io) { reader.close() }
        }
    }

    private suspend fun upload(path: String, total: Long, reader: ChunkReader): String {
        val what = "the upload of $path"
        var session = startSession(path, total)
        var newSessionTaken = false
        var token = http.authorization.accessToken()
        var offset = 0L
        // The furthest offset the session has confirmed; failures count only until it moves.
        var confirmed = 0L
        var failures = 0
        var refreshed = false
        var askStatus = false

        suspend fun failed(error: IOException) {
            if (failures >= http.backoff.waits.size) throw error
            http.backoff.pause(failures++)
            askStatus = true
        }

        while (true) {
            val response = try {
                if (askStatus) {
                    http.send(what, statusQuery(session, total), token, resumable = true)
                } else {
                    val bytes = withContext(http.io) { reader.chunk(offset, minOf(chunkSize.toLong(), total - offset).toInt()) }
                    http.send(what, chunk(session, bytes, offset, total), token, resumable = true)
                }
            } catch (e: DriveUnavailableException) {
                failed(e)
                continue
            }
            response.use {
                when {
                    it.isSuccessful -> return createdId(it, path)
                    it.code == DriveHttp.RESUME_INCOMPLETE -> {
                        val answeredChunk = !askStatus
                        offset = committed(it)
                        askStatus = false
                        if (offset > confirmed) {
                            confirmed = offset
                            failures = 0
                            refreshed = false
                        } else if (answeredChunk) {
                            // A session that keeps nothing of a whole chunk would loop forever.
                            failed(DriveHttpException(DriveHttp.RESUME_INCOMPLETE, null, "$what, which kept nothing of a chunk"))
                        }
                    }
                    it.code == DriveHttp.UNAUTHORIZED -> {
                        if (refreshed) throw SyncSignedOutException("Google Drive refused a refreshed access token ($what)")
                        refreshed = true
                        token = http.authorization.refreshed(token)
                        askStatus = true
                    }
                    it.code == DriveHttp.NOT_FOUND -> {
                        if (newSessionTaken) throw http.failure(it, what)
                        newSessionTaken = true
                        session = startSession(path, total)
                        offset = 0
                        confirmed = 0
                        failures = 0
                        askStatus = false
                    }
                    else -> when (val error = http.failure(it, what)) {
                        is DriveUnavailableException -> failed(error)
                        else -> throw error
                    }
                }
            }
        }
    }

    /**
     * A new resumable session for [path], whose address must be on Drive's own scheme, host and port:
     * the chunks carry the access token, which goes nowhere else.
     */
    private suspend fun startSession(path: String, total: Long): HttpUrl {
        val url = http.upload.newBuilder().addQueryParameter("uploadType", "resumable").addQueryParameter("fields", "id").build()
        return http.retrying {
            http.signed("files.create $path") {
                Request.Builder().url(url)
                    .header("X-Upload-Content-Type", OCTET_STREAM.toString())
                    .header("X-Upload-Content-Length", total.toString())
                    .post(metadata(path).toRequestBody(JSON_TYPE))
            }.use { response ->
                if (!response.isSuccessful) throw http.failure(response, "files.create $path")
                val session = response.header("Location")?.let { response.request.url.resolve(it) }
                if (session == null || session.scheme != url.scheme || session.host != url.host || session.port != url.port) {
                    throw DriveMalformedResponseException("Google Drive started the upload of $path without a session on its own host")
                }
                session
            }
        }
    }

    private fun chunk(session: HttpUrl, bytes: ByteArray, offset: Long, total: Long): Request.Builder =
        Request.Builder().url(session)
            .header("Content-Range", "bytes $offset-${offset + bytes.size - 1}/$total")
            .put(bytes.toRequestBody(OCTET_STREAM))

    private fun statusQuery(session: HttpUrl, total: Long): Request.Builder =
        Request.Builder().url(session).header("Content-Range", "bytes */$total").put(ByteArray(0).toRequestBody(null))

    /** The offset a 308 says the session holds: the byte after its `Range`, or 0 when it names none. */
    private fun committed(response: Response): Long {
        val range = response.header("Range") ?: return 0
        val end = RANGE.matchEntire(range.trim())?.groupValues?.get(1)?.toLongOrNull()
            ?: throw DriveMalformedResponseException("Google Drive answered an upload with the Range '$range'")
        return end + 1
    }

    private suspend fun createdId(response: Response, path: String): String =
        http.json<CreatedFile>(response, "files.create $path").id?.takeIf { it.isNotEmpty() }
            ?: throw DriveMalformedResponseException("Google Drive created $path without naming its id")

    /**
     * Reads [id] back and compares it with [content] by length and SHA-256, both streamed. The
     * request and the reading of its body share one budget.
     */
    private suspend fun verifyStored(path: String, id: String, content: SyncContent) {
        val written = withContext(http.io) { content.open().use(StreamDigest::of) }
        http.retrying {
            val response = mediaOnce(id, path) ?: throw DriveWriteNotStoredException(path, "no file has the id Drive answered")
            response.use {
                val stored = withContext(http.io) {
                    try {
                        it.body.byteStream().use(StreamDigest::of)
                    } catch (e: IOException) {
                        throw DriveUnavailableException(status = null, reason = null, cause = e)
                    }
                }
                if (stored != written) throw DriveWriteNotStoredException(path, "it holds $stored where $written was written")
            }
        }
    }

    private fun fileUrl(id: String, media: Boolean = false): HttpUrl =
        http.files.newBuilder().addPathSegment(id).apply { if (media) addQueryParameter("alt", "media") }.build()

    /** One file of a listing or a name query, with what that call asked for. */
    private class Listed(val id: String, val name: String, val size: Long?, val modifiedAt: Long?, val createdAt: Long)

    @Serializable
    private class FileList(val nextPageToken: String? = null, val files: List<FileEntry>? = null)

    @Serializable
    private class FileEntry(
        val id: String? = null,
        val name: String? = null,
        val size: String? = null,
        val modifiedTime: String? = null,
        val createdTime: String? = null,
    ) {
        fun listed(listing: Boolean): Listed {
            val id = id?.takeIf { it.isNotEmpty() } ?: malformed("a file without an id")
            val name = name ?: malformed("file $id without a name")
            val createdAt = time(createdTime) ?: malformed("$name without a createdTime it can read")
            if (!listing) return Listed(id, name, null, null, createdAt)
            val size = size?.toLongOrNull()?.takeIf { it >= 0 } ?: malformed("$name without a size (${size ?: "none"})")
            val modifiedAt = time(modifiedTime) ?: malformed("$name without a modifiedTime it can read (${modifiedTime ?: "none"})")
            return Listed(id, name, size, modifiedAt, createdAt)
        }

        private fun time(text: String?): Long? = text?.let {
            try {
                Instant.parse(it).toEpochMilliseconds()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun malformed(what: String): Nothing = throw DriveMalformedResponseException("Google Drive listed $what")
    }

    @Serializable
    private class CreatedFile(val id: String? = null)

    /** Upload bytes streamed from [content] as OkHttp writes them, never held whole. */
    private class ContentBody(private val content: SyncContent) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = content.length

        override fun writeTo(sink: BufferedSink) {
            content.open().source().use { sink.writeAll(it) }
        }
    }

    /**
     * [content] read one chunk at a time from any offset: forward by skipping, backward (a session
     * that kept less than was sent, or a new session) by opening it again (item 1). Blocking; call it
     * on the IO dispatcher.
     */
    private class ChunkReader(private val content: SyncContent) : Closeable {
        private var stream: InputStream? = null
        private var position = 0L

        fun chunk(offset: Long, size: Int): ByteArray {
            var input = stream
            if (input == null || offset < position) {
                input?.close()
                input = content.open()
                stream = input
                position = 0
            }
            while (position < offset) {
                val skipped = input.skip(offset - position)
                if (skipped > 0) {
                    position += skipped
                } else if (input.read() >= 0) {
                    position++
                } else {
                    throw EOFException("The upload's content ended at $position, before $offset")
                }
            }
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(bytes, read, size - read)
                if (count < 0) throw EOFException("The upload's content ended at ${position + read}, before ${offset + size}")
                read += count
            }
            position += size
            return bytes
        }

        override fun close() {
            stream?.close()
        }
    }

    companion object {
        /** What every Drive backend id starts with; `SyncSession` picks the account by it. */
        const val ID_PREFIX = "drive-"
        private const val ID_HEX_CHARS = 16

        /** The largest `files.list` page Drive serves. */
        const val PAGE_SIZE = 1000

        /** Up to 5 MiB is one multipart upload (Q3: over 5 MB is resumable). */
        const val MULTIPART_LIMIT = 5L * 1024 * 1024

        /** A resumable chunk: 4 MiB, a multiple of the 256 KiB Drive requires of every chunk but the last. */
        const val CHUNK_SIZE = 4 * 1024 * 1024

        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val TRASHED_FALSE = "trashed = false"
        private const val LIST_FIELDS = "nextPageToken,files(id,name,size,modifiedTime,createdTime)"
        private const val NAME_FIELDS = "nextPageToken,files(id,name,createdTime)"
        private val RANGE = Regex("bytes=0-(\\d+)")

        private val MULTIPART_RELATED = "multipart/related".toMediaType()
        private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /**
         * `drive-` and the first 16 hex characters of the SHA-256 of [clientId], a newline and
         * [permissionId]. An `appDataFolder` belongs to the OAuth client's project and to the account,
         * so another of either is another store and a full export there; an email address can
         * change, a `permissionId` does not. Changing the rule makes every device export again.
         */
        fun idFor(clientId: String, permissionId: String): String =
            ID_PREFIX + Hash.sha256("$clientId\n$permissionId").take(ID_HEX_CHARS)

        /** The newest of [files]: the latest `createdTime`, then the greatest id. */
        private fun newest(files: List<Listed>): Listed? =
            files.maxWithOrNull(compareBy<Listed> { it.createdAt }.thenBy { it.id })

        /** One file per name, the newest by [newest]. */
        private fun collapse(files: List<Listed>): Map<String, Listed> =
            files.groupBy { it.name }.mapValues { (_, same) -> newest(same)!! }

        /** [value] inside a query's single quotes: `\` and `'` escaped with a backslash, as Google documents. */
        internal fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        /** The metadata of a new file named [path] in the `appDataFolder`. `modifiedTime` is never sent. */
        private fun metadata(path: String): String = buildJsonObject {
            put("name", path)
            putJsonArray("parents") { add(APP_DATA_FOLDER) }
        }.toString()
    }
}
