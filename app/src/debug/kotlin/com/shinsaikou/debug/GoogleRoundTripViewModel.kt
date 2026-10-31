package com.shinsaikou.debug

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.oauth.LoopbackCallback
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.di.AppGraph
import com.shinsaikou.signin.BrowserSignIn
import com.shinsaikou.signin.SignInBrowser
import com.shinsaikou.sync.google.GoogleEndpoints
import com.shinsaikou.sync.google.GoogleOAuthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat

/**
 * One Google round trip for the owner's go/no-go (phase 3 unit O, owner item 2): the browser
 * sign-in, the code exchange, one refresh and the revoke, reported as yes or no per step and never
 * as a value. No token, code, secret or account reaches the screen or the log; the log line per step
 * is for the spike's timing and says only what happened and when.
 *
 * With [State.holdForeground] off, the sign-in runs without the foreground hold, which only the
 * spike's comparison uses: what the hold buys is measured by leaving it out.
 *
 * [State.baseInput] is the test base the person holding the device typed; [run] puts it in effect
 * (blank is Google's endpoints), and refuses one that is not an `http://` or `https://` address.
 */
class GoogleRoundTripViewModel(private val graph: AppGraph, private val browser: SignInBrowser) : ViewModel() {

    private val _state = MutableStateFlow(
        State(client = graph.googleOAuthClient.describe(), baseInput = graph.googleTestBase.value.orEmpty()),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    fun setHoldForeground(hold: Boolean) {
        _state.update { it.copy(holdForeground = hold) }
    }

    fun setBaseInput(base: String) {
        _state.update { it.copy(baseInput = base) }
    }

    fun run(context: Context, returnIntent: Intent) {
        if (_state.value.running) return
        val base = _state.value.baseInput
        try {
            GoogleEndpoints.forBuild(debug = true, testBase = base)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(failure = e) }
            return
        }
        graph.googleTestBase.value = base
        val client = graph.googleOAuthClient
        val hold = _state.value.holdForeground
        _state.update { State(client = client.describe(), baseInput = base, holdForeground = hold, running = true) }
        if (!client.isConfigured) {
            _state.update { it.copy(running = false) }
            return
        }
        viewModelScope.launch {
            try {
                roundTrip(context, client, hold, returnIntent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "round trip stopped: ${e::class.simpleName}" }
                _state.update { it.copy(failure = e) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun roundTrip(context: Context, client: GoogleOAuthClient, hold: Boolean, returnIntent: Intent) {
        val pkce = Pkce.generate()
        val opened = SystemClock.elapsedRealtime()
        logcat(TAG) { "sign-in started, hold=$hold, endpoints=${client.endpoints}" }
        val (callback, redirectUri) = if (hold) {
            val result = graph.browserSignIn.signIn(0, returnIntent) { client.authorizationUrl(it.redirectUri, it.state, pkce) }
            _state.update { it.copy(heldForeground = result.heldForeground) }
            result.callback to result.redirectUri
        } else {
            signInWithoutHold(context, client, pkce)
        }
        val seconds = (SystemClock.elapsedRealtime() - opened) / 1000
        logcat(TAG) { "code issued ${seconds}s after the browser opened" }
        _state.update { it.copy(codeAfterSeconds = seconds) }

        val tokens = client.exchange(callback.code, pkce, redirectUri)
        val refreshToken = tokens.refreshToken
        logcat(TAG) {
            "exchange done: refresh token issued=${refreshToken != null}, " +
                "refresh_token_expires_in present=${tokens.refreshTokenExpiresAt != null}"
        }
        _state.update { it.copy(refreshTokenIssued = refreshToken != null, expiresInPresent = tokens.refreshTokenExpiresAt != null) }
        if (refreshToken == null) return

        client.refresh(refreshToken)
        logcat(TAG) { "refresh accepted" }
        _state.update { it.copy(refreshed = true) }

        val revoked = client.revoke(refreshToken)
        logcat(TAG) { "revoked=$revoked" }
        _state.update { it.copy(revoked = revoked) }
    }

    /** The same listener and browser, with no hold: what the process does on its own. */
    private suspend fun signInWithoutHold(context: Context, client: GoogleOAuthClient, pkce: Pkce): Pair<LoopbackCallback, String> {
        val listener = LoopbackListener.open(0, BrowserSignIn.pages(context))
        try {
            browser.open(client.authorizationUrl(listener.redirectUri, listener.state, pkce))
            return listener.await() to listener.redirectUri
        } finally {
            listener.close()
        }
    }

    private fun GoogleOAuthClient.describe() = ClientDescription(
        configured = isConfigured,
        testBase = endpoints.takeUnless { it.isGoogle }?.driveBase?.toString(),
    )

    /** Whether there is a client to run with, and the test base when the endpoints are not Google's. */
    data class ClientDescription(val configured: Boolean, val testBase: String?)

    /** Each step's answer; null until the step has run. */
    data class State(
        val client: ClientDescription,
        val baseInput: String = "",
        val holdForeground: Boolean = true,
        val running: Boolean = false,
        val heldForeground: Boolean? = null,
        val codeAfterSeconds: Long? = null,
        val refreshTokenIssued: Boolean? = null,
        val expiresInPresent: Boolean? = null,
        val refreshed: Boolean? = null,
        val revoked: Boolean? = null,
        val failure: Throwable? = null,
    )

    private companion object {
        const val TAG = "GoogleRoundTrip"
    }
}
