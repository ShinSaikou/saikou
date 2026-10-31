package com.shinsaikou.sync.webdav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Unit D's sync fixture (`tools/bench/sync_fixture_server.py`) as a real process, for the WebDAV
 * backend's JVM tests: python3 on a free loopback port (`--port 0`), up once it prints
 * `ready <port>`, stopped with SIGTERM on [close]. A fixture that cannot start fails the test that
 * asked for it, never skips it.
 *
 * [modes] and [reset] use the fixture's control path, which host tests may call and app code never
 * does (R83 item 2); they go through a plain client, not the sync client. [extraArgs] are more of
 * the fixture's command-line options, such as `--client-secret`.
 */
internal class SyncFixtureProcess(workDir: File, extraArgs: List<String> = emptyList()) : AutoCloseable {

    val log = File(workDir, "fixture-requests.jsonl")
    private val stderr = File(workDir, "fixture-stderr.txt")
    private val process: Process
    val port: Int

    init {
        val script = System.getProperty(FIXTURE_PROPERTY)
            ?: throw AssertionError("$FIXTURE_PROPERTY is not set; the Gradle test task names the fixture")
        if (!File(script).isFile) throw AssertionError("No sync fixture at $script")
        process = ProcessBuilder(
            "python3", script,
            "--log", log.path,
            "--port", "0",
            "--state-root", File(workDir, "fixture-state").path,
            "--dav-user", USER,
            "--dav-password", PASSWORD,
            *extraArgs.toTypedArray(),
        ).redirectError(stderr).start()
        val ready = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
        val line = try {
            ready.get(START_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            process.destroyForcibly()
            throw AssertionError("The sync fixture did not start: $e; stderr: ${stderr.readTextOrEmpty()}", e)
        }
        port = line?.let(READY::matchEntire)?.groupValues?.get(1)?.toInt() ?: run {
            process.destroyForcibly()
            throw AssertionError("The sync fixture printed '$line' instead of its ready line; stderr: ${stderr.readTextOrEmpty()}")
        }
    }

    /** The WebDAV root, reached over loopback as the JVM tests' URL policy allows. */
    val davUrl: String get() = "http://127.0.0.1:$port/dav/"

    /** Sets the fixture's modes from a JSON object, such as `{"lie": true}`. */
    fun modes(json: String) {
        control("modes", json)
    }

    /** Default modes and nothing stored. */
    fun reset() {
        control("reset", "")
    }

    /** The fixture's `state` answer, as JSON text. */
    fun state(): String = control("state", null)

    private fun control(name: String, body: String?): String {
        val request = Request.Builder().url("http://127.0.0.1:$port/_fixture/$name").apply {
            if (body != null) post(body.toRequestBody("application/json".toMediaType()))
        }.build()
        return controlClient.newCall(request).execute().use { response ->
            check(response.code == 200) { "The fixture's $name answered ${response.code}: ${response.body.string()}" }
            response.body.string()
        }
    }

    /**
     * Stops the fixture and returns `[method, path]` of every request it answered, in its log's order.
     * A stop waits for every request being answered and its log line, so none is missing, which a
     * read while the fixture runs cannot promise: a line is written after its answer.
     */
    fun requestsAfterStop(): List<Pair<String, String>> {
        close()
        return log.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["kind"] != null }
            .map { it.text("method") to it.text("path") }
    }

    private fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).content

    override fun close() {
        process.destroy()
        if (!process.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun File.readTextOrEmpty(): String = if (isFile) readText() else ""

    companion object {
        const val USER = "saikou"
        const val PASSWORD = "fixture-dav-password"

        private const val FIXTURE_PROPERTY = "saikou.sync.fixture"
        private const val START_TIMEOUT_S = 30L
        private const val STOP_TIMEOUT_S = 10L
        private val READY = Regex("ready (\\d+)")
        private val controlClient = OkHttpClient()
    }
}
