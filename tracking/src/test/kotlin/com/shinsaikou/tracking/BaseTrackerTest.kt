package com.shinsaikou.tracking

import app.cash.turbine.test
import com.shinsaikou.domain.preferences.TrackPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The vendored [BaseTracker] (phase 3 unit T1): credentials by id in [TrackPreferences], the
 * auth-expired state, logout, and upstream's `setRemote*` rules over the immutable row, each storing
 * the row the service returned while the pair still names the entry it was sent to (phase 3 unit
 * T3).
 */
class BaseTrackerTest {

    private val preferences = MapPreferenceStore()
    private val trackPreferences = TrackPreferences(preferences)
    private val repository = FakeTrackRepository()
    private val httpClient = mockk<TrackerHttpClient>()

    /** A service that answers every update with the row it was sent, or fails with [failure]. */
    private inner class ServiceTracker : BaseTracker(
        id = 1L,
        name = "Service",
        trackPreferences = trackPreferences,
        trackRepository = repository,
        httpClient = httpClient,
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(NOW)
        },
    ) {
        val sent = mutableListOf<Track>()
        var failure: Exception? = null
        var beforeAnswer: suspend (Track) -> Unit = {}

        override fun getLogo(): Int = 0
        override fun getStatusList(): List<Long> = listOf(READING, COMPLETED, REREADING)
        override fun getStatus(status: Long): Int? = null
        override fun getReadingStatus(): Long = READING
        override fun getRereadingStatus(): Long = REREADING
        override fun getCompletionStatus(): Long = COMPLETED
        override fun getScoreList(): List<String> = (0..10).map(Int::toString)
        override fun displayScore(track: Track): String = track.score.toString()
        override suspend fun search(query: String): List<TrackSearchResult> = emptyList()
        override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = track
        override suspend fun refresh(track: Track): Track = track
        override suspend fun login(username: String, password: String) = saveCredentials(username, password)

        override suspend fun update(track: Track, didReadChapter: Boolean): Track {
            failure?.let { throw it }
            sent += track
            beforeAnswer(track)
            return track.copy(remoteUrl = "https://tracker.example.invalid/answered")
        }
    }

    private val tracker = ServiceTracker()

    @Test
    fun `signed in only with both credentials, and the flow follows them`() = runTest {
        tracker.isLoggedIn shouldBe false
        tracker.isLoggedInFlow.test {
            awaitItem() shouldBe false
            tracker.saveCredentials("owner", "")
            tracker.saveCredentials("owner", "token")
            // Two writes land as one pair; the flow settles on signed in.
            expectMostRecentItem() shouldBe true
        }
        tracker.isLoggedIn shouldBe true
        tracker.getUsername() shouldBe "owner"
    }

    @Test
    fun `the auth-expired state is set by the tracker and cleared by a new sign-in`() = runTest {
        tracker.saveCredentials("owner", "token")
        tracker.setAuthExpired()

        tracker.isAuthExpired shouldBe true
        tracker.isLoggedIn shouldBe true
        tracker.isAuthExpiredFlow.test {
            awaitItem() shouldBe true
            tracker.saveCredentials("owner", "new token")
            awaitItem() shouldBe false
        }
    }

    @Test
    fun `logout clears the credentials, the token and the auth-expired state, and keeps the display name`() {
        tracker.saveCredentials("owner", "token")
        tracker.saveDisplayUsername("Owner")
        trackPreferences.trackToken(1).set("{\"access_token\":\"t\"}")
        tracker.setAuthExpired()

        tracker.logout()

        tracker.isLoggedIn shouldBe false
        tracker.isAuthExpired shouldBe false
        trackPreferences.trackToken(1).isSet() shouldBe false
        tracker.getDisplayUsername() shouldBe "Owner"
    }

    @Test
    fun `the credentials are the private per-tracker keys upstream uses`() {
        tracker.saveCredentials("owner", "token")
        tracker.setAuthExpired()

        preferences.values.keys shouldBe setOf(
            "__PRIVATE_pref_mangasync_username_1",
            "__PRIVATE_pref_mangasync_password_1",
            "__PRIVATE_pref_tracker_auth_expired_1",
        )
    }

    /**
     * The dates stamped are today on the device, as the date picker stores a day: its midnight in
     * the device's zone. [NOW] is 22:13 UTC, already the next day at +09:00.
     */
    @Test
    fun `the first chapter set starts the entry today, and the last completes it today, east and west of UTC`() = runTest {
        for ((offset, today) in ZONES) {
            inZone(offset) {
                val first = tracker.setRemoteLastChapterRead(track(id = 10, trackerId = 1, totalChapters = 5, status = PLANNED), 3)
                first.status shouldBe READING
                first.lastChapterRead shouldBe 3.0
                first.startDate shouldBe localMidnight(today, offset)

                val last = tracker.setRemoteLastChapterRead(first, 5)
                last.status shouldBe COMPLETED
                last.startDate shouldBe localMidnight(today, offset)
                last.finishDate shouldBe localMidnight(today, offset)
            }
        }
    }

    @Test
    fun `a chapter set by hand keeps a start date the row has`() = runTest {
        val sent = tracker.setRemoteLastChapterRead(track(id = 10, trackerId = 1, status = PLANNED, startDate = 1234L), 2)

        sent.status shouldBe READING
        sent.startDate shouldBe 1234L
    }

    @Test
    fun `completed set by hand finishes the entry today unless it has a finish date, east and west of UTC`() = runTest {
        for ((offset, today) in ZONES) {
            inZone(offset) {
                tracker.setRemoteStatus(track(id = 10, trackerId = 1, totalChapters = 12), COMPLETED).finishDate shouldBe
                    localMidnight(today, offset)
            }
        }
        val dated = track(id = 10, trackerId = 1).copy(finishDate = 1234L)
        tracker.setRemoteStatus(dated, COMPLETED).finishDate shouldBe 1234L
        tracker.setRemoteStatus(track(id = 10, trackerId = 1), READING).finishDate shouldBe 0L
    }

    @Test
    fun `a re-read keeps its status on the first chapter pushed`() = runTest {
        val sent = tracker.setRemoteLastChapterRead(track(id = 10, trackerId = 1, status = REREADING), 1)
        sent.status shouldBe REREADING
    }

    @Test
    fun `completing with a known total pushes the total as read`() = runTest {
        val sent = tracker.setRemoteStatus(track(id = 10, trackerId = 1, totalChapters = 12), COMPLETED)
        sent.lastChapterRead shouldBe 12.0
    }

    @Test
    fun `a setRemote call stores and returns the row the service answered`() = runTest {
        repository.rows[10] = track(id = 10, trackerId = 1)

        val answered = tracker.setRemoteStartDate(track(id = 10, trackerId = 1), 1234L)

        answered.remoteUrl shouldBe "https://tracker.example.invalid/answered"
        answered.startDate shouldBe 1234L
        repository.rows.getValue(10) shouldBe answered
    }

    @Test
    fun `a setRemote answer is not stored over a rebind or a remove made while the call was on the wire`() = runTest {
        val bound = track(id = 10, trackerId = 1)
        repository.rows[10] = bound
        val rebound = bound.copy(id = 11, remoteId = 2002, title = "Another entry")
        tracker.beforeAnswer = { repository.rebind(rebound) }

        val answered = tracker.setRemoteScore(bound, "7")

        tracker.sent.single().remoteId shouldBe bound.remoteId
        answered.score shouldBe 7.0
        repository.rows.values.toList() shouldBe listOf(rebound)

        tracker.beforeAnswer = { repository.deleteIfBoundTo(it.mangaId, it.trackerId, it.remoteId) shouldBe true }
        tracker.setRemoteStatus(rebound, COMPLETED)

        repository.rows.values.shouldBeEmpty()
        repository.inserted.shouldBeEmpty()
    }

    @Test
    fun `a failed update is thrown to the caller and stores nothing`() = runTest {
        tracker.failure = IOException("fixture: 503")

        shouldThrow<IOException> { tracker.setRemoteScore(track(id = 10, trackerId = 1), "7") }

        repository.inserted.shouldBeEmpty()
    }

    @Test
    fun `a tracker is configured unless a subclass says otherwise, and talks through the tracker client`() {
        val client = OkHttpClient()
        io.mockk.every { httpClient.client } returns client

        tracker.isConfigured shouldBe true
        tracker.client shouldBeSameInstanceAs client
    }

    private companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val PLANNED = 3L
        const val REREADING = 6L
        const val NOW = 1_700_000_000_000L

        /** A device east and one west of UTC, with the day [NOW] falls on there. */
        val ZONES = listOf("+09:00" to "2023-11-15", "-05:00" to "2023-11-14")
    }
}
