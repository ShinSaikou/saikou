package com.shinsaikou.signin

import com.shinsaikou.BuildConfig
import com.shinsaikou.sync.google.GoogleEndpoints
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The graph's one Google endpoints binding reads the test base in the debug build (phase 3 unit O).
 * `:app` has unit tests for the debug variant only, so the release build's path is pinned through
 * `GoogleBindings.endpointsFor(debug = false, ...)`, the call the provider makes in a release build:
 * it never reads the test base (fix round 1 item 1).
 */
class GoogleBindingsTest {

    private val testBase = GoogleTestBase()

    @Test
    fun `the debug build sends every Google call to the test base once one is set`() {
        BuildConfig.DEBUG shouldBe true
        GoogleBindings.provideGoogleEndpoints(lazyOf(testBase)) shouldBe GoogleEndpoints.GOOGLE

        testBase.value = "http://10.0.2.2:18797"
        val endpoints = GoogleBindings.provideGoogleEndpoints(lazyOf(testBase))
        endpoints.isGoogle shouldBe false
        endpoints.authorization.toString() shouldBe "http://10.0.2.2:18797/o/oauth2/v2/auth"
        endpoints.token.toString() shouldBe "http://10.0.2.2:18797/token"
        endpoints.revoke.toString() shouldBe "http://10.0.2.2:18797/revoke"
        endpoints.driveBase.toString() shouldBe "http://10.0.2.2:18797/"
    }

    @Test
    fun `a blank test base is no test base`() {
        testBase.value = "  "
        testBase.value shouldBe null
        GoogleBindings.provideGoogleEndpoints(lazyOf(testBase)) shouldBe GoogleEndpoints.GOOGLE
    }

    @Test
    fun `the release build's endpoints are Google's without reading the test base`() {
        var read = 0
        val lazyBase = lazy {
            read++
            testBase.apply { value = "http://10.0.2.2:18797" }
        }

        GoogleBindings.endpointsFor(debug = false) { lazyBase.value.value } shouldBe GoogleEndpoints.GOOGLE
        GoogleBindings.endpointsFor(debug = false) { throw AssertionError("a release build read the test base") } shouldBe
            GoogleEndpoints.GOOGLE
        read shouldBe 0
        lazyBase.isInitialized() shouldBe false

        GoogleBindings.endpointsFor(debug = true) { lazyBase.value.value }.isGoogle shouldBe false
        read shouldBe 1
    }
}
