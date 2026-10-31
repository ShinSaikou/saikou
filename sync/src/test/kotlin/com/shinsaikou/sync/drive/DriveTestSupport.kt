package com.shinsaikou.sync.drive

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SyncContent
import com.shinsaikou.sync.SyncHttpClient
import com.shinsaikou.sync.google.GoogleClientCredentials
import com.shinsaikou.sync.google.GoogleEndpoints
import com.shinsaikou.sync.google.GoogleOAuthClient
import com.shinsaikou.sync.webdav.SyncFixtureProcess
import com.shinsaikou.sync.webdav.TEST_DISPATCHERS
import com.shinsaikou.sync.webdav.syncHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Unit D's fixture as Google for the Drive tests (plan unit H): its authorization, token, revoke and
 * Drive endpoints on one loopback port, reached through a debug build's test base, so every client
 * sends the fixture's public test client ([GoogleClientCredentials.TEST_BASE]) and nothing reaches
 * Google. A sign-in takes its code from the fixture's authorization endpoint with a plain request,
 * which answers the approved consent at once, and no browser.
 */
internal class DriveFixture(val process: SyncFixtureProcess, dir: File) {

    val http: SyncHttpClient = syncHttpClient(File(dir, "cache"))
    val base: String = "http://127.0.0.1:${process.port}"
    val endpoints: GoogleEndpoints = GoogleEndpoints.forBuild(debug = true, testBase = base)

    /** The waits every backoff this fixture built was asked for, in order; none is slept. */
    val waits = mutableListOf<Duration>()

    fun backoff(): DriveBackoff = DriveBackoff(DriveBackoff.WAITS) { synchronized(waits) { waits += it } }

    fun oauth(clock: Clock = Clock.System): GoogleOAuthClient =
        GoogleOAuthClient(http, endpoints, GoogleClientCredentials("", ""), clock, TEST_DISPATCHERS)

    /** A Drive account over [secrets], as the app graph builds one, on this fixture. */
    fun account(secrets: SecretStore, clock: Clock = Clock.System): DriveAccount = DriveAccount(
        secrets = secrets,
        oauthClient = { oauth(clock) },
        httpClient = http,
        availability = DriveAvailability({ endpoints }, GoogleClientCredentials("", "")),
        clock = clock,
        backoff = backoff(),
        dispatchers = TEST_DISPATCHERS,
    )

    /** A code from the fixture's authorization endpoint for [pkce], as a browser would bring it back. */
    fun code(pkce: Pkce): String {
        val url = oauth().authorizationUrl(REDIRECT_URI, "fixture-state", pkce)
        return noRedirects.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.code == 302) { "The authorization endpoint answered ${response.code}" }
            checkNotNull(response.header("Location")?.toHttpUrl()?.queryParameter("code")) { "No code in the redirect" }
        }
    }

    /** [account]'s sign-in with a fresh code: what stage 2's browser sign-in hands over. */
    suspend fun signIn(account: DriveAccount): String {
        val pkce = Pkce.generate()
        return account.signIn(code(pkce), pkce, REDIRECT_URI)
    }

    fun modes(json: String) = process.modes(json)

    /** How many times [reset] has run: [log] reads only what came after the last one. */
    private var resets = 0

    /** Default modes and nothing stored, and a log that starts again from here. */
    fun reset() {
        process.reset()
        resets++
    }

    /**
     * The fixture's log lines of [kinds] (all requests when empty) since the last [reset], in order
     * of arrival. The fixture writes a line after its answer, so this waits up to 5 s until the
     * reset's own line is there and [until] holds, as `16243ca` does. The log file itself runs on
     * across resets; a request sent after the reset's answer started after it.
     */
    fun log(vararg kinds: String, until: (List<JsonObject>) -> Boolean = { true }): List<JsonObject> {
        val deadline = System.nanoTime() + LOG_WAIT_NS
        while (true) {
            val all = process.log.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it["kind"] != null }
                .sortedBy { it.t0() }
            val resetLines = all.filter { it.string("kind") == "control" && it.string("path") == RESET_PATH }
            val since = resetLines.lastOrNull()?.t0() ?: Double.NEGATIVE_INFINITY
            val lines = all.filter {
                it.t0() > since && it.string("kind") != "control" && (kinds.isEmpty() || it.string("kind") in kinds)
            }
            if ((resetLines.size >= resets && until(lines)) || System.nanoTime() > deadline) return lines
            Thread.sleep(10)
        }
    }

    private fun JsonObject.t0(): Double = getValue("t0").jsonPrimitive.content.toDouble()

    companion object {
        /** A loopback redirect the fixture accepts; nothing listens there, since no browser follows it. */
        const val REDIRECT_URI = "http://127.0.0.1:9/callback"

        private const val LOG_WAIT_NS = 5_000_000_000L
        private const val RESET_PATH = "/_fixture/reset"
        private val noRedirects = OkHttpClient.Builder().followRedirects(false).build()
    }
}

/** A string field of a log line, or null when it is absent or not a string. */
internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Deterministic bytes that differ at every offset a chunk could start at. */
internal fun bytes(size: Int, seed: Int = 7): ByteArray = ByteArray(size) { ((it * 31 + seed) xor (it ushr 8)).toByte() }

/**
 * [bytes] as upload content that calls [atOffset] once, the first time any stream it opened is read
 * past [offset]: a hook for a test to change the fixture's modes between two chunks of one upload.
 */
internal fun hookedContent(bytes: ByteArray, offset: Long, atOffset: () -> Unit): SyncContent = object : SyncContent {
    private var fired = false
    override val length: Long = bytes.size.toLong()

    override fun open(): InputStream = object : FilterInputStream(bytes.inputStream()) {
        private var position = 0L

        private fun passed(count: Long) {
            if (count > 0) position += count
            if (!fired && position > offset) {
                fired = true
                atOffset()
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) passed(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { passed(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { passed(it) }
    }
}
