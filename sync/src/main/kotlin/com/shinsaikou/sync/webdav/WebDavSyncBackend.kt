package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.BackendStatus
import com.shinsaikou.sync.RemoteFile
import com.shinsaikou.sync.StreamDigest
import com.shinsaikou.sync.SyncAuthorization
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.await
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * E1's [SyncBackend] over WebDAV (plan unit F): the layout's paths below [baseUrl], signed in with
 * Basic credentials that ride each request as a [SyncAuthorization] tag, so the sync client's one
 * interceptor adds the header (R84 item 5).
 *
 * - [list] walks collections with `PROPFIND` at `Depth: 1`, never infinity, and reports each file's
 *   `getlastmodified`, the server's clock; a file without one fails the listing, because compaction
 *   would read any stand-in as old.
 * - [create] streams a `PUT`, makes missing parent collections with `MKCOL` on a 409 or a 404 (which
 *   some servers answer instead) and retries once,
 *   then reads the file back and compares its length and SHA-256 with the content, so a server that
 *   reports a write it did not store, or stored other bytes, fails the sync
 *   ([WebDavWriteNotStoredException]). That read-back is the only one: [createReadsBack] tells the
 *   engine not to read the file again (phase 5 unit SC3; R87 item 8 had it read twice).
 * - No redirect is followed. OkHttp would resend a `PUT` and its body unchanged on a 307 or 308, to
 *   another host or from `https://` to `http://`, and would take a redirected listing or read as the
 *   configured server's; so every 3xx on every call is [WebDavRedirectException], and the owner enters
 *   the final address.
 * - A 401 on any call is [SyncSignedOutException], except in [probe], whose answer says so.
 * - A certificate the device does not trust and a certificate for another host name are
 *   [WebDavUntrustedCertificateException] and [WebDavHostNameMismatchException]; a refused
 *   connection, an unknown host, no route and a timeout before the answer's headers are
 *   [WebDavUnreachableException]; every other transport failure is OkHttp's own exception.
 *
 * Built only by [WebDavAccount], which applies the build's [WebDavUrlPolicy] first. [client] is
 * the sync client's (`SyncHttpClient.client`), from which this backend derives its own with both
 * redirect switches off; the shared client is unchanged.
 */
class WebDavSyncBackend internal constructor(
    private val baseUrl: HttpUrl,
    user: String,
    password: String,
    client: OkHttpClient,
    private val io: CoroutineDispatcher,
) : SyncBackend {

    private val client: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init {
        require(baseUrl.pathSegments.last().isEmpty()) { "A base URL ends in /" }
    }

    private val authorization = SyncAuthorization(Credentials.basic(user, password, Charsets.UTF_8))

    private val rootSegments = baseUrl.pathSegments.dropLast(1)

    /**
     * `webdav-` and the first 16 hex characters of the SHA-256 of the base address and the user
     * name, so another server or another account is another backend, and the engine makes a full
     * export there before any segment.
     */
    override val id: String = ID_PREFIX + Hash.sha256("$baseUrl\n$user").take(ID_HEX_CHARS)

    override suspend fun probe(): BackendStatus = call(propfind(url("", collection = true), depth = 0)).use { response ->
        when {
            response.code == 401 -> BackendStatus(reachable = true, authenticated = false, message = "HTTP 401")
            response.code != MULTI_STATUS -> throw WebDavHttpException(response.code, "PROPFIND", "")
            else -> {
                val root = withContext(io) { Multistatus.parse(response.body.byteStream()) }
                    .firstOrNull { relativePath(response.request.url, it.href) == "" }
                if (root?.collection != true) {
                    throw WebDavMalformedResponseException("$baseUrl is not a WebDAV collection")
                }
                BackendStatus(reachable = true, authenticated = true, message = null)
            }
        }
    }

    override suspend fun list(prefix: String): List<RemoteFile> {
        // By path, so a server that lists a member twice reports it once.
        val files = linkedMapOf<String, RemoteFile>()
        // The deepest collection that holds every path starting with the prefix.
        val pending = ArrayDeque(listOf(prefix.substringBeforeLast('/', missingDelimiterValue = "")))
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val collection = pending.removeFirst()
            if (!visited.add(collection)) continue
            val within = if (collection.isEmpty()) "" else "$collection/"
            for (entry in propfindChildren(collection) ?: continue) {
                val path = entry.path
                if (path == collection) continue
                if (!path.startsWith(within)) {
                    throw WebDavMalformedResponseException("Listing $collection named $path, which is outside it")
                }
                if (entry.collection) {
                    if ("$path/".startsWith(prefix) || prefix.startsWith("$path/")) pending += path
                } else if (path.startsWith(prefix)) {
                    files[path] = RemoteFile(
                        path = path,
                        size = entry.contentLength
                            ?: throw WebDavMalformedResponseException("The listing gives $path no getcontentlength"),
                        modifiedAt = entry.lastModified ?: throw WebDavMalformedResponseException(
                            "The listing gives $path no getlastmodified it can read (${entry.rawLastModified ?: "none"})",
                        ),
                    )
                }
            }
        }
        return files.values.toList()
    }

    override suspend fun create(path: String, content: SyncContent) {
        val url = url(path)
        var response = call(put(url, content))
        if (response.code == 409 || response.code == 404) {
            // RFC 4918 answers a missing parent with 409; sabre/dav before 4.1.3, and so Nextcloud
            // and ownCloud, answer 404.
            response.close()
            makeParents(path)
            response = call(put(url, content))
        }
        response.use { if (!it.isSuccessful) throw it.failure("PUT", path) }
        verifyStored(path, content)
    }

    /** [create] ends in [verifyStored]. */
    override val createReadsBack: Boolean = true

    /** [block] runs in the caller's coroutine context, reading the network stream there. */
    override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T =
        call(request(url(path)).get().build()).use { response ->
            if (response.code == 404) throw FileNotFoundException(path)
            if (!response.isSuccessful) throw response.failure("GET", path)
            response.body.byteStream().use { block(it) }
        }

    override suspend fun delete(path: String) {
        call(request(url(path)).delete().build()).use { response ->
            if (response.code != 404 && !response.isSuccessful) throw response.failure("DELETE", path)
        }
    }

    /** One collection's members as backend paths, or null when the collection does not exist. */
    private suspend fun propfindChildren(collection: String): List<Listed>? =
        call(propfind(url(collection, collection = true), depth = 1)).use { response ->
            if (response.code == 404) return null
            if (response.code != MULTI_STATUS) throw response.failure("PROPFIND", collection)
            val requested = response.request.url
            withContext(io) { Multistatus.parse(response.body.byteStream()) }.map { entry ->
                val path = relativePath(requested, entry.href)
                    ?: throw WebDavMalformedResponseException("The listing names ${entry.href}, outside $baseUrl")
                Listed(path, entry.collection, entry.contentLength, entry.lastModified, entry.rawLastModified)
            }
        }

    /** Each parent collection of [path], from the root down; 405 is one that already exists. */
    private suspend fun makeParents(path: String) {
        val segments = path.split('/').dropLast(1)
        for (depth in 1..segments.size) {
            val collection = segments.take(depth).joinToString("/")
            call(request(url(collection, collection = true)).method("MKCOL", null).build()).use { response ->
                if (!response.isSuccessful && response.code != 405) throw response.failure("MKCOL", collection)
            }
        }
    }

    /** Reads [path] back and compares it with [content] by length and SHA-256, both streamed. */
    private suspend fun verifyStored(path: String, content: SyncContent) {
        val written = withContext(io) { content.open().use(StreamDigest::of) }
        call(request(url(path)).get().build()).use { response ->
            if (response.code == 404) throw WebDavWriteNotStoredException(path, "no file is there")
            if (!response.isSuccessful) throw response.failure("GET", path)
            val stored = withContext(io) { response.body.byteStream().use(StreamDigest::of) }
            if (stored != written) {
                throw WebDavWriteNotStoredException(path, "it holds $stored where $written was written")
            }
        }
    }

    /**
     * The URL of backend [path], each segment percent-encoded; a [collection] URL ends in `/`.
     * The base URL's own trailing empty segment is replaced by the first one added.
     */
    private fun url(path: String, collection: Boolean = false): HttpUrl {
        val builder = baseUrl.newBuilder()
        if (path.isNotEmpty()) {
            path.split('/').forEach { segment ->
                require(segment.isNotEmpty() && segment != "." && segment != "..") { "Not a backend path: $path" }
                builder.addPathSegment(segment)
            }
        }
        if (collection && path.isNotEmpty()) builder.addPathSegment("")
        return builder.build()
    }

    /**
     * [href] from a multistatus as a backend path, or null when it names something outside the base
     * address. An href may be a full URL, an absolute path or relative to [requested]; it is
     * percent-decoded segment by segment. A collection's trailing `/` is dropped. The host is not
     * compared, since a server behind a proxy may name its own.
     */
    private fun relativePath(requested: HttpUrl, href: String): String? {
        val resolved = requested.resolve(href.trim()) ?: return null
        val segments = resolved.pathSegments.let { if (it.last().isEmpty()) it.dropLast(1) else it }
        if (segments.size < rootSegments.size || segments.subList(0, rootSegments.size) != rootSegments) return null
        return segments.drop(rootSegments.size).joinToString("/")
    }

    private fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).tag(SyncAuthorization::class, authorization)

    private fun propfind(url: HttpUrl, depth: Int): Request =
        request(url).header("Depth", depth.toString()).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).build()

    private fun put(url: HttpUrl, content: SyncContent): Request = request(url).put(ContentBody(content)).build()

    private fun Response.failure(method: String, path: String): IOException =
        if (code == 401) SyncSignedOutException("WebDAV refused the stored credentials ($method /$path)")
        else WebDavHttpException(code, method, path)

    /**
     * Sends [request] and suspends until its headers arrive; cancelling the coroutine cancels the
     * call. A certificate the device does not trust, one for another host, a server that does not
     * answer, and any redirect are named here.
     */
    private suspend fun call(request: Request): Response {
        val response = send(request)
        if (response.code in REDIRECTS) {
            val location = response.header("Location")?.let(request.url::resolve)
            response.close()
            throw WebDavRedirectException(response.code, request.method, request.url.encodedPath, location?.scheme, location?.host)
        }
        return response
    }

    private suspend fun send(request: Request): Response = try {
        client.newCall(request).await()
    } catch (e: ConnectException) {
        throw WebDavUnreachableException(request.url.host, e)
    } catch (e: UnknownHostException) {
        throw WebDavUnreachableException(request.url.host, e)
    } catch (e: NoRouteToHostException) {
        throw WebDavUnreachableException(request.url.host, e)
    } catch (e: SocketTimeoutException) {
        throw WebDavUnreachableException(request.url.host, e)
    } catch (e: SSLPeerUnverifiedException) {
        throw WebDavHostNameMismatchException(request.url.host, e)
    } catch (e: SSLException) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }) {
            throw WebDavUntrustedCertificateException(request.url.host, e)
        }
        throw e
    }

    /** A listed member: its backend path and what the listing said of it. */
    private class Listed(
        val path: String,
        val collection: Boolean,
        val contentLength: Long?,
        val lastModified: Long?,
        val rawLastModified: String?,
    )

    /** Upload bytes streamed from [content] as OkHttp writes them, never held whole. */
    private class ContentBody(private val content: SyncContent) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = content.length

        override fun writeTo(sink: BufferedSink) {
            content.open().source().use { sink.writeAll(it) }
        }
    }

    companion object {
        /** What every WebDAV backend id starts with; `SyncSession` picks the account by it. */
        const val ID_PREFIX = "webdav-"
        private const val ID_HEX_CHARS = 16
        private const val MULTI_STATUS = 207
        private val REDIRECTS = 300..399

        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
