package com.shinsaikou.signin

import android.content.Context
import android.content.Intent
import com.shinsaikou.core.common.oauth.LoopbackDeniedException
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.LoopbackPortInUseException
import com.shinsaikou.core.common.oauth.LoopbackResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * One browser sign-in from start to finish (phase 3 unit O): the listener binds before anything else,
 * the hold starts before the browser opens, and whichever way the sign-in ends the hold ends with it
 * and the listener closes. The "browser" is a thread that loads the page it is given, the way the
 * provider's redirect reaches the loopback. A second sign-in cancels the first (fix round 1 item 2).
 */
class BrowserSignInTest {

    private val context = mockk<Context>(relaxed = true)
    private val hold = mockk<SignInHold>(relaxed = true)
    private val browser = mockk<SignInBrowser>()
    private val returnIntent = mockk<Intent>()
    private val signIn = BrowserSignIn(context, hold, browser)
    private val held = SignInHold.Handle(SignInHold.Hold(returnIntent), inForeground = true)

    private var redirectPort = -1

    init {
        coEvery { hold.start(any()) } returns held
        every { browser.open(any()) } answers { load(firstArg()) }
    }

    /** What the provider's redirect would carry back: [query] and the listener's own state. */
    private fun redirect(listener: LoopbackListener, query: String): HttpUrl {
        redirectPort = listener.port
        return "${listener.redirectUri}?$query&state=${listener.state}".toHttpUrl()
    }

    @Test
    fun `a code that comes back ends the hold as signed in and closes the listener`() = runTest {
        val result = signIn.signIn(0, returnIntent) { redirect(it, "code=fixture-code") }

        result.callback.code shouldBe "fixture-code"
        result.redirectUri shouldBe "http://127.0.0.1:$redirectPort/callback"
        result.heldForeground shouldBe true
        coVerify(exactly = 1) { hold.start(returnIntent) }
        coVerifyOrder {
            hold.start(returnIntent)
            browser.open(any())
            hold.end(held, true)
        }
        closed(redirectPort) shouldBe true
    }

    /** AniList's implicit grant: the listener waits for a token, and the token comes back as the code. */
    @Test
    fun `an implicit grant's token comes back where the code would, and a code alone does not end it`() = runTest {
        val result = signIn.signIn(0, returnIntent, LoopbackResponse.TOKEN) {
            redirect(it, "access_token=fixture-token&token_type=Bearer&expires_in=31536000")
        }

        result.callback.code shouldBe "fixture-token"
        verify(exactly = 1) { hold.end(held, true) }
        closed(redirectPort) shouldBe true

        val failure = shouldThrow<LoopbackDeniedException> {
            signIn.signIn(0, returnIntent, LoopbackResponse.TOKEN) { redirect(it, "code=fixture-code") }
        }
        failure.error shouldBe LoopbackListener.NO_CODE
    }

    @Test
    fun `a hold the platform refused does not stop the sign-in`() = runTest {
        val refused = SignInHold.Handle(SignInHold.Hold(returnIntent), inForeground = false)
        coEvery { hold.start(any()) } returns refused

        val result = signIn.signIn(0, returnIntent) { redirect(it, "code=fixture-code") }

        result.heldForeground shouldBe false
        verify(exactly = 1) { hold.end(refused, true) }
    }

    @Test
    fun `a port in use fails before the hold starts or the browser opens`() = runTest {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            val failure = shouldThrow<LoopbackPortInUseException> {
                signIn.signIn(taken.localPort, returnIntent) { redirect(it, "code=fixture-code") }
            }
            failure.port shouldBe taken.localPort
        }
        coVerify(exactly = 0) { hold.start(any()) }
        verify(exactly = 0) { browser.open(any()) }
        verify(exactly = 0) { hold.end(any(), any()) }
    }

    @Test
    fun `a denied sign-in ends the hold as not signed in and closes the listener`() = runTest {
        val failure = shouldThrow<LoopbackDeniedException> {
            signIn.signIn(0, returnIntent) { redirect(it, "error=access_denied") }
        }

        failure.error shouldBe "access_denied"
        verify(exactly = 1) { hold.end(held, false) }
        closed(redirectPort) shouldBe true
    }

    @Test
    fun `a browser that does not open still ends the hold and closes the listener`() = runTest {
        every { browser.open(any()) } throws IllegalStateException("fixture: nothing opens a web page")

        shouldThrow<IllegalStateException> { signIn.signIn(0, returnIntent) { redirect(it, "code=fixture-code") } }

        verify(exactly = 1) { hold.end(held, false) }
        closed(redirectPort) shouldBe true
    }

    /**
     * Sign-in A is abandoned in the browser; sign-in B starts while A's listener waits. A's caller
     * gets the typed cancel, A's listener is closed and its hold ended (its own, as not signed in)
     * before B's listener opens, and B completes and ends its own hold as signed in.
     */
    @Test
    fun `a second sign-in cancels the first, whose caller sees a cancel, and completes on its own`() = runTest {
        val handleA = SignInHold.Handle(SignInHold.Hold(returnIntent), inForeground = true)
        val handleB = SignInHold.Handle(SignInHold.Hold(returnIntent), inForeground = true)
        coEvery { hold.start(any()) } returnsMany listOf(handleA, handleB)
        val openedA = CompletableDeferred<Unit>()
        var portA = -1
        every { browser.open(any()) } answers {
            val url: HttpUrl = firstArg()
            if (url.port == portA) openedA.complete(Unit) else load(url)
        }

        val a = async {
            runCatching { signIn.signIn(0, returnIntent) { listener -> listener.redirectUri.toHttpUrl().also { portA = listener.port } } }
        }
        openedA.await()
        closed(portA) shouldBe false

        var portAClosedWhenBOpened: Boolean? = null
        val b = signIn.signIn(0, returnIntent) { listener ->
            portAClosedWhenBOpened = closed(portA)
            redirect(listener, "code=code-b")
        }

        b.callback.code shouldBe "code-b"
        a.await().exceptionOrNull().shouldBeInstanceOf<SignInCancelledException>()
        portAClosedWhenBOpened shouldBe true
        closed(portA) shouldBe true
        closed(redirectPort) shouldBe true
        coVerifyOrder {
            hold.start(returnIntent)
            hold.end(handleA, false)
            hold.start(returnIntent)
            hold.end(handleB, true)
        }
        verify(exactly = 2) { hold.end(any(), any()) }
    }

    /** Loads [url] off the caller's thread, since the sign-in only listens once the browser has opened. */
    private fun load(url: HttpUrl) {
        thread(isDaemon = true) {
            val connection = URL(url.toString()).openConnection() as HttpURLConnection
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun closed(port: Int): Boolean = try {
        Socket(InetAddress.getLoopbackAddress(), port).close()
        false
    } catch (_: ConnectException) {
        true
    }
}
