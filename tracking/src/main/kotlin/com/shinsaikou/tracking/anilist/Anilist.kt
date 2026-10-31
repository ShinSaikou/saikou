// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/Anilist.kt (modified: yes)
package com.shinsaikou.tracking.anilist

import com.shinsaikou.core.common.oauth.LoopbackResponse
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
import com.shinsaikou.tracking.anilist.dto.ALOAuth
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import com.shinsaikou.tracking.R as TrackingR

/**
 * Modified, AniList (id 2), on MyAnimeList's pattern:
 *
 * - **Constructor injection**, [BaseTracker] and the domain [Track] row; contributed to the tracker
 *   set with the binding written out (R26), one per process, as MyAnimeList is, so it is in the graph
 *   under id 2 whether configured or not.
 * - **The client** is the build's id on AniList's endpoints ([AnilistClient]) and a test base's
 *   elsewhere; [isConfigured] is false when the build's id is blank.
 * - **Sign-in is [OAuthTracker]'s seam** in place of upstream's `login(token)` from a deep link:
 *   AniList's implicit grant, so [authorizationUrl] asks for a token on the loopback redirect with
 *   the listener's state, and the listener reads the token from the redirect's fragment
 *   ([loopbackResponse]). [completeSignIn] fetches the account with the token first, then stores
 *   the score format, the grant, the display name and the credentials under `NonCancellable`. A
 *   failure is thrown and leaves the stored sign-in as it was; upstream caught every `Throwable` and
 *   signed out.
 * - **A list entry's id** ([Track.libraryId]) missing from the row is read from the owner's list,
 *   and an entry the list lacks is added first, so [update] puts it on the list as MyAnimeList's send
 *   does; upstream threw. [refresh] is upstream's list read, and adds an entry the list lacks as
 *   MyAnimeList's does, where upstream threw (R96 item 9).
 * - **Dates** stamped on a read chapter come from the injected clock, as today on the device
 *   ([TrackDates]), and any chapter read starts an entry with no start date, as MyAnimeList's does;
 *   upstream stamped chapter 1 alone. The status labels are `:i18n`'s.
 * - **The score format** an unknown preference value reads as is [POINT_10], where upstream threw
 *   in the track sheet. Upstream's `init`, which signed out a score format stored as an API v1
 *   integer, is gone: no Saikou build stored one.
 * - Search trims the query and reads `id:` and an entry's address on the website as one entry, as
 *   MyAnimeList's does (`id:` is upstream v0.21.0's, a81b7bce9).
 * - `getIfAuthExpired`, `setAuthExpired` and [logout] are [BaseTracker]'s; the interceptor keeps no
 *   copy of the grant.
 *
 * The status constants, the score formats and their mappings, the bind and the private entries are
 * upstream's.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<Tracker>())
@Inject
class Anilist(
    trackPreferences: TrackPreferences,
    trackRepository: TrackRepository,
    httpClient: TrackerHttpClient,
    private val clock: Clock,
    private val endpointsProvider: () -> AnilistEndpoints,
    private val buildClient: AnilistClient,
    private val json: Json,
) : BaseTracker(TrackerIds.ANILIST, "AniList", trackPreferences, trackRepository, httpClient, clock),
    OAuthTracker,
    DeletableTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L
        const val REREADING = 6L

        const val POINT_100 = "POINT_100"
        const val POINT_10 = "POINT_10"
        const val POINT_10_DECIMAL = "POINT_10_DECIMAL"
        const val POINT_5 = "POINT_5"
        const val POINT_3 = "POINT_3"

        private val SCORE_TYPES = setOf(POINT_100, POINT_10, POINT_10_DECIMAL, POINT_5, POINT_3)

        private const val SEARCH_ID_PREFIX = "id:"

        /** An entry's page on the website, with or without the scheme, `www.` and the title after the id. */
        private val ENTRY_ADDRESS = Regex(
            """(?:https?://)?(?:www\.)?anilist\.co/manga/(\d+)(?:[/?#].*)?""",
            RegexOption.IGNORE_CASE,
        )

        /** How long a token of AniList's implicit grant lives, as upstream v0.21.0 counts it. */
        internal val TOKEN_LIFETIME = 365.days
    }

    private val interceptor by lazy { AnilistInterceptor(this, json, ::nowEpochMillis) }
    private val api by lazy { AnilistApi(client, interceptor, endpointsProvider, ::clientId, json) }

    override val supportsReadingDates: Boolean = true

    override val supportsPrivateTracking: Boolean = true

    private val scorePreference = trackPreferences.anilistScoreType

    /** Configured while there is a client to sign in with: the build's id, or a test base's. */
    override val isConfigured: Boolean
        get() = clientId().isNotBlank()

    /** Where this call goes: AniList, or a test base. */
    internal fun endpoints(): AnilistEndpoints = endpointsProvider()

    /** The build's client id on AniList's endpoints; the fixture's on any test base. */
    internal fun clientId(): String =
        if (endpoints().isAniList) buildClient.clientId else AnilistClient.TEST_BASE_CLIENT_ID

    override fun getLogo() = TrackingR.drawable.brand_anilist

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ, REREADING)
    }

    override fun getStatus(status: Long): Int? = when (status) {
        READING -> R.string.track_status_reading
        PLAN_TO_READ -> R.string.track_status_plan_to_read
        COMPLETED -> R.string.track_status_completed
        ON_HOLD -> R.string.track_status_on_hold
        DROPPED -> R.string.track_status_dropped
        REREADING -> R.string.track_status_repeating
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = REREADING

    override fun getCompletionStatus(): Long = COMPLETED

    /** The account's score format, as AniList named it at sign-in; one this code does not know is [POINT_10]. */
    internal fun scoreType(): String = scorePreference.get().takeIf { it in SCORE_TYPES } ?: POINT_10

    override fun getScoreList(): List<String> {
        return when (scoreType()) {
            // 100 point
            POINT_100 -> IntRange(0, 100).map(Int::toString)
            // 5 stars
            POINT_5 -> IntRange(0, 5).map { "$it ★" }
            // Smiley
            POINT_3 -> listOf("-", "😦", "😐", "😊")
            // 10 point decimal
            POINT_10_DECIMAL -> IntRange(0, 100).map { (it / 10f).toString() }
            // 10 point
            else -> IntRange(0, 10).map(Int::toString)
        }
    }

    override fun get10PointScore(track: Track): Double {
        // Score is stored in 100 point format
        return track.score / 10.0
    }

    override fun indexToScore(index: Int): Double {
        return when (scoreType()) {
            // 100 point
            POINT_100 -> index.toDouble()
            // 5 stars
            POINT_5 -> when (index) {
                0 -> 0.0
                else -> index * 20.0 - 10.0
            }
            // Smiley
            POINT_3 -> when (index) {
                0 -> 0.0
                else -> index * 25.0 + 10.0
            }
            // 10 point decimal
            POINT_10_DECIMAL -> index.toDouble()
            // 10 point
            else -> index * 10.0
        }
    }

    override fun displayScore(track: Track): String {
        val score = track.score

        return when (scoreType()) {
            POINT_5 -> when (score) {
                0.0 -> "0 ★"
                else -> "${((score + 10) / 20).toInt()} ★"
            }

            POINT_3 -> when {
                score == 0.0 -> "0"
                score <= 35 -> "😦"
                score <= 60 -> "😐"
                else -> "😊"
            }

            else -> track.toApiScore(scoreType())
        }
    }

    private suspend fun add(track: Track): Track {
        return api.addLibManga(track)
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        var updated = withLibraryId(track)

        if (updated.status != COMPLETED) {
            if (didReadChapter) {
                if (updated.lastChapterRead.toLong() == updated.totalChapters && updated.totalChapters > 0) {
                    updated = updated.copy(status = COMPLETED, finishDate = today())
                } else if (updated.status != REREADING) {
                    updated = updated.copy(status = READING)
                }
                if (updated.startDate == 0L && updated.status != REREADING) {
                    updated = updated.copy(startDate = today())
                }
            }
        }

        return api.updateLibManga(updated)
    }

    /**
     * [track] with its list entry's id: the row's own, else the owner's list's, else the id of the
     * entry added to the list for it. Upstream read the list and threw when the entry was not on it.
     */
    private suspend fun withLibraryId(track: Track): Track {
        if (track.libraryId != null && track.libraryId != 0L) return track
        val listed = api.findLibManga(track, userId()) ?: add(track)
        return track.copy(libraryId = listed.libraryId)
    }

    override suspend fun delete(track: Track) {
        val libraryId = track.libraryId?.takeIf { it != 0L }
            ?: api.findLibManga(track, userId())?.libraryId
            ?: return
        api.deleteLibManga(libraryId)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val remoteTrack = api.findLibManga(track, userId())
        return if (remoteTrack != null) {
            // Upstream's copyPersonalFrom without the remote's privacy: the owner's choice in the sheet is sent.
            var bound = track.copy(
                lastChapterRead = remoteTrack.lastChapterRead,
                score = remoteTrack.score,
                status = remoteTrack.status,
                startDate = remoteTrack.startDate,
                finishDate = remoteTrack.finishDate,
                libraryId = remoteTrack.libraryId,
            )

            if (bound.status != COMPLETED) {
                val isRereading = bound.status == REREADING
                bound = bound.copy(status = if (!isRereading && hasReadChapters) READING else bound.status)
            }

            update(bound)
        } else {
            // Set default fields if it's not found in the list
            add(track.copy(status = if (hasReadChapters) READING else PLAN_TO_READ, score = 0.0))
        }
    }

    override suspend fun search(query: String): List<TrackSearchResult> {
        val query = query.trim()
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).trim().toIntOrNull()?.let { id ->
                return listOfNotNull(api.getMangaDetails(id))
            }
        }

        ENTRY_ADDRESS.matchEntire(query)?.groupValues?.get(1)?.toIntOrNull()?.let { id ->
            return listOfNotNull(api.getMangaDetails(id))
        }

        return api.search(query)
    }

    /**
     * The owner's list entry laid over [track], with its title and chapter count, or the entry added
     * as the row has it when the list lacks it. It never calls [update], so the retry worker alone
     * decides whether to send the chapter (R96 item 9).
     */
    override suspend fun refresh(track: Track): Track {
        return api.findLibManga(track, userId()) ?: add(track)
    }

    /** No password sign-in: AniList signs in through the browser ([completeSignIn]). */
    override suspend fun login(username: String, password: String) {
        throw UnsupportedOperationException("AniList signs in through the browser")
    }

    /** AniList's implicit grant puts the token in the redirect's fragment. */
    override val loopbackResponse: LoopbackResponse
        get() = LoopbackResponse.TOKEN

    /**
     * The implicit grant's page; it takes no PKCE challenge, so [pkce] goes unused. [redirectUri]
     * goes unused too: AniList redirects to the client's registered address, which is the loopback's.
     */
    override fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl =
        api.authUrl(state)

    /**
     * [code] is the access token the redirect carried: nothing is exchanged. The account is read
     * with it before anything is stored; its score format, the grant with a year's lifetime, its
     * name and then the credentials (its AniList id, as upstream stores it) are written after.
     */
    override suspend fun completeSignIn(code: String, redirectUri: String, pkce: Pkce) {
        val user = api.getCurrentUser(code)
        val oauth = ALOAuth(accessToken = code, expires = nowEpochMillis() + TOKEN_LIFETIME.inWholeMilliseconds)
        withContext(NonCancellable) {
            scorePreference.set(user.mediaListOptions.scoreFormat)
            saveOAuth(oauth)
            saveDisplayUsername(user.name)
            saveCredentials(user.id.toString(), oauth.accessToken)
        }
    }

    internal fun saveOAuth(alOAuth: ALOAuth?) {
        trackPreferences.trackToken(id).set(json.encodeToString(alOAuth))
    }

    internal fun loadOAuth(): ALOAuth? {
        return try {
            json.decodeFromString<ALOAuth>(trackPreferences.trackToken(id).get())
        } catch (e: Exception) {
            null
        }
    }

    /** The signed-in account's AniList id, which upstream stores as the username. */
    private fun userId(): Int = getUsername().toIntOrNull() ?: throw IOException("AniList: User is not authenticated")

    private fun today(): Long = TrackDates.today(clock)

    private fun nowEpochMillis(): Long = clock.now().toEpochMilliseconds()
}
