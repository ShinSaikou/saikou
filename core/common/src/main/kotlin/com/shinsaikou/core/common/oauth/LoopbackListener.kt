package com.shinsaikou.core.common.oauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The one-shot loopback redirect receiver every browser sign-in uses (phase 3 unit O, Q1 and Q13):
 * Google Drive on a port the system picks, the OAuth trackers on the fixed port their clients
 * register ([TRACKER_PORT]).
 *
 * [open] binds `127.0.0.1` alone, never a wildcard address, so nothing off the device can connect;
 * a port that cannot be bound is [LoopbackPortInUseException] at once, never retried on another
 * port, since a provider accepts only the redirect it registered. The listener draws a random
 * [state] for the authorization URL to carry.
 *
 * [await] accepts connections until one asks for [CALLBACK_PATH] with the expected `state`, or until
 * the timeout, counted from [open], passes ([LoopbackTimeoutException]). Each connection is served on its own, since a
 * browser opens speculative connections it may never use:
 *
 * - a request with the expected `state` gets the page that tells the owner to switch back, and ends
 *   the wait: with its `code` ([LoopbackCallback]), or with its `error` ([LoopbackDeniedException]);
 * - with [LoopbackResponse.TOKEN], a request for [CALLBACK_PATH] with no query at all gets a page
 *   whose script asks for the same path again with the redirect's fragment as its query, since a
 *   browser never sends a fragment; that second request is then read as above, its `access_token`
 *   standing for the code;
 * - any other request, a wrong or missing `state` or another path such as the browser's
 *   `/favicon.ico`, gets 404 and the wait goes on;
 * - a connection that does not open with an HTTP/1 request line, or sends nothing, is closed
 *   without an answer.
 *
 * At most [MAX_CONNECTIONS] are served at once; a connection beyond them is closed as soon as it is
 * accepted, unread. Each served connection has [REQUEST_DEADLINE_MILLIS] in all to send its request
 * and take its answer, besides [READ_TIMEOUT_MILLIS] per read, so a connection trickling one byte at
 * a time cannot keep its place (fix round 1 item 3). A flood of connections can so delay the
 * callback by at most that deadline, never tie up threads without bound.
 *
 * The listener then closes, so a second connection is refused. [close] ends a listener nobody
 * awaits. Blocking socket work runs on the IO dispatcher; a cancelled [await] closes everything
 * within [POLL_MILLIS].
 *
 * Nothing it receives is logged: the query of the callback holds the authorization code, or with
 * [LoopbackResponse.TOKEN] the access token itself.
 */
class LoopbackListener private constructor(
    private val server: ServerSocket,
    val state: String,
    private val pages: LoopbackPages,
    private val timeout: Duration,
    timeSource: TimeSource,
    private val response: LoopbackResponse,
) : Closeable {

    /** The port bound: the one asked for, or the one the system chose for 0. */
    val port: Int = server.localPort

    /** `http://127.0.0.1:<port>/callback`, which the authorization URL and the code exchange name. */
    val redirectUri: String = "http://$LOOPBACK_HOST:$port$CALLBACK_PATH"

    private val stateBytes = state.toByteArray(Charsets.US_ASCII)

    /** The timeout counts from the bind, since the browser opens only once the listener is up. */
    private val deadline = timeSource.markNow() + timeout

    /**
     * Waits for the callback, as the class says. One call per listener; the listener is closed when
     * it returns or throws, whichever way.
     *
     * @throws LoopbackDeniedException when the callback carried the expected state and an `error`
     *   instead of a code.
     * @throws LoopbackTimeoutException when no callback carried the expected state in time.
     */
    suspend fun await(): LoopbackCallback = withContext(Dispatchers.IO) {
        val outcome = CompletableDeferred<Outcome>()
        val connections = ConcurrentHashMap.newKeySet<Socket>()
        try {
            coroutineScope {
                try {
                    server.soTimeout = POLL_MILLIS
                    while (!outcome.isCompleted) {
                        ensureActive()
                        if (deadline.hasPassedNow()) throw LoopbackTimeoutException(timeout)
                        val socket = try {
                            server.accept()
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (e: SocketException) {
                            // The connection that ended the wait closed the server under this accept.
                            if (outcome.isCompleted) break
                            throw e
                        }
                        if (connections.size >= MAX_CONNECTIONS) {
                            socket.closeQuietly()
                            continue
                        }
                        connections += socket
                        launch {
                            // Closing the socket ends a blocked read or write; the deadline counts
                            // from the accept, whatever the connection sends meanwhile.
                            val deadline = launch {
                                delay(REQUEST_DEADLINE_MILLIS.milliseconds)
                                socket.closeQuietly()
                            }
                            try {
                                serve(socket, outcome)
                            } finally {
                                deadline.cancel()
                                connections -= socket
                                socket.closeQuietly()
                            }
                        }
                    }
                } finally {
                    // Before the scope waits for its connections: a blocked read ends when its
                    // socket closes, not when its coroutine is cancelled.
                    server.closeQuietly()
                    connections.forEach { it.closeQuietly() }
                }
            }
        } finally {
            server.closeQuietly()
        }
        when (val result = outcome.await()) {
            is Outcome.Code -> result.callback
            is Outcome.Denied -> throw LoopbackDeniedException(result.error)
        }
    }

    override fun close() {
        server.closeQuietly()
    }

    override fun toString(): String = "LoopbackListener(port=$port)"

    /** Answers one connection. A failure on it ends that connection only, never the wait. */
    private fun serve(socket: Socket, outcome: CompletableDeferred<Outcome>) {
        try {
            answer(socket, outcome)
        } catch (_: IOException) {
        }
    }

    private fun answer(socket: Socket, outcome: CompletableDeferred<Outcome>) {
        socket.soTimeout = READ_TIMEOUT_MILLIS
        val target = requestTarget(BufferedInputStream(socket.getInputStream())) ?: return
        val url = "http://$LOOPBACK_HOST:$port$target".toHttpUrlOrNull()
        val output = socket.getOutputStream()
        if (response == LoopbackResponse.TOKEN && url?.encodedPath == CALLBACK_PATH && url.encodedQuery.isNullOrEmpty()) {
            // The implicit grant's redirect, its fragment left behind by the browser.
            output.write(fragmentResponse(pages))
            output.flush()
            return
        }
        val result = url?.takeIf { it.encodedPath == CALLBACK_PATH && stateMatches(it.queryParameter("state")) }
            ?.let(::outcomeOf)
        if (result == null || outcome.isCompleted) {
            output.write(NOT_FOUND)
            output.flush()
            return
        }
        val page = when (result) {
            is Outcome.Code -> pages.signedIn
            is Outcome.Denied -> pages.notSignedIn
        }
        output.write(pageResponse(pages.title, page))
        output.flush()
        if (outcome.complete(result)) server.closeQuietly()
    }

    private fun outcomeOf(url: HttpUrl): Outcome {
        val code = url.queryParameter(response.parameter)
        if (!code.isNullOrEmpty()) {
            val parameters = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
            return Outcome.Code(LoopbackCallback(code, parameters))
        }
        return Outcome.Denied(url.queryParameter("error")?.takeIf { it.isNotEmpty() } ?: NO_CODE)
    }

    private fun stateMatches(received: String?): Boolean =
        received != null && MessageDigest.isEqual(received.toByteArray(Charsets.US_ASCII), stateBytes)

    private sealed interface Outcome {
        class Code(val callback: LoopbackCallback) : Outcome
        class Denied(val error: String) : Outcome
    }

    companion object {

        /** The path of [redirectUri]. */
        const val CALLBACK_PATH = "/callback"

        /**
         * The fixed port the OAuth trackers' clients register, as `http://127.0.0.1:18796/callback`
         * (Q13). Google Drive asks for 0.
         */
        const val TRACKER_PORT = 18796

        /** How long [await] waits for the callback unless told otherwise (Q1). */
        val DEFAULT_TIMEOUT: Duration = 5.minutes

        /** The `error` a callback with the expected state and neither a code (or token) nor an error reads as. */
        const val NO_CODE = "no_code"

        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val STATE_BYTES = 32
        private const val BACKLOG = 8

        /** How often a waiting [await] looks at its deadline and its cancellation. */
        internal const val POLL_MILLIS = 200

        /** The most connections served at once; one more is closed unread (fix round 1 item 3). */
        internal const val MAX_CONNECTIONS = 8

        /** How long one read on a connection may wait for its next bytes. */
        internal const val READ_TIMEOUT_MILLIS = 3_000

        /** How long one connection has, from its accept, to send its request and take its answer. */
        internal const val REQUEST_DEADLINE_MILLIS = 5_000L

        /** The most a request's head may take before the connection is dropped as not HTTP. */
        private const val MAX_HEAD_BYTES = 16 * 1024

        private const val HEAD_END = 0x0D0A0D0A

        private val REQUEST_LINE = Regex("([A-Z]+) (/\\S*) HTTP/1\\.[01]")

        private val NOT_FOUND = (
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)

        /**
         * Binds a listener on `127.0.0.1:[port]` (0 for a port the system chooses), waiting for the
         * provider's answer in the form [response] names.
         *
         * @throws LoopbackPortInUseException when the port cannot be bound; nothing is retried.
         */
        fun open(
            port: Int,
            pages: LoopbackPages,
            timeout: Duration = DEFAULT_TIMEOUT,
            timeSource: TimeSource = TimeSource.Monotonic,
            random: SecureRandom = SecureRandom(),
            response: LoopbackResponse = LoopbackResponse.CODE,
        ): LoopbackListener {
            require(port in 0..65535) { "Not a port: $port" }
            val server = ServerSocket()
            try {
                server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), BACKLOG)
            } catch (e: BindException) {
                server.closeQuietly()
                throw LoopbackPortInUseException(port, e)
            } catch (e: IOException) {
                server.closeQuietly()
                throw e
            }
            return LoopbackListener(server, Pkce.randomToken(random, STATE_BYTES), pages, timeout, timeSource, response)
        }

        /**
         * The request target of an HTTP/1 request, once its whole head has arrived, or null when the
         * connection does not speak HTTP: no request line of the form `METHOD /target HTTP/1.x`, a
         * method other than GET, a head that never ends, or a connection closed first.
         */
        private fun requestTarget(input: InputStream): String? {
            val head = ByteArrayOutputStream()
            // The last four bytes read, which are CR LF CR LF once the head has ended.
            var tail = 0
            while (tail != HEAD_END) {
                val byte = input.read()
                if (byte < 0 || head.size() >= MAX_HEAD_BYTES) return null
                // A request line starts with a method in capitals; a TLS hello or other binary
                // opening is dropped at its first byte instead of after the read timeout.
                if (head.size() == 0 && byte !in 'A'.code..'Z'.code) return null
                head.write(byte)
                tail = (tail shl 8) or byte
            }
            val requestLine = head.toString(Charsets.ISO_8859_1.name()).substringBefore("\r\n")
            val match = REQUEST_LINE.matchEntire(requestLine) ?: return null
            return match.groupValues[2].takeIf { match.groupValues[1] == "GET" }
        }

        private fun pageResponse(title: String, text: String): ByteArray =
            htmlResponse(title, "<p>${text.escapeHtml()}</p>")

        /**
         * The page an implicit grant's redirect gets: its script asks for [CALLBACK_PATH] again with
         * the fragment as the query, replacing this page in the history. With no fragment, or no
         * script, it shows [LoopbackPages.notSignedIn].
         */
        private fun fragmentResponse(pages: LoopbackPages): ByteArray = htmlResponse(
            pages.title,
            "<p id=\"m\" hidden>${pages.notSignedIn.escapeHtml()}</p>" +
                "<noscript><p>${pages.notSignedIn.escapeHtml()}</p></noscript>" +
                "<script>var f = location.hash.substring(1); " +
                "if (f) { location.replace(\"$CALLBACK_PATH?\" + f); } " +
                "else { document.getElementById(\"m\").hidden = false; }</script>",
        )

        private fun htmlResponse(title: String, bodyHtml: String): ByteArray {
            val body = (
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                    "<title>${title.escapeHtml()}</title></head>" +
                    "<body style=\"font-family: sans-serif; margin: 2em;\">$bodyHtml</body></html>"
                ).toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Connection: close\r\n\r\n"
            return head.toByteArray(Charsets.US_ASCII) + body
        }

        private fun String.escapeHtml(): String =
            replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun Closeable.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
            }
        }
    }
}

/**
 * Where a provider puts its answer on the redirect, and which parameter carries the grant: what a
 * [LoopbackListener] waits for.
 */
enum class LoopbackResponse(internal val parameter: String) {

    /** The authorization code grant (RFC 6749 section 4.1.2): `code`, in the query. */
    CODE("code"),

    /**
     * The implicit grant (RFC 6749 section 4.2.2): `access_token`, in the fragment, which the
     * listener's page moves into the query, so [LoopbackCallback.code] is the token itself.
     */
    TOKEN("access_token"),
}

/**
 * The texts of the page the browser shows once the callback arrived: [signedIn] with a code,
 * [notSignedIn] with an error. The app supplies them from its string resources.
 */
class LoopbackPages(val title: String, val signedIn: String, val notSignedIn: String)

/**
 * The callback that carried the expected state and a [code], with every query parameter it carried
 * ([parameters], the code and state included) for a provider that sends more. With
 * [LoopbackResponse.TOKEN], [code] is the access token. [toString] names neither the code nor the
 * parameters.
 */
class LoopbackCallback(val code: String, val parameters: Map<String, String>) {
    override fun toString(): String = "LoopbackCallback(██, parameters=${parameters.keys})"
}

/** Every way a loopback sign-in ends without a code. Each is an [IOException]. */
sealed class LoopbackException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * [port] could not be bound on `127.0.0.1`: another app holds it (Q13's "port in use"). A tracker's
 * fixed port is registered with its provider, so another port would not help.
 */
class LoopbackPortInUseException(val port: Int, cause: BindException) :
    LoopbackException("Port $port on 127.0.0.1 is in use", cause)

/** No callback carried the expected state within [timeout]; the owner did not finish in the browser. */
class LoopbackTimeoutException(val timeout: Duration) :
    LoopbackException("No sign-in arrived within $timeout")

/**
 * The callback carried the expected state and [error] instead of a code: the owner declined, or the
 * provider refused the request (`access_denied`, `invalid_scope` and the like), or
 * [LoopbackListener.NO_CODE] when it carried neither.
 */
class LoopbackDeniedException(val error: String) :
    LoopbackException("The sign-in ended without a code: $error")
