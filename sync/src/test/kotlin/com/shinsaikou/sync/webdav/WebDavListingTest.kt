package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.RemoteFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress

/**
 * Listings the sync fixture never sends, from a stub server: hrefs written as full URLs and as
 * relative paths, properties split across a 200 and a 404 `propstat`, a file whose
 * `getlastmodified` is missing or unreadable, and a document type the parser must refuse. Each
 * answer is a canned multistatus for the collection asked for, through the real sync client.
 */
class WebDavListingTest {

    @TempDir
    lateinit var dir: File

    private val server = MockWebServer()

    /** The multistatus body for each collection path the stub is asked for. */
    private val answers = mutableMapOf<String, String>()

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun backend(): WebDavSyncBackend {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = answers[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(207).setHeader("Content-Type", "application/xml; charset=utf-8").body(body).build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val base = JVM_TEST_POLICY.baseUrl("$origin/remote/dav/")
        return WebDavSyncBackend(base, "owner", "secret", syncHttpClient(dir).client, Dispatchers.IO)
    }

    private val origin: String get() = "http://127.0.0.1:${server.port}"

    private fun multistatus(vararg responses: String): String =
        """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">${responses.joinToString("")}</D:multistatus>"""

    private fun collection(href: String): String =
        """<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype>
           <D:getlastmodified>Sat, 26 Sep 2026 10:00:00 GMT</D:getlastmodified></D:prop>
           <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"""

    private fun file(href: String, length: Long, lastModified: String?): String =
        """<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype/>
           <D:getcontentlength>$length</D:getcontentlength>
           ${lastModified?.let { "<D:getlastmodified>$it</D:getlastmodified>" }.orEmpty()}</D:prop>
           <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
           <D:propstat><D:prop>${if (lastModified == null) "<D:getlastmodified/>" else ""}<D:quota-used-bytes/></D:prop>
           <D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>"""

    private fun list(backend: WebDavSyncBackend, prefix: String): List<RemoteFile> = runBlocking { backend.list(prefix) }

    @Test
    fun `hrefs as full urls, absolute paths and relative paths, percent-encoded, map to backend paths`() {
        val backend = backend()
        answers["/remote/dav/saikou-sync/v1/"] = multistatus(
            collection("/remote/dav/saikou-sync/v1/"),
            collection("$origin/remote/dav/saikou-sync/v1/devices/"),
            file("snapshot-1-device%2D1.jsonl.gz", 40, "Sat, 26 Sep 2026 10:00:01 GMT"),
        )
        answers["/remote/dav/saikou-sync/v1/devices/"] = multistatus(
            collection("$origin/remote/dav/saikou-sync/v1/devices/"),
            collection("a%20b/"),
        )
        answers["/remote/dav/saikou-sync/v1/devices/a%20b/"] = multistatus(
            collection("/remote/dav/saikou-sync/v1/devices/a%20b/"),
            file("/remote/dav/saikou-sync/v1/devices/a%20b/%C3%BC%231.bin", 7, "Sat, 26 Sep 2026 10:00:02 GMT"),
        )

        list(backend, "saikou-sync/v1/") shouldContainExactlyInAnyOrder listOf(
            RemoteFile("saikou-sync/v1/snapshot-1-device-1.jsonl.gz", 40, 1_790_416_801_000),
            RemoteFile("saikou-sync/v1/devices/a b/ü#1.bin", 7, 1_790_416_802_000),
        )
    }

    @Test
    fun `a file without getlastmodified fails the listing and names the file`() {
        val backend = backend()
        answers["/remote/dav/saikou-sync/v1/"] = multistatus(
            collection("/remote/dav/saikou-sync/v1/"),
            file("/remote/dav/saikou-sync/v1/snapshot-1-a.jsonl.gz", 40, "Sat, 26 Sep 2026 10:00:01 GMT"),
            file("/remote/dav/saikou-sync/v1/snapshot-2-a.jsonl.gz", 40, null),
        )

        val failure = shouldThrow<WebDavMalformedResponseException> { list(backend, "saikou-sync/v1/") }
        failure.message shouldContain "saikou-sync/v1/snapshot-2-a.jsonl.gz"
        failure.message shouldContain "getlastmodified"
    }

    @Test
    fun `a getlastmodified that is not an RFC 1123 date fails the listing the same way`() {
        val backend = backend()
        answers["/remote/dav/saikou-sync/v1/"] = multistatus(
            collection("/remote/dav/saikou-sync/v1/"),
            file("/remote/dav/saikou-sync/v1/snapshot-1-a.jsonl.gz", 40, "2026-09-26T10:00:01Z"),
        )

        shouldThrow<WebDavMalformedResponseException> { list(backend, "saikou-sync/v1/") }.message shouldContain
            "snapshot-1-a.jsonl.gz no getlastmodified it can read (2026-09-26T10:00:01Z)"
    }

    @Test
    fun `an href outside the base address fails the listing`() {
        val backend = backend()
        answers["/remote/dav/saikou-sync/v1/"] = multistatus(
            collection("/remote/dav/saikou-sync/v1/"),
            file("/elsewhere/snapshot-1-a.jsonl.gz", 40, "Sat, 26 Sep 2026 10:00:01 GMT"),
        )

        shouldThrow<WebDavMalformedResponseException> { list(backend, "saikou-sync/v1/") }.message shouldContain
            "/elsewhere/snapshot-1-a.jsonl.gz"
    }

    @Test
    fun `a document type or an external entity ends the parse without fetching anything`() {
        val backend = backend()
        val entity = "$origin/entity.txt"
        answers["/remote/dav/saikou-sync/v1/"] = """<?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE D:multistatus [<!ENTITY outside SYSTEM "$entity">]>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>&outside;</D:href></D:response></D:multistatus>"""

        shouldThrow<WebDavMalformedResponseException> { list(backend, "saikou-sync/v1/") }
        server.requestCount shouldBe 1
    }

    @Test
    fun `a probe of an address that is a file, not a collection, is refused`() {
        val backend = backend()
        answers["/remote/dav/"] = multistatus(file("/remote/dav/", 10, "Sat, 26 Sep 2026 10:00:01 GMT"))

        shouldThrow<WebDavMalformedResponseException> { runBlocking { backend.probe() } }.message shouldContain
            "is not a WebDAV collection"
    }
}
