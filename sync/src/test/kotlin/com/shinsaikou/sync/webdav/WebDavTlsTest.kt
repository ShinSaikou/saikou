package com.shinsaikou.sync.webdav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * A TLS failure by name (plan unit F): an HTTPS server with a self-signed certificate is not
 * trusted, because the sync client trusts only the platform's authorities, and a certificate the
 * client does trust but issued for another name is a host name mismatch. The control case trusts
 * the certificate and names the right host, so both failures are the certificate's and not the
 * test's.
 */
class WebDavTlsTest {

    @TempDir
    lateinit var dir: File

    private val server = MockWebServer()

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun serve(certificate: HeldCertificate) {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        server.enqueue(
            MockResponse.Builder().code(207).setHeader("Content-Type", "application/xml; charset=utf-8").body(
                """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response>
                   <d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                   <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
            ).build(),
        )
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun backend(client: OkHttpClient): WebDavSyncBackend {
        val base = WebDavUrlPolicy.forBuild(debug = false).baseUrl("https://127.0.0.1:${server.port}/dav/")
        return WebDavSyncBackend(base, "owner", "secret", client, Dispatchers.IO)
    }

    @Test
    fun `a self-signed certificate is an untrusted certificate, by name`() {
        serve(certificate("127.0.0.1"))

        val failure = shouldThrow<WebDavUntrustedCertificateException> { runBlocking { backend(syncHttpClient(dir).client).probe() } }

        failure.host shouldBe "127.0.0.1"
        failure.cause.shouldBeInstanceOf<SSLHandshakeException>()
    }

    @Test
    fun `a trusted certificate for another host name is a host name mismatch, by name`() {
        val certificate = certificate("sync.invalid")
        serve(certificate)

        val failure = shouldThrow<WebDavHostNameMismatchException> { runBlocking { backend(syncHttpClient(dir).trusting(certificate)).probe() } }

        failure.host shouldBe "127.0.0.1"
        failure.cause.shouldBeInstanceOf<SSLPeerUnverifiedException>()
    }

    @Test
    fun `a trusted certificate for this host signs in`() {
        val certificate = certificate("127.0.0.1")
        serve(certificate)

        val status = runBlocking { backend(syncHttpClient(dir).trusting(certificate)).probe() }

        status.reachable shouldBe true
        status.authenticated shouldBe true
    }
}
