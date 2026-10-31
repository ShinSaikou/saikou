package com.shinsaikou.core.common.oauth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * The loopback listener over real sockets on `127.0.0.1`, spoken to by a raw socket so every byte of
 * each answer, and the lack of one, is visible. The five-minute timeout runs on a test time source.
 */
class LoopbackListenerTest {

    private val pages = LoopbackPages(title = "Saikou", signedIn = "Signed in, switch back", notSignedIn = "Not signed in")
    private val opened = mutableListOf<LoopbackListener>()

    @AfterEach
    fun closeAll() {
        opened.forEach { it.close() }
    }

    private fun open(port: Int = 0, timeSource: TestTimeSource = TestTimeSource()) =
        LoopbackListener.open(port, pages, timeSource = timeSource).also(opened::add)

    private fun CoroutineScope.waiting(listener: LoopbackListener): Deferred<Result<LoopbackCallback>> =
        async(Dispatchers.IO) { runCatching { listener.await() } }

    @Test
    fun `port 0 binds a port the system chose, and the callback with the expected state and a code ends the wait`() {
        runBlocking {
            val listener = open()
            listener.port shouldBeGreaterThan 0
            listener.redirectUri shouldBe "http://127.0.0.1:${listener.port}/callback"
            val result = waiting(listener)

            val answer = get(listener.port, "/callback?state=${listener.state}&code=4%2F0abc&scope=drive")

            answer shouldStartWith "HTTP/1.1 200 OK\r\n"
            answer shouldContain "Signed in, switch back"
            answer shouldContain "Cache-Control: no-store"
            val callback = withTimeout(5.seconds) { result.await() }.getOrThrow()
            callback.code shouldBe "4/0abc"
            callback.parameters.keys shouldContainExactlyInAnyOrder listOf("state", "code", "scope")
            callback.toString() shouldNotContain "4/0abc"
        }
    }

    @Test
    fun `a wrong or missing state and any other path are 404, and the wait goes on`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)

            get(listener.port, "/callback?state=not-the-state&code=stolen") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            get(listener.port, "/callback?code=stolen") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            get(listener.port, "/favicon.ico") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            get(listener.port, "/other?state=${listener.state}&code=abc") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            result.isCompleted shouldBe false

            get(listener.port, "/callback?state=${listener.state}&code=real") shouldStartWith "HTTP/1.1 200 OK\r\n"
            withTimeout(5.seconds) { result.await() }.getOrThrow().code shouldBe "real"
        }
    }

    @Test
    fun `a connection that does not speak HTTP is closed without an answer, and the wait goes on`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)

            // A TLS client hello's first bytes, then a line that is not a request line.
            exchange(listener.port, byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00)) shouldBe ""
            exchange(listener.port, "HELLO\r\n\r\n".toByteArray()) shouldBe ""
            exchange(listener.port, "GET /callback SMTP/1.0\r\n\r\n".toByteArray()) shouldBe ""
            // A POST with the right state is not the browser's redirect either.
            exchange(listener.port, "POST /callback?state=${listener.state}&code=x HTTP/1.1\r\n\r\n".toByteArray()) shouldBe ""
            result.isCompleted shouldBe false

            get(listener.port, "/callback?state=${listener.state}&code=real") shouldStartWith "HTTP/1.1 200 OK\r\n"
            withTimeout(5.seconds) { result.await() }.getOrThrow().code shouldBe "real"
        }
    }

    /** A browser opens connections it never sends on; one of them must not hold the callback up. */
    @Test
    fun `a connection that sends nothing does not delay the callback on another`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)
            Socket(LOOPBACK, listener.port).use {
                delay(300.milliseconds)
                val sent = TimeSource.Monotonic.markNow()
                get(listener.port, "/callback?state=${listener.state}&code=real") shouldStartWith "HTTP/1.1 200 OK\r\n"
                withTimeout(2.seconds) { result.await() }.getOrThrow().code shouldBe "real"
                // Well inside the time the idle connection would hold a listener that served one at a
                // time; the blocking get is timed, since no coroutine timeout can cut it short.
                sent.elapsedNow() shouldBeLessThan 2.seconds
            }
        }
    }

    /**
     * Fix round 1 item 3: three times the cap in idle connections. The listener serves the cap and
     * closes the rest unread at once; the callback, turned away while the idle ones hold every place
     * (a browser shows an error and the owner reloads, which [getOnceServed] stands for), arrives once
     * the per-read timeout has cut them, inside the whole-request deadline.
     *
     * The flood is opened one extra at a time, each seen closed before the next connects (R93
     * carry-over 4). Opened all at once it overflowed the listener's accept queue of 8
     * whenever the accept loop fell behind: the kernel drops a SYN on a full queue and the client
     * sends it again a second later, so under load the flood took over 2 s to open and the count
     * ran past the 3 s per-read timeout that closes a served idle connection, finding 6 of 8 open.
     * Only the accept loop adds to the listener's set of served connections, so the cap itself was
     * never exceeded; the listener was right and the test's timing was not.
     */
    @Test
    fun `a flood of idle connections is capped, the extras closed unread, and the callback still arrives`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)
            val start = TimeSource.Monotonic.markNow()
            val flood = mutableListOf<Socket>()
            try {
                // The first MAX_CONNECTIONS wait in the accept queue, which holds them all.
                repeat(LoopbackListener.MAX_CONNECTIONS) { flood += Socket(LOOPBACK, listener.port) }
                repeat(LoopbackListener.MAX_CONNECTIONS * 2) {
                    val extra = Socket(LOOPBACK, listener.port).also { flood += it }
                    // Accepted after every one before it, and closed unread at once.
                    withContext(Dispatchers.IO) { extra.closedWithin(2.seconds) }.shouldNotBeNull()
                }
                val served = flood.take(LoopbackListener.MAX_CONNECTIONS)
                served.count { it.isStillOpen() } shouldBe LoopbackListener.MAX_CONNECTIONS
                // The count means something only while no served connection could have timed out.
                start.elapsedNow() shouldBeLessThan LoopbackListener.READ_TIMEOUT_MILLIS.milliseconds
                result.isCompleted shouldBe false

                val sent = TimeSource.Monotonic.markNow()
                getOnceServed(listener.port, "/callback?state=${listener.state}&code=real") shouldStartWith "HTTP/1.1 200 OK\r\n"
                withTimeout(2.seconds) { result.await() }.getOrThrow().code shouldBe "real"
                sent.elapsedNow() shouldBeLessThan LoopbackListener.REQUEST_DEADLINE_MILLIS.milliseconds + 2.seconds
            } finally {
                flood.forEach { it.close() }
            }
        }
    }

    /**
     * Fix round 1 item 3: a byte every 250 ms never trips the per-read timeout, and the head never
     * ends; the whole-request deadline closes the connection, and the wait goes on.
     */
    @Test
    fun `a connection trickling its request is cut at the whole-request deadline`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)
            val deadline = LoopbackListener.REQUEST_DEADLINE_MILLIS.milliseconds

            val cutAfter = withContext(Dispatchers.IO) {
                Socket(LOOPBACK, listener.port).use { socket ->
                    val connected = TimeSource.Monotonic.markNow()
                    val closedBy = async(Dispatchers.IO) {
                        socket.soTimeout = (deadline + 5.seconds).inWholeMilliseconds.toInt()
                        try {
                            socket.getInputStream().read()
                        } catch (_: SocketException) {
                        }
                        connected.elapsedNow()
                    }
                    val line = "GET /callback?state=${listener.state}&code=slow HTTP/1.1\r\nX-Trickle: ".toByteArray()
                    var sent = 0
                    try {
                        while (!closedBy.isCompleted && connected.elapsedNow() < deadline + 4.seconds) {
                            socket.getOutputStream().apply { write(line[sent % line.size].toInt()); flush() }
                            sent++
                            delay(250.milliseconds)
                        }
                    } catch (_: SocketException) {
                    }
                    closedBy.await()
                }
            }

            cutAfter shouldBeGreaterThan deadline - 500.milliseconds
            cutAfter shouldBeLessThan deadline + 2.seconds
            result.isCompleted shouldBe false
            get(listener.port, "/callback?state=${listener.state}&code=real") shouldStartWith "HTTP/1.1 200 OK\r\n"
            withTimeout(5.seconds) { result.await() }.getOrThrow().code shouldBe "real"
        }
    }

    @Test
    fun `the listener is one-shot, so a second connection after the callback is refused`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)
            get(listener.port, "/callback?state=${listener.state}&code=first")
            withTimeout(5.seconds) { result.await() }.getOrThrow().code shouldBe "first"

            shouldThrow<ConnectException> { get(listener.port, "/callback?state=${listener.state}&code=second") }
        }
    }

    @Test
    fun `a callback with the expected state and an error ends the wait as denied, with the page saying so`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)

            val answer = get(listener.port, "/callback?state=${listener.state}&error=access_denied")

            answer shouldStartWith "HTTP/1.1 200 OK\r\n"
            answer shouldContain "Not signed in"
            val error = withTimeout(5.seconds) { result.await() }.exceptionOrNull()
            (error as LoopbackDeniedException).error shouldBe "access_denied"
        }
    }

    @Test
    fun `a callback with the expected state and neither a code nor an error is denied as no code`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)
            get(listener.port, "/callback?state=${listener.state}")
            (withTimeout(5.seconds) { result.await() }.exceptionOrNull() as LoopbackDeniedException).error shouldBe
                LoopbackListener.NO_CODE
        }
    }

    /**
     * An implicit grant's redirect carries the token in its fragment, which a browser never sends: a
     * request with no query gets the page that asks again with the fragment as the query, and that
     * request ends the wait with the token where a code would be.
     */
    @Test
    fun `with the implicit grant, the redirect gets a page that moves its fragment into the query, and the token ends the wait`() {
        runBlocking {
            val listener = LoopbackListener.open(0, pages, response = LoopbackResponse.TOKEN).also(opened::add)
            val result = waiting(listener)

            val page = get(listener.port, "/callback")

            page shouldStartWith "HTTP/1.1 200 OK\r\n"
            page shouldContain "Cache-Control: no-store"
            page shouldContain "Referrer-Policy: no-referrer"
            page shouldContain "var f = location.hash.substring(1);"
            page shouldContain "location.replace(\"/callback?\" + f)"
            page shouldContain "<noscript><p>Not signed in</p></noscript>"
            get(listener.port, "/callback?") shouldContain "location.hash"
            result.isCompleted shouldBe false

            val answer = get(
                listener.port,
                "/callback?access_token=eyJ0.fixture-token&token_type=Bearer&expires_in=31536000&state=${listener.state}",
            )

            answer shouldStartWith "HTTP/1.1 200 OK\r\n"
            answer shouldContain "Signed in, switch back"
            val callback = withTimeout(5.seconds) { result.await() }.getOrThrow()
            callback.code shouldBe "eyJ0.fixture-token"
            callback.parameters.keys shouldContainExactlyInAnyOrder listOf("access_token", "token_type", "expires_in", "state")
            callback.toString() shouldNotContain "fixture-token"
        }
    }

    @Test
    fun `with the implicit grant, a token needs the expected state, a code is no token, and an error is a denial`() {
        runBlocking {
            val listener = LoopbackListener.open(0, pages, response = LoopbackResponse.TOKEN).also(opened::add)
            val result = waiting(listener)

            get(listener.port, "/callback?access_token=stolen&state=not-the-state") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            get(listener.port, "/callback?access_token=stolen") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            get(listener.port, "/other") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            result.isCompleted shouldBe false

            get(listener.port, "/callback?error=access_denied&state=${listener.state}") shouldContain "Not signed in"
            (withTimeout(5.seconds) { result.await() }.exceptionOrNull() as LoopbackDeniedException).error shouldBe "access_denied"
        }
        runBlocking {
            val listener = LoopbackListener.open(0, pages, response = LoopbackResponse.TOKEN).also(opened::add)
            val result = waiting(listener)
            get(listener.port, "/callback?code=a-code&state=${listener.state}")
            (withTimeout(5.seconds) { result.await() }.exceptionOrNull() as LoopbackDeniedException).error shouldBe
                LoopbackListener.NO_CODE
        }
    }

    @Test
    fun `the code grant never serves the fragment page, and a token is no code`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)

            get(listener.port, "/callback") shouldStartWith "HTTP/1.1 404 Not Found\r\n"
            result.isCompleted shouldBe false
            get(listener.port, "/callback?access_token=a-token&state=${listener.state}")
            (withTimeout(5.seconds) { result.await() }.exceptionOrNull() as LoopbackDeniedException).error shouldBe
                LoopbackListener.NO_CODE
        }
    }

    @Test
    fun `nothing for five minutes is a timeout, not a minute sooner, and the listener closes`() {
        runBlocking {
            val clock = TestTimeSource()
            val listener = open(timeSource = clock)
            val result = waiting(listener)

            clock += 4.minutes + 59.seconds
            delay(LoopbackListener.POLL_MILLIS.milliseconds * 3)
            result.isCompleted shouldBe false
            get(listener.port, "/favicon.ico") shouldStartWith "HTTP/1.1 404"

            clock += 2.seconds
            val error = withTimeout(5.seconds) { result.await() }.exceptionOrNull()
            (error as LoopbackTimeoutException).timeout shouldBe 5.minutes
            shouldThrow<ConnectException> { get(listener.port, "/callback?state=${listener.state}&code=late") }
        }
    }

    @Test
    fun `a fixed port that is free is the port bound`() {
        val free = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        val listener = open(port = free)
        listener.port shouldBe free
        listener.redirectUri shouldBe "http://127.0.0.1:$free/callback"
    }

    @Test
    fun `a fixed port in use is its own failure, naming the port, and nothing else is bound`() {
        ServerSocket().use { holder ->
            holder.bind(InetSocketAddress(LOOPBACK, 0))
            val taken = holder.localPort

            val error = shouldThrow<LoopbackPortInUseException> { open(port = taken) }

            error.port shouldBe taken
            opened shouldBe emptyList()
        }
    }

    @Test
    fun `a cancelled wait closes the listener`() {
        runBlocking {
            val listener = open()
            val result = waiting(listener)
            delay(100.milliseconds)
            result.cancelAndJoin()
            // The accept loop notices within one poll.
            delay(LoopbackListener.POLL_MILLIS.milliseconds * 3)
            shouldThrow<ConnectException> { get(listener.port, "/callback?state=${listener.state}&code=late") }
        }
    }

    @Test
    fun `each listener draws its own state`() {
        val states = List(5) { open().state }
        states.toSet().size shouldBe 5
        states.forEach { it.length shouldBe 43 }
    }

    /** Whether the listener still holds [this] open: a short read waits instead of ending. */
    private fun Socket.isStillOpen(): Boolean {
        soTimeout = 100
        return try {
            getInputStream().read() >= 0
        } catch (_: SocketTimeoutException) {
            true
        } catch (_: SocketException) {
            false
        }
    }

    /**
     * How long after this call the listener closed [this], read as the end of the stream or a reset,
     * or null when it was still open after [limit]. It must send nothing first.
     */
    private fun Socket.closedWithin(limit: Duration): Duration? {
        val start = TimeSource.Monotonic.markNow()
        soTimeout = limit.inWholeMilliseconds.toInt()
        return try {
            check(getInputStream().read() < 0) { "the listener sent bytes on a connection it should close unread" }
            start.elapsedNow()
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: SocketException) {
            start.elapsedNow()
        }
    }

    /** [get] again every 100 ms while the listener turns the connection away unanswered. */
    private suspend fun getOnceServed(port: Int, target: String): String {
        val giveUp = TimeSource.Monotonic.markNow() + LoopbackListener.REQUEST_DEADLINE_MILLIS.milliseconds + 5.seconds
        while (true) {
            val answer = withContext(Dispatchers.IO) {
                try {
                    get(port, target)
                } catch (e: SocketException) {
                    // Closed unread: the request's own write may meet the reset.
                    if (e is ConnectException) throw e
                    ""
                }
            }
            if (answer.isNotEmpty() || giveUp.hasPassedNow()) return answer
            delay(100.milliseconds)
        }
    }

    private fun get(port: Int, target: String): String =
        exchange(port, "GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())

    /**
     * Sends [bytes], half-closes, and reads until the listener closes: "" when it answered nothing,
     * whether it closed or reset the connection.
     */
    private fun exchange(port: Int, bytes: ByteArray): String = Socket(LOOPBACK, port).use { socket ->
        socket.soTimeout = 15_000
        socket.getOutputStream().apply { write(bytes); flush() }
        socket.shutdownOutput()
        try {
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        } catch (e: SocketException) {
            if (e is ConnectException) throw e
            ""
        }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
