package com.shinsaikou.sync.webdav

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.sync.SyncContent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A server that does not answer is [WebDavUnreachableException] (phase 3 unit G, fix round 1): a
 * refused connection, an unknown host, no route and a timeout, from the probe the sign-in makes and
 * from every call a sync run makes, with the transport's exception as the cause. Any other transport
 * failure stays OkHttp's own. One case is a real closed port, so the wrapping is not only the
 * interceptor's.
 */
class WebDavUnreachableTest {

    @TempDir
    lateinit var dir: File

    private val base = JVM_TEST_POLICY.baseUrl("http://127.0.0.1:1/dav/")

    private fun backend(failure: IOException): WebDavSyncBackend {
        val failing = OkHttpClient.Builder().addInterceptor(Interceptor { throw failure }).build()
        return WebDavSyncBackend(base, "owner", "secret", failing, Dispatchers.IO)
    }

    private val unreachable = listOf(
        ConnectException("fixture: refused"),
        UnknownHostException("dav.example.invalid"),
        NoRouteToHostException("fixture: no route"),
        SocketTimeoutException("fixture: no answer"),
    )

    @Test
    fun `the probe names each way a server does not answer, with the transport's exception as the cause`() = runBlocking<Unit> {
        for (failure in unreachable) {
            val thrown = shouldThrow<WebDavUnreachableException> { backend(failure).probe() }
            thrown.cause.shouldBeLike(failure)
            thrown.host shouldBe "127.0.0.1"
            ErrorKey.of(thrown) shouldBe ErrorKey.WEBDAV_UNREACHABLE
        }
    }

    @Test
    fun `a sync run's list, read, create and delete name it too`() = runBlocking<Unit> {
        for (failure in unreachable) {
            val backend = backend(failure)
            shouldThrow<WebDavUnreachableException> { backend.list("saikou-sync/v1/") }.cause.shouldBeLike(failure)
            shouldThrow<WebDavUnreachableException> { backend.read("saikou-sync/v1/x") { it.read() } }.cause.shouldBeLike(failure)
            shouldThrow<WebDavUnreachableException> { backend.delete("saikou-sync/v1/x") }.cause.shouldBeLike(failure)
            shouldThrow<WebDavUnreachableException> { backend.create("saikou-sync/v1/x", SyncContent(byteArrayOf(1))) }
                .cause.shouldBeLike(failure)
        }
    }

    @Test
    fun `any other transport failure stays the transport's own`() = runBlocking<Unit> {
        val reset = IOException("fixture: connection reset")

        val thrown = shouldThrow<IOException> { backend(reset).probe() }

        thrown.shouldBeLike(reset)
    }

    @Test
    fun `a real closed port is a server that did not answer`() = runBlocking<Unit> {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val backend = WebDavSyncBackend(
            JVM_TEST_POLICY.baseUrl("http://127.0.0.1:$port/dav/"),
            "owner",
            "secret",
            syncHttpClient(dir).client,
            Dispatchers.IO,
        )

        val thrown = shouldThrow<WebDavUnreachableException> { backend.probe() }

        (thrown.cause is ConnectException) shouldBe true
    }

    // The coroutine machinery may hand back a copy of an exception with a longer stack trace, so
    // the check is the class and the message rather than the instance.
    private fun Throwable?.shouldBeLike(expected: Throwable) {
        this?.javaClass shouldBe expected.javaClass
        this?.message shouldBe expected.message
    }
}
