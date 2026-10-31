package com.shinsaikou.tracking

import com.shinsaikou.core.common.oauth.LoopbackResponse
import com.shinsaikou.core.common.oauth.Pkce
import okhttp3.HttpUrl

/**
 * A tracker that signs in through the owner's browser (phase 3 unit T1, Q13, R91): OAuth 2's
 * authorization code grant with PKCE, the browser coming back through O's loopback listener on
 * `LoopbackListener.TRACKER_PORT`, whose redirect `http://127.0.0.1:18796/callback` the service's
 * client registers.
 *
 * The caller, the TRACKING settings section, runs one sign-in as:
 *
 * 1. `Pkce.generate()`, a pair for this sign-in alone;
 * 2. `BrowserSignIn.signIn(LoopbackListener.TRACKER_PORT, ...) { listener ->
 *    authorizationUrl(listener.redirectUri, listener.state, pkce) }`, which opens the page and
 *    waits for the callback that carries the listener's state;
 * 3. [completeSignIn] with the callback's code, the redirect the URL named and the same pair.
 *
 * So the verifier and the code live only in that coroutine and die with it; a tracker keeps neither
 * on a field or a companion object (upstream's MyAnimeList kept its verifier on the companion). Nothing
 * logs or shows a code, state, verifier or token.
 *
 * A service that offers a public client only the implicit grant (RFC 6749 section 4.2), as AniList
 * does, says so with [loopbackResponse]: its page asks for a token, the redirect carries it in the
 * fragment, the listener reads it there, and [completeSignIn] is handed the token as its code and
 * exchanges nothing. Such a tracker sends no PKCE challenge; the caller's pair goes unused.
 */
interface OAuthTracker : Tracker {

    /**
     * What the service's redirect carries: a code to exchange ([LoopbackResponse.CODE], every
     * tracker's but an implicit grant's), or the token itself in the fragment
     * ([LoopbackResponse.TOKEN]).
     */
    val loopbackResponse: LoopbackResponse
        get() = LoopbackResponse.CODE

    /**
     * The page the browser opens: the service's authorization endpoint with the tracker's client id,
     * [redirectUri], [state] and the challenge of [pkce] under the method the service accepts
     * ([PkceMethod]). Builds a URL and nothing else: no call, no stored value.
     */
    fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl

    /**
     * Exchanges [code] for the service's grant, sending [redirectUri] and [pkce]'s verifier again,
     * and stores the sign-in: the token, then the display name, then the credentials that make
     * [isLoggedIn] true (`BaseTracker.saveCredentials`, which also clears [isAuthExpired]), so a
     * screen that sees the tracker signed in also sees who as. With [LoopbackResponse.TOKEN], [code]
     * is the token, which is checked against the service and stored with nothing exchanged.
     *
     * Throws [TrackerSignInRefusedException] when the token endpoint refuses the code, and the
     * transport's `IOException` when it cannot be reached. A sign-in that throws leaves the stored
     * sign-in as it was: a signed-out tracker stays signed out, and an expired one stays expired.
     */
    suspend fun completeSignIn(code: String, redirectUri: String, pkce: Pkce)
}

/**
 * How a service wants the PKCE challenge (RFC 7636 section 4.2). `Pkce` computes S256; a service
 * that accepts only `plain`, as MyAnimeList does, takes the verifier itself as the challenge. Each
 * tracker names its own, so the seam forces neither on a service.
 */
enum class PkceMethod(val id: String) {
    S256(Pkce.METHOD),
    PLAIN("plain"),
    ;

    /** The `code_challenge` this method sends for [pkce]. */
    fun challenge(pkce: Pkce): String = when (this) {
        S256 -> pkce.challenge
        PLAIN -> pkce.verifier
    }
}
