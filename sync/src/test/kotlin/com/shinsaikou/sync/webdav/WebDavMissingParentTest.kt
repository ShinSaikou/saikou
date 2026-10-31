package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
import java.util.Collections

/**
 * A server that answers a `PUT` into a missing collection with 404 instead of RFC 4918's 409, as
 * sabre/dav before 4.1.3 does, and so Nextcloud and ownCloud (unit F's fix round). The stub keeps
 * a set of collections and the stored files: `MKCOL` makes one (405 when it exists, 409 without its
 * parent), a `PUT` without its parent collection is 404, and a `GET` answers what was stored. D's
 * fixture answers 409 and is covered by `WebDavSyncBackendTest`.
 */
class WebDavMissingParentTest {

    @TempDir
    lateinit var dir: File

    private val server = MockWebServer()
    private val collections = Collections.synchronizedSet(mutableSetOf("/dav/"))
    private val files = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    /** When true, a PUT is 404 even once its parent exists, as a server that never stores there. */
    private var alwaysMissing = false

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/') + "/"

    private fun backend(): WebDavSyncBackend {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val answer = when (request.method) {
                    "MKCOL" -> when {
                        path in collections -> 405
                        parentOf(path) !in collections -> 409
                        else -> 201.also { collections += path }
                    }
                    "PUT" -> if (alwaysMissing || parentOf(path) !in collections) 404 else 201.also { files[path] = request.body!!.toByteArray() }
                    "GET" -> if (path in files) 200 else 404
                    else -> 405
                }
                requests += "${request.method} $path $answer"
                return MockResponse.Builder().code(answer).apply { files[path]?.takeIf { answer == 200 }?.let { body(okio.Buffer().write(it)) } }.build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        return WebDavSyncBackend(
            JVM_TEST_POLICY.baseUrl("http://127.0.0.1:${server.port}/dav/"),
            "owner",
            "secret",
            syncHttpClient(dir).client,
            Dispatchers.IO,
        )
    }

    @Test
    fun `a 404 for a missing parent makes each collection from the root down and writes once more`() {
        val backend = backend()
        val path = SyncLayout.segmentPath("device-1", 0, 5)
        val content = ByteArray(100) { it.toByte() }

        runBlocking { backend.create(path, SyncContent(content)) }

        files["/dav/$path"]!!.toList() shouldBe content.toList()
        requests shouldBe listOf(
            "PUT /dav/saikou-sync/v1/devices/device-1/0-5.jsonl.gz 404",
            "MKCOL /dav/saikou-sync/ 201",
            "MKCOL /dav/saikou-sync/v1/ 201",
            "MKCOL /dav/saikou-sync/v1/devices/ 201",
            "MKCOL /dav/saikou-sync/v1/devices/device-1/ 201",
            "PUT /dav/saikou-sync/v1/devices/device-1/0-5.jsonl.gz 201",
            "GET /dav/saikou-sync/v1/devices/device-1/0-5.jsonl.gz 200",
        )
    }

    @Test
    fun `a second 404 after the parents exist fails the write as before`() {
        val backend = backend()
        alwaysMissing = true

        shouldThrow<WebDavHttpException> {
            runBlocking { backend.create(SyncLayout.segmentPath("device-1", 0, 5), SyncContent(ByteArray(10))) }
        }.code shouldBe 404
        requests.count { it.startsWith("PUT") } shouldBe 2
    }
}
