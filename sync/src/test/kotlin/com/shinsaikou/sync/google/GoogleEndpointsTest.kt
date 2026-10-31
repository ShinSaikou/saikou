package com.shinsaikou.sync.google

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * The one binding every Google address comes from (phase 3 unit O). The release variant is
 * `forBuild(debug = false, ...)`, the call the app graph makes in a release build; `:app` has unit
 * tests for its debug variant only, so the release half is proved here.
 */
class GoogleEndpointsTest {

    @Test
    fun `a release build ignores the test argument and talks to Google`() {
        for (argument in listOf("http://10.0.2.2:18797", "https://fixture.example.invalid:8443", "", null)) {
            GoogleEndpoints.forBuild(debug = false, testBase = argument) shouldBeSameInstanceAs GoogleEndpoints.GOOGLE
        }
        // Not even a malformed one is read.
        GoogleEndpoints.forBuild(debug = false, testBase = "not a url") shouldBeSameInstanceAs GoogleEndpoints.GOOGLE
    }

    @Test
    fun `Google's own addresses`() {
        val google = GoogleEndpoints.GOOGLE
        google.authorization.toString() shouldBe "https://accounts.google.com/o/oauth2/v2/auth"
        google.token.toString() shouldBe "https://oauth2.googleapis.com/token"
        google.revoke.toString() shouldBe "https://oauth2.googleapis.com/revoke"
        google.driveBase.toString() shouldBe "https://www.googleapis.com/"
        google.isGoogle shouldBe true
    }

    @Test
    fun `a debug build with a test argument moves all four to its scheme, host and port and keeps every path`() {
        val fixture = GoogleEndpoints.forBuild(debug = true, testBase = " http://10.0.2.2:18797 ")

        fixture.authorization.toString() shouldBe "http://10.0.2.2:18797/o/oauth2/v2/auth"
        fixture.token.toString() shouldBe "http://10.0.2.2:18797/token"
        fixture.revoke.toString() shouldBe "http://10.0.2.2:18797/revoke"
        fixture.driveBase.toString() shouldBe "http://10.0.2.2:18797/"
        fixture.driveBase.resolve("upload/drive/v3/files").toString() shouldBe "http://10.0.2.2:18797/upload/drive/v3/files"
        fixture.isGoogle shouldBe false
        // A path in the argument is not a prefix: the fixture's paths are Google's (R83 item 1).
        GoogleEndpoints.forBuild(debug = true, testBase = "http://10.0.2.2:18797/ignored/").token.toString() shouldBe
            "http://10.0.2.2:18797/token"
    }

    @Test
    fun `a debug build without a test argument talks to Google, and a malformed one is refused`() {
        GoogleEndpoints.forBuild(debug = true, testBase = null) shouldBeSameInstanceAs GoogleEndpoints.GOOGLE
        GoogleEndpoints.forBuild(debug = true, testBase = "  ") shouldBeSameInstanceAs GoogleEndpoints.GOOGLE
        shouldThrow<IllegalArgumentException> { GoogleEndpoints.forBuild(debug = true, testBase = "10.0.2.2:18797") }
    }
}
