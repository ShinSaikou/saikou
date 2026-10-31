package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.webdav.WebDavUrlRefusedException.Reason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Plan unit F's URL rule, one case at a time: release takes `https://` only, debug also takes
 * `http://10.0.2.2` on any port for the fixture on an emulator, and the JVM tests' policy also takes
 * `http://127.0.0.1`. Cleartext is allowed app-wide for sources, so nothing else refuses these.
 */
class WebDavUrlPolicyTest {

    private val release = WebDavUrlPolicy.forBuild(debug = false)
    private val debug = WebDavUrlPolicy.forBuild(debug = true)

    private fun WebDavUrlPolicy.refusal(url: String): Reason = shouldThrow<WebDavUrlRefusedException> { baseUrl(url) }.reason

    @Test
    fun `release refuses http to a private address, the emulator's host and loopback`() {
        for (url in listOf(
            "http://192.168.1.20/dav/",
            "http://10.0.0.5:8080/remote/",
            "http://172.16.4.2/",
            "http://10.0.2.2:18795/dav/",
            "http://127.0.0.1:18795/dav/",
            "http://localhost/dav/",
            "http://sync.example.org/dav/",
        )) {
            release.refusal(url) shouldBe Reason.CLEARTEXT
        }
    }

    @Test
    fun `every build takes https to any host, private addresses included`() {
        for (policy in listOf(release, debug, JVM_TEST_POLICY)) {
            policy.baseUrl("https://sync.example.org/dav/").toString() shouldBe "https://sync.example.org/dav/"
            policy.baseUrl("https://192.168.1.20:8443/remote").toString() shouldBe "https://192.168.1.20:8443/remote/"
        }
    }

    @Test
    fun `debug takes http to the emulator's host on any port and nothing else in cleartext`() {
        debug.baseUrl("http://10.0.2.2:18795/dav/").toString() shouldBe "http://10.0.2.2:18795/dav/"
        debug.baseUrl("http://10.0.2.2/dav").toString() shouldBe "http://10.0.2.2/dav/"
        debug.baseUrl("http://10.0.2.2:8080").toString() shouldBe "http://10.0.2.2:8080/"
        debug.refusal("http://192.168.1.20/dav/") shouldBe Reason.CLEARTEXT
        debug.refusal("http://127.0.0.1:18795/dav/") shouldBe Reason.CLEARTEXT
        debug.refusal("http://10.0.2.3/dav/") shouldBe Reason.CLEARTEXT
    }

    @Test
    fun `the JVM tests' policy also takes http to loopback`() {
        JVM_TEST_POLICY.baseUrl("http://127.0.0.1:41000/dav/").toString() shouldBe "http://127.0.0.1:41000/dav/"
        JVM_TEST_POLICY.refusal("http://192.168.1.20/dav/") shouldBe Reason.CLEARTEXT
    }

    @Test
    fun `an address is normalized, so one server and path has one base url`() {
        release.baseUrl("  HTTPS://Sync.Example.ORG:443/dav  ").toString() shouldBe "https://sync.example.org/dav/"
        release.baseUrl("https://sync.example.org/a%20b/").toString() shouldBe "https://sync.example.org/a%20b/"
        release.baseUrl("https://sync.example.org/a b").toString() shouldBe "https://sync.example.org/a%20b/"
    }

    @Test
    fun `what is not an http address, or carries more than a base, is refused as that`() {
        for (url in listOf("", "sync.example.org/dav/", "ftp://sync.example.org/", "https://", "webdav://host/")) {
            release.refusal(url) shouldBe Reason.NOT_A_URL
        }
        for (url in listOf(
            "https://owner:secret@sync.example.org/dav/",
            "https://owner@sync.example.org/dav/",
            "https://sync.example.org/dav/?a=b",
            "https://sync.example.org/dav/#top",
        )) {
            release.refusal(url) shouldBe Reason.NOT_A_BASE_URL
        }
    }
}
