package com.shinsaikou.sync.google

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import java.io.IOException

// The three ways Google's token and revoke endpoints turn the client down, decided on the HTTP
// status and the OAuth `error` code alone, never on `error_description` text (R83 item 3). Each is
// an IOException and declares its own ErrorKey (R67). Which one a caller retries is the caller's
// rule; the plan's (unit H, Q1) is: invalid_grant is signed out, any other refusal is Q1's standing
// fallback, and an unavailable service is retried with backoff.

/**
 * Google answered `invalid_grant`: the refresh token was revoked, expired or never issued, or the
 * code was used, expired or does not match its verifier or redirect. The owner signs in again.
 */
class GoogleInvalidGrantException(grantType: String) :
    IOException("Google answered invalid_grant to a $grantType grant"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.GOOGLE_INVALID_GRANT
}

/**
 * Google refused the request with [error], an OAuth error code other than `invalid_grant`
 * (`invalid_client`, `unauthorized_client`, `redirect_uri_mismatch`, `invalid_request` and the rest),
 * or with a status and no code (`http_<status>`), or answered success with a body that is not a
 * token response (`malformed_response`). Waiting does not fix any of them.
 */
class GoogleAuthRefusedException(val error: String, val status: Int) :
    IOException("Google refused the request with $error (HTTP $status)"), KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.GOOGLE_AUTH_REFUSED
    override val errorDetail: String get() = error

    companion object {
        /** The [error] of a success answer that carried no usable token. */
        const val MALFORMED_RESPONSE = "malformed_response"
    }
}

/**
 * Google did not answer usably: a 5xx or 429 ([status]), or no answer at all ([status] null, the
 * transport failure as the cause: a dropped connection, a refused one, a timeout). Waiting can fix it.
 */
class GoogleUnavailableException(val status: Int?, cause: IOException? = null) :
    IOException(if (status == null) "Google did not answer: ${cause?.let { it::class.simpleName }}" else "Google answered HTTP $status", cause),
    KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.GOOGLE_UNAVAILABLE
    override val errorDetail: String? get() = status?.toString()
}
