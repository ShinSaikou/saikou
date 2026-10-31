package com.shinsaikou.appupdate

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The updater's version comparison: numbers part by part, a `v` tag, the installed name's suffix. */
class AppVersionTest {

    private fun tag(text: String) = checkNotNull(AppVersion.fromTag(text)) { "$text did not parse" }

    @Test
    fun `parts compare as numbers, not as text`() {
        tag("0.10.0") shouldBeGreaterThan tag("0.9.0")
        tag("1.0.0") shouldBeGreaterThan tag("0.99.99")
        tag("0.0.2") shouldBeGreaterThan tag("0.0.1")
        tag("0.0.1") shouldBeLessThan tag("0.0.10")
    }

    @Test
    fun `a missing part counts as zero`() {
        tag("0.1") shouldBe tag("0.1.0")
        tag("0.1.1") shouldBeGreaterThan tag("0.1")
    }

    @Test
    fun `a tag reads with or without its v`() {
        tag("v0.0.2") shouldBe tag("0.0.2")
        tag("V1.2.3").toString() shouldBe "1.2.3"
        tag(" v0.0.2 ").toString() shouldBe "0.0.2"
    }

    @Test
    fun `a tag that is not a version does not parse`() {
        AppVersion.fromTag("").shouldBeNull()
        AppVersion.fromTag("v").shouldBeNull()
        AppVersion.fromTag("latest").shouldBeNull()
        AppVersion.fromTag("v0.0.2-beta").shouldBeNull()
        AppVersion.fromTag("0..2").shouldBeNull()
        AppVersion.fromTag("1.2.").shouldBeNull()
        AppVersion.fromTag("99999999999.0").shouldBeNull()
    }

    @Test
    fun `the installed version name drops its build type suffix`() {
        AppVersion.fromVersionName("0.0.1-debug") shouldBe tag("0.0.1")
        AppVersion.fromVersionName("0.0.1+abc") shouldBe tag("0.0.1")
        AppVersion.fromVersionName("0.0.1") shouldBe tag("0.0.1")
        AppVersion.fromVersionName("debug").shouldBeNull()
    }
}
