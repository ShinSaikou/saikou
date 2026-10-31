package com.shinsaikou.di

import com.shinsaikou.BuildConfig
import com.shinsaikou.sync.webdav.WebDavUrlRefusedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The graph's WebDAV URL policy follows the build type (phase 3 unit F): the debug build takes
 * `http://10.0.2.2` for the sync fixture on an emulator. `:app` has unit tests for the debug
 * variant only, so the release policy's refusals are `:sync`'s `WebDavUrlPolicyTest`, over
 * `WebDavUrlPolicy.forBuild(debug = false)`, the call this provider makes in a release build.
 */
class WebDavUrlPolicyBindingTest {

    private val policy = PlatformBindings.provideWebDavUrlPolicy()

    @Test
    fun `the debug build's graph takes the emulator's fixture address`() {
        BuildConfig.DEBUG shouldBe true
        val fixture = "http://10.0.2.2:18795/dav/"
        policy.baseUrl(fixture).toString() shouldBe fixture
    }

    @Test
    fun `no build takes cleartext to a private address other than the emulator's host`() {
        shouldThrow<WebDavUrlRefusedException> { policy.baseUrl("http://192.168.1.20/dav/") }.reason shouldBe
            WebDavUrlRefusedException.Reason.CLEARTEXT
        policy.baseUrl("https://192.168.1.20/dav/").toString() shouldBe "https://192.168.1.20/dav/"
    }
}
