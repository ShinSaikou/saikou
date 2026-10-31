// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeList.kt (modified: yes)
package com.shinsaikou.tracking.myanimelist

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.BaseTracker
import com.shinsaikou.tracking.DeletableTracker
import com.shinsaikou.tracking.EntryRead
import com.shinsaikou.tracking.ListComparingTracker
import com.shinsaikou.tracking.ListRead
import com.shinsaikou.tracking.OAuthTracker
import com.shinsaikou.tracking.TrackDates
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.myanimelist.dto.MALOAuth
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Clock
import com.shinsaikou.tracking.R as TrackingR

/**
 * Modified (phase 3 unit T2), MyAnimeList (id 1), the one tracker in the owner's scope:
 *
 * - **Constructor injection**, T1's [BaseTracker] and the domain [Track] row; contributed to the
 *   tracker set with the binding written out (R26), one per process since its refresh is
 *   single-flight.
 * - **Sign-in is T1's [OAuthTracker] seam** in place of `login(authCode)`: [authorizationUrl]
 *   builds the page from the caller's redirect, state and PKCE pair, and [completeSignIn]
 *   exchanges the code and fetches the user first, then stores the token, the display name and the
 *   credentials under `NonCancellable`, so a cancel cannot leave half a sign-in. A failure is thrown
 *   to the caller and leaves the stored sign-in as it was; upstream caught every `Throwable` and
 *   logged out.
 * - **The client** is the build's id on MyAnimeList's endpoints ([MyAnimeListClient]) and the
 *   fake's on any test base; [isConfigured] is false when the build's id is blank and no test base
 *   is set. Both read the endpoints binding per call.
 * - `getIfAuthExpired` and `setAuthExpired` are [BaseTracker]'s; [logout] is too (it deletes the
 *   token), since the interceptor keeps no copy of the grant.
 * - Dates stamped on a read chapter come from the injected clock, as today on the device
 *   ([TrackDates]); the status labels are `:i18n`'s. Any chapter read starts an entry with no start
 *   date, and a start date set is never moved: upstream stamped chapter 1 alone, over any date, so a
 *   first read of 1.1, 0 or 5, or a mark-read jump, never set one.
 * - **A [ListComparingTracker]** (phase 4 unit TC): [readList] reads the owner's list with its
 *   progress, and [readEntry] reads one entry as the service holds it, with the service's dates only
 *   and no write, where [refresh] adds an entry missing from the list back.
 *
 * Search (the `id:` and `my:` prefixes, the 64-character limit), the list status, score and dates,
 * the pagination amount, [MALTitleNotApproved] and the status constants are upstream's. The query is
 * trimmed first (upstream trims the `id:` number since v0.21.0, E33), and an entry's address on the
 * website, as copied from a browser, reads that one entry as `id:` does.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<Tracker>())
@Inject
class MyAnimeList(
    trackPreferences: TrackPreferences,
    trackRepository: TrackRepository,
    httpClient: TrackerHttpClient,
    private val clock: Clock,
    private val endpointsProvider: () -> MyAnimeListEndpoints,
    private val buildClient: MyAnimeListClient,
    private val json: Json,
) : BaseTracker(TrackerIds.MYANIMELIST, "MyAnimeList", trackPreferences, trackRepository, httpClient, clock),
    OAuthTracker,
    DeletableTracker,
    ListComparingTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 6L
        const val REREADING = 7L

        private const val SEARCH_ID_PREFIX = "id:"
        private const val SEARCH_LIST_PREFIX = "my:"

        /** An entry's page on the website, with or without the scheme, `www.` and the title after the id. */
        private val ENTRY_ADDRESS = Regex(
            """(?:https?://)?(?:www\.)?myanimelist\.net/manga/(\d+)(?:[/?#].*)?""",
            RegexOption.IGNORE_CASE,
        )

        private val SCORE_LIST = IntRange(0, 10)
            .map(Int::toString)
    }

    private val interceptor by lazy { MyAnimeListInterceptor(this, json, ::nowEpochSeconds) }
    private val api by lazy {
        MyAnimeListApi(id, client, interceptor, endpointsProvider, ::clientId, json, ::nowEpochSeconds)
    }

    override val supportsReadingDates: Boolean = true

    /** Configured while there is a client to sign in with: the build's id, or a test base's. */
    override val isConfigured: Boolean
        get() = clientId().isNotBlank()

    /** Where this call goes: MyAnimeList, or in a debug build the test base a test set. */
    internal fun endpoints(): MyAnimeListEndpoints = endpointsProvider()

    /** The build's client id on MyAnimeList's endpoints; the fake's on any test base. */
    internal fun clientId(): String =
        if (endpoints().isMyAnimeList) buildClient.clientId else MyAnimeListClient.TEST_BASE_CLIENT_ID

    override fun getLogo() = TrackingR.drawable.brand_myanimelist

    override fun getStatusList(): List<Long> {
        return listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ, REREADING)
    }

    override fun getStatus(status: Long): Int? = when (status) {
        READING -> R.string.track_status_reading
        PLAN_TO_READ -> R.string.track_status_plan_to_read
        COMPLETED -> R.string.track_status_completed
        ON_HOLD -> R.string.track_status_on_hold
        DROPPED -> R.string.track_status_dropped
        REREADING -> R.string.track_status_rereading
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = REREADING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun displayScore(track: Track): String {
        return track.score.toInt().toString()
    }

    private suspend fun add(track: Track): Track {
        return api.updateItem(track)
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        var updated = track
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

        return api.updateItem(updated)
    }

    override suspend fun delete(track: Track) {
        api.deleteItem(track.remoteId)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val lookup = api.findListItem(track)
        return if (lookup.onList) {
            // Upstream copied the list's fields onto the row here; the lookup's row already carries them.
            var remoteTrack = lookup.track

            if (remoteTrack.status != COMPLETED) {
                val isRereading = remoteTrack.status == REREADING
                remoteTrack = remoteTrack.copy(
                    status = if (!isRereading && hasReadChapters) READING else remoteTrack.status,
                )
            }

            update(remoteTrack)
        } else {
            // Set default fields if it's not found in the list
            add(lookup.track.copy(status = if (hasReadChapters) READING else PLAN_TO_READ, score = 0.0))
        }
    }

    override suspend fun search(query: String): List<TrackSearchResult> {
        val query = query.trim()
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).trim().toIntOrNull()?.let { id ->
                return listOf(api.getMangaDetails(id))
            }
        }

        ENTRY_ADDRESS.matchEntire(query)?.groupValues?.get(1)?.toIntOrNull()?.let { id ->
            return listOf(api.getMangaDetails(id))
        }

        if (query.startsWith(SEARCH_LIST_PREFIX)) {
            query.substringAfter(SEARCH_LIST_PREFIX).let { title ->
                return api.findListItems(title)
            }
        }

        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val lookup = api.findListItem(track)
        return if (lookup.onList) lookup.track else add(lookup.track)
    }

    override suspend fun readList(remoteIds: Set<Long>): ListRead = api.readListProgress(remoteIds)

    /**
     * [findListItem][MyAnimeListApi.findListItem] on [track] with its dates cleared: the lookup keeps
     * the row's own date wherever the service has none, and a send built on the answer would put back
     * a date cleared on the website. A 404 is [EntryRead.Gone]; nothing is ever added back.
     */
    override suspend fun readEntry(track: Track): EntryRead {
        val lookup = try {
            api.findListItem(track.copy(startDate = 0, finishDate = 0))
        } catch (e: HttpException) {
            if (e.code == 404) return EntryRead.Gone
            throw e
        }
        return if (lookup.onList) {
            EntryRead.OnList(lookup.track)
        } else {
            EntryRead.OffList(track.copy(totalChapters = lookup.track.totalChapters))
        }
    }

    /** No password sign-in: MyAnimeList signs in through the browser ([completeSignIn]). */
    override suspend fun login(username: String, password: String) {
        throw UnsupportedOperationException("MyAnimeList signs in through the browser")
    }

    override fun authorizationUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl =
        api.authUrl(redirectUri, state, pkce)

    override suspend fun completeSignIn(code: String, redirectUri: String, pkce: Pkce) {
        val oauth = api.getAccessToken(code, redirectUri, pkce)
        val username = api.getCurrentUser(oauth.accessToken)
        withContext(NonCancellable) {
            saveOAuth(oauth)
            saveDisplayUsername(username)
            saveCredentials(username, oauth.accessToken)
        }
    }

    internal fun saveOAuth(oAuth: MALOAuth?) {
        trackPreferences.trackToken(id).set(json.encodeToString(oAuth))
    }

    internal fun loadOAuth(): MALOAuth? {
        return try {
            json.decodeFromString<MALOAuth>(trackPreferences.trackToken(id).get())
        } catch (e: Exception) {
            null
        }
    }

    private fun today(): Long = TrackDates.today(clock)

    private fun nowEpochSeconds(): Long = clock.now().epochSeconds
}
