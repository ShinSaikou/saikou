// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/Comick.kt (modified: yes)
package com.shinsaikou.tracking.comick

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.BaseTracker
import com.shinsaikou.tracking.DeletableTracker
import com.shinsaikou.tracking.OAuthTracker
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.comick.dto.ComickOAuth
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
import kotlin.time.Clock
import com.shinsaikou.tracking.R as TrackingR

/**
 * Modified, Comick (id 12), the tracking site at comick.dev, on MangaBaka's pattern:
 *
 * - **Constructor injection**, [BaseTracker] and the domain [Track] row; contributed to the tracker
 *   set with the binding written out (R26), one per process since its refresh is single-flight, so
 *   it is in the graph under id 12 whether configured or not.
 * - **The client** is the build's id on Comick's endpoints ([ComickClient]) and a test base's
 *   elsewhere; [isConfigured] is false when the build's id is blank. Upstream's id is not in this tree.
 * - **Sign-in is [OAuthTracker]'s seam** in place of upstream's `login(code)` from a deep link: the
 *   code flow with an S256 challenge on the redirect the caller names, the trackers' loopback today.
 *   [completeSignIn] exchanges the code, which fails when the owner left `offline_access` or
 *   `library:write` off ([ComickApi.getAccessToken]), reads the account with the new token, then
 *   stores the grant, the display name and the credentials (the account's id) under
 *   `NonCancellable` and [grantLock]. A failure is thrown and leaves the stored sign-in as it was;
 *   upstream caught every `Exception` and signed out.
 * - **[refresh]** adds an entry the library lacks, as MangaBaka's does, where upstream threw, and
 *   never calls [update]. **[update]** adds an entry the library no longer has back
 *   ([ComickApi.updateLibManga]).
 * - **Search** trims the query; `id:` reads the one title with that HID, as upstream's does.
 * - The status labels are `:i18n`'s. `getIfAuthExpired` and `setAuthExpired` are [BaseTracker]'s;
 *   the interceptor keeps no copy of the grant. Upstream's `updateUserConfig` has no caller here:
 *   the name is read at each sign-in. Comick has no start or finish dates and no private entries, so
 *   [supportsReadingDates] and [supportsPrivateTracking] stay false, and nothing stamps a date.
 *
 * The status constants and their order, the 0 to 10 scores, the HID mapping and the bind are
 * upstream's.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<Tracker>())
@Inject
class Comick(
    trackPreferences: TrackPreferences,
    trackRepository: TrackRepository,
    httpClient: TrackerHttpClient,
    private val clock: Clock,
    private val endpointsProvider: () -> ComickEndpoints,
    private val buildClient: ComickClient,
    private val json: Json,
) : BaseTracker(TrackerIds.COMICK, "Comick", trackPreferences, trackRepository, httpClient, clock),
    OAuthTracker,
    DeletableTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L

        private const val SEARCH_ID_PREFIX = "id:"
    }

    /**
     * The lock the stored grant is replaced under: the interceptor's refresh, the sign-in's write and
     * the sign-out's clear, so none of them sees the others' half-done.
     */
    internal val grantLock = Any()

    private val interceptor by lazy { ComickInterceptor(this, json, ::nowEpochSeconds) }
    private val api by lazy {
        ComickApi(id, client, interceptor, endpointsProvider, ::clientId, json, ::nowEpochSeconds)
    }

    /** Configured while there is a client to sign in with: the build's id, or a test base's. */
    override val isConfigured: Boolean
        get() = clientId().isNotBlank()

    /** Where this call goes: Comick, or a test base. */
    internal fun endpoints(): ComickEndpoints = endpointsProvider()

    /** The build's client id on Comick's endpoints; the fixture's on any test base. */
    internal fun clientId(): String =
        if (endpoints().isComick) buildClient.clientId else ComickClient.TEST_BASE_CLIENT_ID

    override fun getLogo() = TrackingR.drawable.brand_comick

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)
    }

    override fun getStatus(status: Long): Int? = when (status) {
        READING -> R.string.track_status_reading
        COMPLETED -> R.string.track_status_completed
        ON_HOLD -> R.string.track_status_on_hold
        DROPPED -> R.string.track_status_dropped
        PLAN_TO_READ -> R.string.track_status_plan_to_read
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    /** Comick has no rereading status. */
    override fun getRereadingStatus(): Long = -1L

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): List<String> = IntRange(0, ComickApi.MAX_SCORE).map(Int::toString)

    override fun displayScore(track: Track): String = track.score.toInt().toString()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        var updated = track
        if (updated.status != COMPLETED && updated.status != READING && didReadChapter) {
            updated = updated.copy(status = READING)
        }

        return api.updateLibManga(updated)
    }

    override suspend fun delete(track: Track) {
        api.deleteLibManga(track.remoteId)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val remoteTrack = api.findLibManga(track)
        return if (remoteTrack != null) {
            // Upstream's copyPersonalFrom: Comick has no dates or privacy to copy.
            update(
                track.copy(
                    lastChapterRead = remoteTrack.lastChapterRead,
                    score = remoteTrack.score,
                    status = remoteTrack.status,
                    title = remoteTrack.title,
                ),
            )
        } else {
            // Set default fields if it's not found in the list
            api.addLibManga(track.copy(status = if (hasReadChapters) READING else PLAN_TO_READ, score = 0.0))
        }
    }

    override suspend fun search(query: String): List<TrackSearchResult> {
        val query = query.trim()
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            val hid = query.substringAfter(SEARCH_ID_PREFIX).trim()
            if (hid.isEmpty()) return emptyList()
            return listOfNotNull(api.getMangaDetails(hid))
        }

        return api.search(query)
    }

    /**
     * The owner's library entry laid over [track], or the entry added as the row has it when the
     * library lacks it.
     */
    override suspend fun refresh(track: Track): Track {
        return api.findLibManga(track) ?: api.addLibManga(track)
    }

    /** No password sign-in: Comick signs in through the browser ([completeSignIn]). */
    override suspend fun login(username: String, password: String) {
        throw UnsupportedOperationException("Comick signs in through the browser")
    }

    /**
     * Comick's page for one sign-in. [redirectUri] is the caller's, today the trackers' loopback;
     * [completeSignIn] sends the same one again, so the redirect is named nowhere in this package.
     */
    override fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl =
        api.authUrl(redirectUri, state, pkce)

    /**
     * Exchanges [code], reads the account with the new token, then stores the grant, its name and
     * the credentials (its Comick id, with the access token as upstream stores it). A grant without
     * `offline_access` or `library:write` throws before anything is stored.
     */
    override suspend fun completeSignIn(code: String, redirectUri: String, pkce: Pkce) {
        val oauth = api.getAccessToken(code, redirectUri, pkce)
        val user = api.getCurrentUser(oauth.accessToken)
        withContext(NonCancellable) {
            synchronized(grantLock) {
                saveOAuth(oauth)
                saveDisplayUsername(user.displayName())
                saveCredentials(user.id, oauth.accessToken)
            }
        }
    }

    /** Clears the sign-in under [grantLock]. Comick names no revocation endpoint, so the grant is left to lapse. */
    override fun logout() {
        synchronized(grantLock) { super.logout() }
    }

    internal fun saveOAuth(oauth: ComickOAuth?) {
        trackPreferences.trackToken(id).set(json.encodeToString(oauth))
    }

    internal fun loadOAuth(): ComickOAuth? {
        return try {
            json.decodeFromString<ComickOAuth>(trackPreferences.trackToken(id).get())
        } catch (e: Exception) {
            null
        }
    }

    private fun nowEpochSeconds(): Long = clock.now().epochSeconds
}
