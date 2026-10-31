// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/BaseTracker.kt (modified: yes)
package com.shinsaikou.tracking

import androidx.annotation.CallSuper
import com.shinsaikou.domain.preferences.TrackPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Clock

/**
 * Modified: constructor injection in place of Injekt; [TrackRepository] in place of the `AddTracks`
 * and `InsertTrack` fields, since binding with local progress is the `AddTracks` interactor's job
 * here and not the tracker's (so there is no `register`); credentials through [TrackPreferences] by
 * id; the immutable [Track] row, so every `setRemote*` returns the row the service answered; that row
 * is stored only while the pair still names the entry it was sent to (phase 3 unit T3), so a rebind
 * or a remove that lands while the call is on the wire is kept; a failed update is logged and thrown
 * to the caller instead of shown as a toast; [client] is [TrackerHttpClient]'s,
 * not the shared one; [isConfigured], [isAuthExpired] and [setAuthExpired] (upstream keeps the last
 * two on MyAnimeList alone); [logout] also deletes the stored token; the finish date reads the
 * injected [clock]. Dates stamped here are today on the device ([TrackDates]), and are never left
 * out where upstream left them out: Completed set by hand finishes an entry with no finish date, and
 * the first chapter set by hand starts one with no start date.
 */
abstract class BaseTracker(
    override val id: Long,
    override val name: String,
    protected val trackPreferences: TrackPreferences,
    private val trackRepository: TrackRepository,
    private val httpClient: TrackerHttpClient,
    private val clock: Clock,
) : Tracker {

    open val client: OkHttpClient
        get() = httpClient.client

    // Application and remote support for reading dates
    override val supportsReadingDates: Boolean = false

    override val supportsPrivateTracking: Boolean = false

    /** Configured unless a subclass needs a build-time key that is blank. */
    override val isConfigured: Boolean
        get() = true

    // TODO: Store all scores as 10 point in the future maybe?
    override fun get10PointScore(track: Track): Double {
        return track.score
    }

    override fun indexToScore(index: Int): Double {
        return index.toDouble()
    }

    @CallSuper
    override fun logout() {
        trackPreferences.setCredentials(id, "", "")
        trackPreferences.trackToken(id).delete()
    }

    override val isLoggedIn: Boolean
        get() = getUsername().isNotEmpty() &&
            getPassword().isNotEmpty()

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        combine(
            trackPreferences.trackUsername(id).changes(),
            trackPreferences.trackPassword(id).changes(),
        ) { username, password ->
            username.isNotEmpty() && password.isNotEmpty()
        }
    }

    override val isAuthExpired: Boolean
        get() = trackPreferences.trackAuthExpired(id).get()

    override val isAuthExpiredFlow: Flow<Boolean> by lazy {
        trackPreferences.trackAuthExpired(id).changes()
    }

    /** The service refused the stored sign-in; cleared by [saveCredentials] and [logout]. */
    fun setAuthExpired() {
        trackPreferences.trackAuthExpired(id).set(true)
    }

    override fun getUsername() = trackPreferences.trackUsername(id).get()

    override fun getDisplayUsername(): String = trackPreferences.trackDisplayUsername(id).get()

    fun saveDisplayUsername(displayName: String) = trackPreferences.trackDisplayUsername(id).set(displayName)

    fun getPassword() = trackPreferences.trackPassword(id).get()

    fun saveCredentials(username: String, password: String) {
        trackPreferences.setCredentials(id, username, password)
    }

    override suspend fun setRemoteStatus(track: Track, status: Long): Track {
        var updated = track.copy(status = status)
        if (updated.status == getCompletionStatus()) {
            if (updated.totalChapters != 0L) {
                updated = updated.copy(lastChapterRead = updated.totalChapters.toDouble())
            }
            if (updated.finishDate == 0L) {
                updated = updated.copy(finishDate = TrackDates.today(clock))
            }
        }
        return updateRemote(updated)
    }

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int): Track {
        var updated = track
        if (
            updated.lastChapterRead == 0.0 &&
            updated.lastChapterRead < chapterNumber &&
            updated.status != getRereadingStatus()
        ) {
            updated = updated.copy(status = getReadingStatus())
            if (updated.startDate == 0L) {
                updated = updated.copy(startDate = TrackDates.today(clock))
            }
        }
        updated = updated.copy(lastChapterRead = chapterNumber.toDouble())
        if (updated.totalChapters != 0L && updated.lastChapterRead.toLong() == updated.totalChapters) {
            updated = updated.copy(
                status = getCompletionStatus(),
                finishDate = TrackDates.today(clock),
            )
        }
        return updateRemote(updated)
    }

    override suspend fun setRemoteScore(track: Track, scoreString: String): Track {
        return updateRemote(track.copy(score = indexToScore(getScoreList().indexOf(scoreString))))
    }

    override suspend fun setRemoteStartDate(track: Track, epochMillis: Long): Track {
        return updateRemote(track.copy(startDate = epochMillis))
    }

    override suspend fun setRemoteFinishDate(track: Track, epochMillis: Long): Track {
        return updateRemote(track.copy(finishDate = epochMillis))
    }

    override suspend fun setRemotePrivate(track: Track, private: Boolean): Track {
        return updateRemote(track.copy(private = private))
    }

    private suspend fun updateRemote(track: Track): Track = withIOContext {
        try {
            val updated = update(track)
            // Stored only while the pair still names the entry sent to: a rebind synced in, or a
            // remove, while the call was on the wire stays (phase 3 unit T3). Upstream inserts as is.
            if (!trackRepository.insertIfBoundTo(updated, track.remoteId)) {
                logcat(LogPriority.INFO) {
                    "Not storing the answer for manga ${track.mangaId} on tracker $id: the pair was rebound or removed"
                }
            }
            updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to update remote track data id=$id" }
            throw e
        }
    }
}
