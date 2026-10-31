package com.shinsaikou.tracking

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import java.io.IOException

/**
 * A tracker's token endpoint refused a sign-in's code exchange ([OAuthTracker.completeSignIn]).
 * [error] is the OAuth `error` code the service sent (`invalid_grant`, `invalid_client` and the
 * like), or `http_<status>` when it sent none; never the response body, which may echo the code.
 */
class TrackerSignInRefusedException(val trackerId: Long, val error: String) :
    IOException("Tracker $trackerId refused the sign-in with $error"),
    KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.TRACKER_SIGN_IN_REFUSED
    override val errorDetail: String get() = error
}

/**
 * A tracker no longer honours the stored sign-in: its token endpoint answered a refresh with 400 or
 * 401 (Q13). The tracker sets `trackAuthExpired` (`BaseTracker.setAuthExpired`) before it throws
 * this, so the retry worker keeps the push for the next sign-in instead of retrying it, and settings
 * shows "Sign in again".
 */
class TrackerAuthExpiredException(val trackerId: Long) :
    IOException("Tracker $trackerId needs a new sign-in"),
    KeyedFailure {
    override val errorKey: ErrorKey get() = ErrorKey.TRACKER_AUTH_EXPIRED
}
