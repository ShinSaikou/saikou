// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBaka.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.BaseTracker
import com.shinsaikou.tracking.DeletableTracker
import com.shinsaikou.tracking.OAuthTracker
import com.shinsaikou.tracking.TrackDates
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.mangabaka.dto.MangaBakaOAuth
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.HttpUrl
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Clock
import com.shinsaikou.tracking.R as TrackingR

/**
 * Modified, MangaBaka (id 11), on AniList's pattern:
 *
 * - **Constructor injection**, [BaseTracker] and the domain [Track] row; contributed to the tracker
 *   set with the binding written out (R26), one per process since its refresh is single-flight, so
 *   it is in the graph under id 11 whether configured or not.
 * - **The client** is the build's id on MangaBaka's endpoints ([MangaBakaClient]) and a test base's
 *   elsewhere; [isConfigured] is false when the build's id is blank.
 * - **Sign-in is [OAuthTracker]'s seam** in place of upstream's `login(code)` from a deep link: the
 *   code flow with an S256 challenge on the loopback redirect. [completeSignIn] exchanges the code
 *   and reads the profile with the new token first, then stores the score step, the grant, the
 *   display name and the credentials (the account's id) under `NonCancellable` and [grantLock]. A
 *   failure is thrown and leaves the stored sign-in as it was; upstream caught every `Exception` and
 *   signed out, and failed a sign-in on a score step it did not know, which reads as [STEP_1] here.
 * - **Sign-out revokes the grant** ([logout]): the sign-in is cleared here first, then the refresh
 *   token is revoked at MangaBaka on [appScope], best effort, so a sign-out offline still signs out.
 * - **[refresh]** adds an entry the library lacks, as AniList's does, where upstream threw, and never
 *   calls [update], so the retry worker alone decides whether to send the chapter. **[update]** adds
 *   an entry the library no longer has back ([MangaBakaApi.updateLibManga]).
 * - **Dates** stamped on a read chapter come from the injected clock, as today on the device
 *   ([TrackDates]), and any chapter read starts an entry with no start date, as AniList's does;
 *   upstream stamped chapter 1 alone. The status labels are `:i18n`'s.
 * - **Search** trims the query and reads `id:` and a series' address on the website as one series,
 *   as AniList's does.
 * - `getIfAuthExpired` and `setAuthExpired` are [BaseTracker]'s; the interceptor keeps no copy of
 *   the grant. Upstream's `updateUserConfig`, which read the score step again, has no caller here: the
 *   step is read at each sign-in.
 *
 * The status constants and their order, the score steps and their mappings, the bind and the
 * private entries are upstream's.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<Tracker>())
@Inject
class MangaBaka(
    trackPreferences: TrackPreferences,
    trackRepository: TrackRepository,
    httpClient: TrackerHttpClient,
    private val clock: Clock,
    private val endpointsProvider: () -> MangaBakaEndpoints,
    private val buildClient: MangaBakaClient,
    private val json: Json,
    private val appScope: CoroutineScope,
    private val dispatchers: Dispatchers,
) : BaseTracker(TrackerIds.MANGABAKA, "MangaBaka", trackPreferences, trackRepository, httpClient, clock),
    OAuthTracker,
    DeletableTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val PAUSED = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L
        const val REREADING = 6L
        const val CONSIDERING = 7L

        const val STEP_1 = "STEP_1"
        const val STEP_5 = "STEP_5"
        const val STEP_10 = "STEP_10"
        const val STEP_20 = "STEP_20"
        const val STEP_25 = "STEP_25"

        /** Each step's scores, upstream's: the step only sets the stride, every score is on 0 to 100. */
        private val SCORE_RANGES: Map<String, IntProgression> = mapOf(
            // 1, 2, ..., 99, 100
            STEP_1 to IntRange(0, 100),
            // 5, 10, ..., 95, 100
            STEP_5 to IntRange(0, 100).step(5),
            // 10, 20, ..., 90, 100
            STEP_10 to IntRange(0, 100).step(10),
            // 20, 40, ..., 80, 100
            STEP_20 to IntRange(0, 100).step(20),
            // 25, 50, 75, 100
            STEP_25 to IntRange(0, 100).step(25),
        )

        private const val SEARCH_ID_PREFIX = "id:"

        /** A series' page on the website, with or without the scheme, `www.`, `manga/` and the title after the id. */
        private val ENTRY_ADDRESS = Regex(
            """(?:https?://)?(?:www\.)?mangabaka\.org/(?:manga/)?(\d+)(?:[/?#].*)?""",
            RegexOption.IGNORE_CASE,
        )

        /** The score step [ratingSteps] names on the account's profile; null and unknown steps read as 1. */
        internal fun scoreTypeOf(ratingSteps: Int?): String = when (ratingSteps) {
            5 -> STEP_5
            10 -> STEP_10
            20 -> STEP_20
            25 -> STEP_25
            else -> STEP_1
        }
    }

    /**
     * The lock the stored grant is replaced under: the interceptor's refresh, the sign-in's write and
     * the sign-out's clear, so none of them sees the others' half-done.
     */
    internal val grantLock = Any()

    private val interceptor by lazy { MangaBakaInterceptor(this, json, ::nowEpochSeconds) }
    private val api by lazy {
        MangaBakaApi(id, client, interceptor, endpointsProvider, ::clientId, json, ::nowEpochSeconds)
    }

    override val supportsReadingDates: Boolean = true

    override val supportsPrivateTracking: Boolean = true

    private val scorePreference = trackPreferences.mangabakaScoreType

    /** Configured while there is a client to sign in with: the build's id, or a test base's. */
    override val isConfigured: Boolean
        get() = clientId().isNotBlank()

    /** Where this call goes: MangaBaka, or a test base. */
    internal fun endpoints(): MangaBakaEndpoints = endpointsProvider()

    /** The build's client id on MangaBaka's endpoints; the fixture's on any test base. */
    internal fun clientId(): String =
        if (endpoints().isMangaBaka) buildClient.clientId else MangaBakaClient.TEST_BASE_CLIENT_ID

    override fun getLogo() = TrackingR.drawable.brand_mangabaka

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, PAUSED, DROPPED, PLAN_TO_READ, REREADING, CONSIDERING)
    }

    override fun getStatus(status: Long): Int? = when (status) {
        CONSIDERING -> R.string.track_status_considering
        COMPLETED -> R.string.track_status_completed
        DROPPED -> R.string.track_status_dropped
        PAUSED -> R.string.track_status_paused
        PLAN_TO_READ -> R.string.track_status_plan_to_read
        READING -> R.string.track_status_reading
        REREADING -> R.string.track_status_rereading
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = REREADING

    override fun getCompletionStatus(): Long = COMPLETED

    /** The account's score step, as MangaBaka named it at sign-in; one this code does not know is [STEP_1]. */
    internal fun scoreType(): String = scorePreference.get().takeIf { it in SCORE_RANGES } ?: STEP_1

    override fun getScoreList(): List<String> = scoreRange().map(Int::toString)

    // score preference only dictates step size, scores are always 0-100
    override fun get10PointScore(track: Track): Double = track.score / 10.0

    /** The score at [index] of [getScoreList]; an index outside it (a pick the list no longer has) is no score. */
    override fun indexToScore(index: Int): Double = scoreRange().toList().getOrElse(index) { 0 }.toDouble()

    override fun displayScore(track: Track): String = track.score.toInt().toString()

    private fun scoreRange(): IntProgression = SCORE_RANGES.getValue(scoreType())

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        var updated = track
        if (updated.status != COMPLETED && didReadChapter) {
            if (updated.lastChapterRead.toLong() == updated.totalChapters && updated.totalChapters > 0) {
                updated = updated.copy(status = COMPLETED, finishDate = today())
            } else if (updated.status != REREADING) {
                updated = updated.copy(status = READING)
            }
            if (updated.startDate == 0L && updated.status != REREADING) {
                updated = updated.copy(startDate = today())
            }
        }

        return api.updateLibManga(updated)
    }

    override suspend fun delete(track: Track) {
        api.deleteLibManga(track.remoteId)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val remoteTrack = api.findLibManga(track)
        return if (remoteTrack != null) {
            // Upstream's copyPersonalFrom without the remote's privacy: the owner's choice in the sheet is sent.
            var bound = track.copy(
                lastChapterRead = remoteTrack.lastChapterRead,
                score = remoteTrack.score,
                status = remoteTrack.status,
                startDate = remoteTrack.startDate,
                finishDate = remoteTrack.finishDate,
                title = remoteTrack.title,
                totalChapters = remoteTrack.totalChapters,
            )

            if (bound.status != COMPLETED) {
                val isRereading = bound.status == REREADING
                bound = bound.copy(status = if (!isRereading && hasReadChapters) READING else bound.status)
            }

            update(bound)
        } else {
            // Set default fields if it's not found in the list
            api.addLibManga(track.copy(status = if (hasReadChapters) READING else PLAN_TO_READ, score = 0.0))
        }
    }

    override suspend fun search(query: String): List<TrackSearchResult> {
        val query = query.trim()
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).trim().toLongOrNull()?.let { id ->
                return listOfNotNull(api.getMangaDetails(id))
            }
        }

        ENTRY_ADDRESS.matchEntire(query)?.groupValues?.get(1)?.toLongOrNull()?.let { id ->
            return listOfNotNull(api.getMangaDetails(id))
        }

        return api.search(query)
    }

    /**
     * The owner's library entry laid over [track], with the series' title and chapter count, or the
     * entry added as the row has it when the library lacks it.
     */
    override suspend fun refresh(track: Track): Track {
        return api.findLibManga(track) ?: api.addLibManga(track)
    }

    /** No password sign-in: MangaBaka signs in through the browser ([completeSignIn]). */
    override suspend fun login(username: String, password: String) {
        throw UnsupportedOperationException("MangaBaka signs in through the browser")
    }

    override fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl =
        api.authUrl(redirectUri, state, pkce)

    /**
     * Exchanges [code], reads the account's profile with the new token, then stores its score step,
     * the grant, its name and the credentials (its MangaBaka id, with the access token as upstream
     * stores it).
     */
    override suspend fun completeSignIn(code: String, redirectUri: String, pkce: Pkce) {
        val oauth = api.getAccessToken(code, redirectUri, pkce)
        val user = api.getCurrentUser(oauth.accessToken)
        withContext(NonCancellable) {
            synchronized(grantLock) {
                scorePreference.set(scoreTypeOf(user.ratingSteps))
                saveOAuth(oauth)
                saveDisplayUsername(user.displayName())
                saveCredentials(user.id, oauth.accessToken)
            }
        }
    }

    /**
     * Clears the sign-in here, then revokes its refresh token at MangaBaka in the background. The
     * revoke is best effort: offline, or refused, the grant is simply left to lapse, and the
     * sign-out has already happened.
     */
    override fun logout() {
        val refreshToken = synchronized(grantLock) {
            loadOAuth()?.refreshToken.also { super.logout() }
        }
        if (refreshToken.isNullOrBlank() || !isConfigured) return
        appScope.launch(dispatchers.io) {
            try {
                api.revoke(refreshToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "MangaBaka: the sign-out could not revoke the grant" }
            }
        }
    }

    internal fun saveOAuth(oauth: MangaBakaOAuth?) {
        trackPreferences.trackToken(id).set(json.encodeToString(oauth))
    }

    internal fun loadOAuth(): MangaBakaOAuth? {
        return try {
            json.decodeFromString<MangaBakaOAuth>(trackPreferences.trackToken(id).get())
        } catch (e: Exception) {
            null
        }
    }

    private fun today(): Long = TrackDates.today(clock)

    private fun nowEpochSeconds(): Long = clock.now().epochSeconds
}
