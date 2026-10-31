package com.shinsaikou.tracking

import com.shinsaikou.core.common.oauth.LoopbackResponse
import com.shinsaikou.core.common.oauth.Pkce
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.domain.track.model.Track

/**
 * A browser sign-in tracker shaped like upstream's `DummyTracker`
 * (`app/src/main/java/eu/kanade/test/DummyTracker.kt`), in test sources only (R39). Its sign-in state
 * is two state flows the test drives, as a service's refusal or a sign-out elsewhere would; its
 * authorization URL uses [PkceMethod.PLAIN], as MyAnimeList's does; and [exchange] is the token
 * endpoint, which may suspend or throw. Every code, redirect and PKCE pair it was handed is kept.
 *
 * For the track sheet (phase 3 unit T3), [searched] and [refreshed] are the service's answers and
 * may throw; every `setRemote*` is kept in [remoteEdits] and throws [remoteFailure] when it is set.
 *
 * [loopbackResponse] is a code unless a test names AniList's implicit grant, whose token then comes
 * back as the code; [supportsPrivateTracking] is false unless a test offers private entries, as
 * AniList does.
 */
class FakeOAuthTracker(
    override val id: Long,
    override val name: String = "Fake tracker $id",
    override val isConfigured: Boolean = true,
    loggedIn: Boolean = false,
    authExpired: Boolean = false,
    private val displayName: String = "Reader $id",
    override val loopbackResponse: LoopbackResponse = LoopbackResponse.CODE,
    override val supportsPrivateTracking: Boolean = false,
) : OAuthTracker {

    val loggedInState = MutableStateFlow(loggedIn)
    val authExpiredState = MutableStateFlow(authExpired)

    /** The token endpoint: returns to accept the code, throws to refuse it, suspends to hang. */
    var exchange: suspend (code: String) -> Unit = {}

    /**
     * Runs between storing the grant and storing the credentials, and after the credentials, inside
     * the writes' `NonCancellable` block as MyAnimeList's are (phase 3 unit T2): suspend here to
     * hold a sign-in in the middle of storing.
     */
    var betweenWrites: suspend () -> Unit = {}
    var afterWrites: suspend () -> Unit = {}

    /** The stored grant, as a tracker's `trackToken` holds it; null while signed out. */
    var token: String? = null

    val authorizationRequests = mutableListOf<Triple<String, String, Pkce>>()
    val completions = mutableListOf<Triple<String, String, Pkce>>()
    var logouts = 0

    var searched: suspend (String) -> List<TrackSearchResult> = { emptyList() }
    var refreshed: suspend (Track) -> Track = { it }
    val searches = mutableListOf<String>()
    val refreshes = mutableListOf<Track>()

    /** Each edit as the field's name and the row the service was sent. */
    val remoteEdits = mutableListOf<Pair<String, Track>>()
    var remoteFailure: Exception? = null

    override val supportsReadingDates: Boolean = false

    override fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl {
        authorizationRequests += Triple(redirectUri, state, pkce)
        return "https://tracker.example.invalid/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", PkceMethod.PLAIN.challenge(pkce))
            .addQueryParameter("code_challenge_method", PkceMethod.PLAIN.id)
            .build()
    }

    override suspend fun completeSignIn(code: String, redirectUri: String, pkce: Pkce) {
        completions += Triple(code, redirectUri, pkce)
        exchange(code)
        withContext(NonCancellable) {
            token = "fixture-grant-$id"
            betweenWrites()
            authExpiredState.value = false
            loggedInState.value = true
            afterWrites()
        }
    }

    override val isLoggedIn: Boolean get() = loggedInState.value
    override val isLoggedInFlow: Flow<Boolean> get() = loggedInState
    override val isAuthExpired: Boolean get() = authExpiredState.value
    override val isAuthExpiredFlow: Flow<Boolean> get() = authExpiredState

    override fun logout() {
        logouts++
        token = null
        loggedInState.value = false
        authExpiredState.value = false
    }

    override fun getUsername(): String = if (isLoggedIn) "user$id" else ""

    override fun getDisplayUsername(): String = if (isLoggedIn) displayName else ""

    override fun getLogo(): Int = 0

    override fun getStatusList(): List<Long> = listOf(1L, 2L)

    override fun getStatus(status: Long): Int? = null

    override fun getReadingStatus(): Long = 1L

    override fun getRereadingStatus(): Long = 1L

    override fun getCompletionStatus(): Long = 2L

    override fun getScoreList(): List<String> = (0..10).map(Int::toString)

    override fun get10PointScore(track: Track): Double = track.score

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: Track): String = track.score.toString()

    override suspend fun search(query: String): List<TrackSearchResult> {
        searches += query
        return searched(query)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = track

    override suspend fun update(track: Track, didReadChapter: Boolean): Track = track

    override suspend fun refresh(track: Track): Track {
        refreshes += track
        return refreshed(track)
    }

    override suspend fun setRemoteStatus(track: Track, status: Long): Track = edit("status", track.copy(status = status))

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int): Track =
        edit("chapter", track.copy(lastChapterRead = chapterNumber.toDouble()))

    override suspend fun setRemoteScore(track: Track, scoreString: String): Track =
        edit("score", track.copy(score = scoreString.toDouble()))

    override suspend fun setRemoteStartDate(track: Track, epochMillis: Long): Track =
        edit("startDate", track.copy(startDate = epochMillis))

    override suspend fun setRemoteFinishDate(track: Track, epochMillis: Long): Track =
        edit("finishDate", track.copy(finishDate = epochMillis))

    override suspend fun setRemotePrivate(track: Track, private: Boolean): Track = edit("private", track.copy(private = private))

    private fun edit(field: String, sent: Track): Track {
        remoteEdits += field to sent
        remoteFailure?.let { throw it }
        return sent
    }

    override suspend fun login(username: String, password: String) = Unit
}
