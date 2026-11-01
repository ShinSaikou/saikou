package com.shinsaikou.tracking

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.myanimelist.MALTitleNotApproved
import com.shinsaikou.tracking.myanimelist.MALTokenRefreshFailed
import eu.kanade.tachiyomi.network.HttpException
import java.io.IOException

/**
 * What a failure means for "Compare with MyAnimeList" (phase 4 unit TC): either one entry cannot be
 * read or written and the others go on, or the comparison or the Apply run stops.
 */
sealed interface CompareFailure {
    val error: Throwable

    /**
     * One entry's failure; the run goes on. At compare time the entry goes under "Can't compare";
     * on Apply the row fails and keeps its choice.
     */
    data class RowOnly(override val error: Throwable) : CompareFailure

    /**
     * The run stops: at compare time nothing is shown but the error, and on Apply the rows not
     * reached keep their choices. [sendUnanswered] is a send whose answer never came, so the service
     * may hold it or not.
     */
    data class Stop(
        override val error: Throwable,
        val kind: StopKind,
        val sendUnanswered: Boolean = false,
    ) : CompareFailure
}

/** Why a run stopped, for the text and the action the screen shows. */
enum class StopKind {
    /** The token endpoint refused a refresh, or had before: "Sign in again". */
    SIGN_IN_REFUSED,

    /** An API call answered 401 while the stored sign-in looks valid. */
    UNAUTHORIZED,

    /** 403, from an API call or the token endpoint. */
    FORBIDDEN,

    /** 429, from an API call or the token endpoint. */
    THROTTLED,

    /** Anything else: a server error, no network, a time-out, a refresh that failed, a sign-out. */
    OTHER,
}

/**
 * The one classification of the design's section 8.8, used by the comparison's confirmations and
 * fallback ([CompareTrackProgress]) and by every row of Apply ([ApplyTrackChoice]).
 *
 * Only what is plainly about one entry lets the run go on: a 400 (the service refusing that entry,
 * [MALTitleNotApproved] included), an answer for that entry that does not decode, and a 404 on a send
 * (the entry deleted between the read and the send; a 404 on a read is [EntryRead.Gone], never a
 * failure). Everything else stops, a 5xx included: a server-wide failure looks the same as one
 * entry's, and going on through every row would send one failing call each while stopping loses
 * nothing. A 401 from an API call stops too: the interceptor has already refreshed the grant once
 * and sent the call again, so a 401 that reaches here would answer every call.
 */
object CompareFailures {

    /** [error], thrown by a read of one entry, or by a send when [send] is true. */
    fun classify(error: Throwable, send: Boolean = false): CompareFailure = when {
        error is TrackerAuthExpiredException -> CompareFailure.Stop(error, StopKind.SIGN_IN_REFUSED)
        error is MALTitleNotApproved -> CompareFailure.RowOnly(error)
        error is HttpException && error.code == 400 -> CompareFailure.RowOnly(error)
        send && error is HttpException && error.code == 404 -> CompareFailure.RowOnly(error)
        // A decoding failure of that entry's answer: kotlinx's SerializationException, or a date the
        // tracker's parser does not know, are both IllegalArgumentExceptions.
        error is IllegalArgumentException -> CompareFailure.RowOnly(error)
        send && isUnanswered(error) -> stopOf(error).copy(sendUnanswered = true)
        else -> stopOf(error)
    }

    /** [error] as a stop, whatever it is: how a failure of the whole list read is shown. */
    fun stopOf(error: Throwable): CompareFailure.Stop = CompareFailure.Stop(
        error,
        when (httpCode(error)) {
            null -> if (error is TrackerAuthExpiredException) StopKind.SIGN_IN_REFUSED else StopKind.OTHER
            401 -> StopKind.UNAUTHORIZED
            403 -> StopKind.FORBIDDEN
            429 -> StopKind.THROTTLED
            else -> StopKind.OTHER
        },
    )

    /**
     * The status of the first [HttpException] in [error]'s causes. `ErrorKey.httpCode` reads the top
     * and one cause deep, which is where the token endpoint's status rides on [MALTokenRefreshFailed]
     * (a 401 there is a refused refresh, never this). The interceptor's failures cross OkHttp's
     * thread, and where the coroutines' stack trace recovery is on (the JVM tests, which run with
     * assertions) the caller gets a copy whose cause is the original, one level further down.
     */
    private fun httpCode(error: Throwable): Int? =
        generateSequence(error) { it.cause }.take(MAX_CAUSES).firstNotNullOfOrNull { ErrorKey.httpCode(it) }

    private const val MAX_CAUSES = 4

    /**
     * A send that failed after it may have reached the service: an I/O failure that is not the
     * interceptor refusing a refresh before the call, and not a connection that never opened (no
     * network, or a refused one). A sign-out between the read and the send ("User is not
     * authenticated") reads as one too; the run stops either way.
     */
    private fun isUnanswered(error: Throwable): Boolean = error is IOException &&
        error !is TrackerAuthExpiredException &&
        error !is MALTokenRefreshFailed &&
        ErrorKey.of(error) !in NEVER_CONNECTED

    private val NEVER_CONNECTED = setOf(ErrorKey.NO_NETWORK, ErrorKey.CONNECTION_REFUSED)
}
