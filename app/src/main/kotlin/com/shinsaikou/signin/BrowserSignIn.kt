package com.shinsaikou.signin

import android.content.Context
import android.content.Intent
import com.shinsaikou.core.common.oauth.LoopbackCallback
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.LoopbackPages
import com.shinsaikou.core.common.oauth.LoopbackResponse
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicReference

/**
 * One browser sign-in, start to finish, for Google Drive and the OAuth trackers alike (phase 3
 * unit O, Q1 and Q13):
 *
 * 1. bind the loopback listener on [port] (0 for Drive, `LoopbackListener.TRACKER_PORT` for a
 *    tracker), so a port in use fails before anything else happens;
 * 2. hold the foreground ([SignInHold]) while the caller's activity is still visible;
 * 3. open the authorization URL the caller builds from the listener's redirect and state in the
 *    owner's browser ([SignInBrowser]);
 * 4. wait for the callback, then end the hold and close the listener, whichever way it went.
 *
 * Call it from the visible activity's scope. The caller exchanges the code itself, with the
 * [Result.redirectUri] its URL named; nothing here talks to a provider or stores anything.
 *
 * One browser sign-in at a time, app-wide (fix round 1 item 2): starting one cancels the one in
 * flight, whose listener closes and whose hold ends before the new one binds its port, and whose
 * caller gets [SignInCancelledException], a cancel rather than a failure. So at most one listener and
 * one hold exist.
 */
@SingleIn(AppScope::class)
@Inject
class BrowserSignIn(private val context: Context, private val hold: SignInHold, private val browser: SignInBrowser) {

    private val current = AtomicReference<Deferred<Result>?>(null)

    /**
     * Runs the sign-in, after cancelling any other in flight and waiting for its listener to close.
     * [returnIntent] is what the hold's notifications open to bring the owner back; [response] is
     * the form the provider answers in, a code for every sign-in but AniList's implicit grant.
     *
     * Throws what the listener throws: `LoopbackPortInUseException`, `LoopbackTimeoutException` and
     * `LoopbackDeniedException`; `ActivityNotFoundException` when no app opens a web page; and
     * [SignInCancelledException] when a later sign-in started before this one ended.
     */
    suspend fun signIn(
        port: Int,
        returnIntent: Intent,
        response: LoopbackResponse = LoopbackResponse.CODE,
        authorizationUrl: (LoopbackListener) -> HttpUrl,
    ): Result =
        coroutineScope {
            val session = async(start = CoroutineStart.LAZY) { run(port, returnIntent, response, authorizationUrl) }
            current.getAndSet(session)?.let { previous ->
                previous.cancel(SignInCancelledException())
                previous.join()
            }
            try {
                session.await()
            } finally {
                current.compareAndSet(session, null)
            }
        }

    private suspend fun run(
        port: Int,
        returnIntent: Intent,
        response: LoopbackResponse,
        authorizationUrl: (LoopbackListener) -> HttpUrl,
    ): Result {
        val listener = LoopbackListener.open(port, pages(context), response = response)
        var handle: SignInHold.Handle? = null
        var signedIn = false
        try {
            handle = hold.start(returnIntent)
            browser.open(authorizationUrl(listener))
            val callback = listener.await()
            signedIn = true
            return Result(callback, listener.redirectUri, handle.inForeground)
        } finally {
            handle?.let { hold.end(it, signedIn) }
            listener.close()
        }
    }

    /**
     * The code that came back (the token, for an implicit grant), the redirect the authorization URL
     * named (the code exchange sends it again), and whether the process held the foreground while it
     * waited.
     */
    class Result(val callback: LoopbackCallback, val redirectUri: String, val heldForeground: Boolean) {
        override fun toString(): String = "BrowserSignIn.Result($callback, heldForeground=$heldForeground)"
    }

    companion object {

        /** The page the browser shows once the callback has arrived, in the app's own words. */
        fun pages(context: Context): LoopbackPages = LoopbackPages(
            title = context.getString(R.string.app_name),
            signedIn = context.getString(R.string.sign_in_page_signed_in),
            notSignedIn = context.getString(R.string.sign_in_page_not_signed_in),
        )
    }
}

/**
 * A browser sign-in ended because another started before it finished (fix round 1 item 2). A cancel,
 * not a failure: a caller that rethrows `CancellationException` shows nothing, and one that wants to
 * know can catch this type.
 */
class SignInCancelledException : CancellationException("Another browser sign-in started")
