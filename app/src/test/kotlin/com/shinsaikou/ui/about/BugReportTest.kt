package com.shinsaikou.ui.about

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** The bug report's text: the header, the crash and the log, and nothing that looks like a credential. */
class BugReportTest {

    private val info = DeviceInfo(
        appName = "Shin Saikou Debug",
        versionName = "0.1.2-debug",
        versionCode = 5,
        buildType = "publicDebug",
        debuggable = true,
        commit = "abc1234",
        packageName = "com.shinsaikou.debug",
        androidRelease = "8.0.0",
        sdk = 26,
        manufacturer = "Google",
        model = "Pixel 9",
        abis = listOf("x86_64", "x86"),
    )

    @Test
    fun `the header names the build and the device on six lines`() {
        info.header() shouldBe """
            Shin Saikou Debug 0.1.2-debug (5)
            Build: Debug (publicDebug), commit abc1234
            Package: com.shinsaikou.debug
            Android 8.0.0 (API 26)
            Device: Google Pixel 9
            ABIs: x86_64, x86
        """.trimIndent()
        info.copy(debuggable = false, buildType = "release").header() shouldContain "Build: Release (release), commit abc1234"
    }

    @Test
    fun `the report holds the header, the newest crash and the log, in that order`() {
        val text = BugReport.text(
            info = info,
            writtenAt = "2026-11-03T10:00:00Z",
            crash = CrashFile("crash-1.txt", "java.lang.IllegalStateException: boom\n"),
            log = "11-03 10:00:00.000  100  100 I Saikou: started\n",
        )

        text shouldStartWith info.header()
        text shouldContain "Written: 2026-11-03T10:00:00Z"
        val crash = text.indexOf("== Newest crash log: crash-1.txt ==")
        val log = text.indexOf("== Log of this process, last 2000 lines ==")
        (crash in 0 until log) shouldBe true
        text shouldContain "java.lang.IllegalStateException: boom"
        text shouldContain "I Saikou: started"
    }

    @Test
    fun `without a crash the section is left out, and a log that could not be read says so`() {
        val text = BugReport.text(info, "now", crash = null, log = null)

        text shouldNotContain "Newest crash log"
        text shouldContain "The log could not be read."
    }

    @Test
    fun `only the log's last lines are kept`() {
        val log = (1..2_500).joinToString("\n") { "line $it" }

        val kept = BugReport.lastLines(log).lines()

        kept.size shouldBe BugReport.LOG_LINES
        kept.first() shouldBe "line 501"
        kept.last() shouldBe "line 2500"
    }

    @Test
    fun `credential headers keep their name and lose their value`() {
        val log = """
            I okhttp.OkHttpClient: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig
            I okhttp.OkHttpClient: cookie: cf_clearance=abc; session=def
            I okhttp.OkHttpClient: Set-Cookie: sid=xyz; Path=/
            I okhttp.OkHttpClient: Proxy-Authorization: Basic dXNlcjpwYXNz
            I okhttp.OkHttpClient: X-Api-Key: 12345
        """.trimIndent()

        val scrubbed = BugReport.scrub(log)

        scrubbed shouldBe """
            I okhttp.OkHttpClient: Authorization: <redacted>
            I okhttp.OkHttpClient: cookie: <redacted>
            I okhttp.OkHttpClient: Set-Cookie: <redacted>
            I okhttp.OkHttpClient: Proxy-Authorization: <redacted>
            I okhttp.OkHttpClient: X-Api-Key: <redacted>
        """.trimIndent()
    }

    @Test
    fun `bearer values, tokens, codes and secrets in a query, a form or JSON are redacted`() {
        val log = """
            W Tracker: refresh failed for bearer abc.def-ghi
            I Loopback: http://127.0.0.1:18796/callback?code=4/0AbCdEf&state=s1
            I Loopback: callback#access_token=tok123&token_type=Bearer&expires_in=3600
            I Sync: grant_type=refresh_token&refresh_token=1//0gXyZ&client_secret=GOCSPX-1
            E Sync: {"access_token":"ya29.a0","id_token" : "eyJ","expires_in":3599,"code":"4/0Xy"}
            I Login: password=hunter2, apikey=k1
        """.trimIndent()

        val scrubbed = BugReport.scrub(log)

        listOf("abc.def-ghi", "4/0AbCdEf", "4/0Xy", "tok123", "1//0gXyZ", "GOCSPX-1", "ya29.a0", "eyJ", "hunter2", "k1")
            .forEach { scrubbed shouldNotContain it }
        scrubbed shouldContain "bearer <redacted>"
        scrubbed shouldContain "code=<redacted>&state=s1"
        scrubbed shouldContain "access_token=<redacted>&token_type=Bearer&expires_in=3600"
        scrubbed shouldContain "grant_type=refresh_token&refresh_token=<redacted>&client_secret=<redacted>"
        scrubbed shouldContain "\"access_token\":\"<redacted>\""
        scrubbed shouldContain "\"id_token\" : \"<redacted>\""
        scrubbed shouldContain "\"expires_in\":3599"
    }

    @Test
    fun `a code that starts a form body is redacted, at a line's start or after a space`() {
        val log = "code=4/0AbCdEf&grant_type=authorization_code\n" +
            "I Sync: body code=4/0GhIjK&redirect_uri=http://127.0.0.1:18796/callback\n" +
            "I Sync:\tcode=4/0LmNoP&client_id=c1"

        val scrubbed = BugReport.scrub(log)

        listOf("4/0AbCdEf", "4/0GhIjK", "4/0LmNoP").forEach { scrubbed shouldNotContain it }
        scrubbed shouldBe "code=<redacted>&grant_type=authorization_code\n" +
            "I Sync: body code=<redacted>&redirect_uri=http://127.0.0.1:18796/callback\n" +
            "I Sync:\tcode=<redacted>&client_id=c1"
    }

    @Test
    fun `a code in a query or a fragment is redacted wherever it stands among the parameters`() {
        val log = "GET /callback?code=4/0Q1 HTTP/1.1\n" +
            "callback?state=s1&code=4/0Q2\n" +
            "callback#code=4/0Q3&state=s2"

        BugReport.scrub(log) shouldBe "GET /callback?code=<redacted> HTTP/1.1\n" +
            "callback?state=s1&code=<redacted>\n" +
            "callback#code=<redacted>&state=s2"
    }

    @Test
    fun `the runtime's own code lines are left alone, at a line's start too`() {
        val log = "code=30KB, data=27KB\n" +
            "I zygote64: Do full code cache collection, code=124KB, data=101KB\n" +
            "code=30KB"

        BugReport.scrub(log) shouldBe log
    }

    @Test
    fun `words that only contain a secret's name are left alone`() {
        val log = "I Updater: versionCode=5 encode=utf-8 OAuth: started, author: someone\n" +
            "I zygote64: Do partial code cache collection, code=30KB, data=27KB"

        BugReport.scrub(log) shouldBe log
    }

    @Test
    fun `the report itself is scrubbed, its crash included`() {
        val text = BugReport.text(
            info = info,
            writtenAt = "now",
            crash = CrashFile("crash-2.txt", "java.io.IOException: https://example.org/feed?token=secret1"),
            log = "Authorization: Bearer secret2",
        )

        text shouldNotContain "secret1"
        text shouldNotContain "secret2"
    }
}
