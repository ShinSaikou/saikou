package com.shinsaikou.tracking.anilist

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.DelayedTrackingScheduler
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.DelayedTrackingUpdate
import com.shinsaikou.tracking.FakeSignInNotifier
import com.shinsaikou.tracking.MANGA_ID
import com.shinsaikou.tracking.RefreshTracks
import com.shinsaikou.tracking.RemoveTrack
import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.DELETED_BODY
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.NOW_MILLIS
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.ONE_YEAR_MILLIS
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.graphql
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.json
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.listBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.listEntry
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.savedBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.searchBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.track
import com.shinsaikou.tracking.anilist.dto.ALOAuth
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.time.Instant

/**
 * AniList's token on every call: its implicit grant has no refresh token, so a token past its year by
 * the clock, or one the API refuses (401, or 400 `Invalid token`), is "sign in again" (Q13): the
 * credentials are kept, nothing more is sent, and the retry worker keeps the push for the next
 * sign-in. Any other refusal is the call's own.
 *
 * [Anilist.refresh] itself, the call the worker makes before every push (R96 item 9): an entry on
 * the owner's list comes back as the list has it and nothing more is sent; an entry off the list is
 * added as the row has it. Both are run alone and through the worker.
 */
class AnilistRefreshTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { AnilistFixture(cacheDir) }
    private val notifier = FakeSignInNotifier()

    @AfterEach
    fun close() {
        fixture.close()
    }

    private fun worker(store: DelayedTrackingStore) =
        DelayedTrackingUpdate(store, fixture.repository, TrackerManager(setOf(fixture.tracker)), notifier)

    private fun store() = DelayedTrackingStore(fixture.preferences, fixture.preferences::write)

    @Test
    fun `a live token is sent as the bearer, and nothing else is asked for`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, searchBody()))

            fixture.tracker.search("fixture").shouldBeEmpty()

            fixture.server.requestCount shouldBe 1
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `a token past its year by the clock sets auth expired before anything is sent, and keeps the credentials`() {
        runBlocking {
            fixture.signedIn(expires = NOW_MILLIS + 30_000)

            val expired = shouldThrow<TrackerAuthExpiredException> { fixture.tracker.search("fixture") }

            expired.trackerId shouldBe TrackerIds.ANILIST
            ErrorKey.of(expired) shouldBe ErrorKey.TRACKER_AUTH_EXPIRED
            fixture.server.requestCount shouldBe 0
            fixture.tracker.isAuthExpired shouldBe true
            fixture.tracker.isLoggedIn shouldBe true
            checkNotNull(fixture.storedToken()).accessToken shouldBe ACCESS_1

            // Expired is final until a new sign-in: the clock going back sends nothing either.
            fixture.now = Instant.fromEpochMilliseconds(NOW_MILLIS - ONE_YEAR_MILLIS)
            shouldThrow<TrackerAuthExpiredException> { fixture.tracker.search("fixture") }
            fixture.server.requestCount shouldBe 0
        }
    }

    @Test
    fun `a token the API refuses with 401, or with 400 Invalid token, is sign in again`() {
        val refusals = listOf(
            json(401, """{"errors":[{"message":"Unauthorized.","status":401}],"data":null}"""),
            json(400, """{"errors":[{"message":"Invalid token","status":400,"locations":[{"line":2,"column":1}]}],"data":null}"""),
        )
        for (refusal in refusals) {
            AnilistFixture(cacheDir).use { local ->
                runBlocking {
                    local.signedIn()
                    local.server.enqueue(refusal)

                    shouldThrow<TrackerAuthExpiredException> { local.tracker.search("fixture") }

                    local.server.requestCount shouldBe 1
                    local.tracker.isAuthExpired shouldBe true
                    local.tracker.isLoggedIn shouldBe true
                    shouldThrow<TrackerAuthExpiredException> { local.tracker.search("fixture") }
                    local.server.requestCount shouldBe 1
                }
            }
        }
    }

    @Test
    fun `any other 400 is the call's own failure and leaves the sign-in alone`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(400, """{"errors":[{"message":"Validation error.","status":400}],"data":null}"""))
            fixture.server.enqueue(json(400, "<html>not json</html>"))

            shouldThrow<HttpException> { fixture.tracker.search("fixture") }.code shouldBe 400
            shouldThrow<HttpException> { fixture.tracker.search("fixture") }.code shouldBe 400

            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `signed out, a call throws a plain IOException that names no token, and sends nothing`() {
        runBlocking {
            val failed = shouldThrow<IOException> { fixture.tracker.search("fixture") }

            failed.message.orEmpty() shouldContain "not authenticated"
            fixture.server.requestCount shouldBe 0
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `a call refused while a new sign-in stored another token is sent once more with it`() {
        runBlocking {
            fixture.signedIn(access = ACCESS_1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.headers["Authorization"] == "Bearer $ACCESS_1") {
                        fixture.signedIn(access = ACCESS_2)
                        json(401, """{"errors":[{"message":"Unauthorized.","status":401}]}""")
                    } else {
                        json(200, searchBody())
                    }
            }

            fixture.tracker.search("fixture").shouldBeEmpty()

            fixture.server.requestCount shouldBe 2
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_2"
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    /**
     * The grant as each upstream version stores it (v0.20.4 with `token_type` and `expires_in`, v0.21.0
     * without), restored from a backup with private settings, and this tracker's own form.
     */
    @Test
    fun `a grant restored from an upstream backup reads as upstream wrote it`() {
        val expires = NOW_MILLIS + ONE_YEAR_MILLIS / 2
        val stored = listOf(
            """{"access_token":"restored-access","token_type":"Bearer","expires":$expires,"expires_in":31536000000}""",
            """{"access_token":"restored-access","expires":$expires}""",
        )
        for (grant in stored) {
            AnilistFixture(cacheDir, values = mutableMapOf("__PRIVATE_track_token_2" to grant)).use { local ->
                local.storedToken() shouldBe ALOAuth("restored-access", expires)
            }
        }
        AnilistFixture.JSON.encodeToString(ALOAuth("a", 5L)) shouldBe """{"access_token":"a","expires":5}"""
        ALOAuth("secret-token", 5L).toString() shouldNotContain "secret-token"
    }

    @Test
    fun `the retry worker keeps a push on an expired sign-in, notices it once, and sends it after the next sign-in`() {
        runBlocking {
            fixture.signedIn(expires = NOW_MILLIS - 1)
            fixture.repository.insert(track(lastChapterRead = 4.0))
            val store = store()
            store.add(MANGA_ID, TrackerIds.ANILIST, 1001L, 5.0)
            val worker = worker(store)

            worker.run() shouldBe true
            worker.run() shouldBe true

            store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(MANGA_ID, TrackerIds.ANILIST, 1001L, 5.0))
            fixture.tracker.isAuthExpired shouldBe true
            notifier.expired shouldBe listOf(TrackerIds.ANILIST)
            fixture.server.requestCount shouldBe 0

            // A new sign-in clears the expiry; the kept push goes out on the next run.
            fixture.signedIn(access = ACCESS_2)
            fixture.server.enqueue(json(200, listBody(listEntry(progress = 4))))
            fixture.server.enqueue(json(200, savedBody()))

            worker.run() shouldBe true

            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_2"
            fixture.server.takeRequest().graphql().second["progress"] shouldBe JsonPrimitive(5)
            store.getItems().shouldBeEmpty()
            fixture.repository.rows.getValue(10L).lastChapterRead shouldBe 5.0
        }
    }

    @Test
    fun `the retry worker drops a push on an entry AniList no longer has`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(lastChapterRead = 4.0))
            val store = store()
            store.add(MANGA_ID, TrackerIds.ANILIST, 1001L, 5.0)
            fixture.server.enqueue(json(404, """{"errors":[{"message":"Not Found.","status":404}],"data":{"Page":null}}"""))

            worker(store).run() shouldBe true
            worker(store).run() shouldBe true

            store.getItems().shouldBeEmpty()
            fixture.server.requestCount shouldBe 1
            fixture.repository.rows.getValue(10L).lastChapterRead shouldBe 4.0
        }
    }

    @Test
    fun `refresh returns an entry on the owner's list as the list has it, and sends nothing more`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, listBody(listEntry(status = "CURRENT", progress = 7, scoreRaw = 60))))
            fixture.server.enqueue(json(200, savedBody(status = "DROPPED")))
            val row = track(lastChapterRead = 3.0, status = Anilist.PLAN_TO_READ)

            val refreshed = fixture.tracker.refresh(row)

            fixture.server.requestCount shouldBe 1
            refreshed.status shouldBe Anilist.READING
            refreshed.lastChapterRead shouldBe 7.0
            refreshed.score shouldBe 60.0
            refreshed.totalChapters shouldBe 40L
        }
    }

    @Test
    fun `refresh adds an entry the owner's list lacks as the row has it`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, listBody()))
            fixture.server.enqueue(json(200, savedBody(libraryId = 5044L, status = "PLANNING")))
            fixture.server.enqueue(json(200, savedBody(status = "DROPPED")))
            val row = track(libraryId = null, lastChapterRead = 0.0, status = Anilist.PLAN_TO_READ)

            val refreshed = fixture.tracker.refresh(row)

            fixture.server.requestCount shouldBe 2
            fixture.server.takeRequest().graphql().first shouldContain "mediaList("
            val (query, variables) = fixture.server.takeRequest().graphql()
            query shouldContain "SaveMediaListEntry (mediaId: \$mangaId"
            variables["status"] shouldBe JsonPrimitive("PLANNING")
            variables["progress"] shouldBe JsonPrimitive(0)
            refreshed shouldBe row.copy(libraryId = 5044L)
        }
    }

    @Test
    fun `the retry worker sends a push on an entry the owner's list has as one update from the list's state`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(lastChapterRead = 4.0))
            val store = store()
            store.add(MANGA_ID, TrackerIds.ANILIST, 1001L, 5.0)
            fixture.server.enqueue(json(200, listBody(listEntry(progress = 4, scoreRaw = 80))))
            fixture.server.enqueue(json(200, savedBody()))
            fixture.server.enqueue(json(200, savedBody(status = "DROPPED")))

            worker(store).run() shouldBe true

            store.getItems().shouldBeEmpty()
            fixture.server.requestCount shouldBe 2
            fixture.server.takeRequest()
            val variables = fixture.server.takeRequest().graphql().second
            variables["listId"] shouldBe JsonPrimitive(5001)
            variables["progress"] shouldBe JsonPrimitive(5)
            variables["status"] shouldBe JsonPrimitive("CURRENT")
            variables["score"] shouldBe JsonPrimitive(80)
            val stored = fixture.repository.rows.getValue(10L)
            stored.lastChapterRead shouldBe 5.0
            stored.totalChapters shouldBe 40L
            stored.score shouldBe 80.0
        }
    }

    @Test
    fun `the retry worker adds an entry the owner's list lacks, then sends the push`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(libraryId = null, lastChapterRead = 0.0, status = Anilist.PLAN_TO_READ))
            val store = store()
            store.add(MANGA_ID, TrackerIds.ANILIST, 1001L, 3.0)
            fixture.server.enqueue(json(200, listBody()))
            fixture.server.enqueue(json(200, savedBody(libraryId = 5055L, status = "PLANNING")))
            fixture.server.enqueue(json(200, savedBody(libraryId = 5055L)))
            fixture.server.enqueue(json(200, savedBody(status = "DROPPED")))

            worker(store).run() shouldBe true

            store.getItems().shouldBeEmpty()
            fixture.server.requestCount shouldBe 3
            fixture.server.takeRequest()
            fixture.server.takeRequest().graphql().second["status"] shouldBe JsonPrimitive("PLANNING")
            val push = fixture.server.takeRequest().graphql().second
            push["listId"] shouldBe JsonPrimitive(5055)
            push["status"] shouldBe JsonPrimitive("CURRENT")
            push["progress"] shouldBe JsonPrimitive(3)
            val stored = fixture.repository.rows.getValue(10L)
            stored.lastChapterRead shouldBe 3.0
            stored.libraryId shouldBe 5055L
            stored.status shouldBe Anilist.READING
        }
    }

    @Test
    fun `the track sheet's refresh on open adds an entry the owner's list lacks, and stores the answer`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(libraryId = null, status = Anilist.PLAN_TO_READ))
            fixture.server.enqueue(json(200, listBody()))
            fixture.server.enqueue(json(200, savedBody(libraryId = 5066L, status = "PLANNING")))

            val failures = RefreshTracks(fixture.repository, TrackerManager(setOf(fixture.tracker)), store(), DelayedTrackingScheduler {})
                .await(MANGA_ID)

            failures.shouldBeEmpty()
            fixture.server.requestCount shouldBe 2
            fixture.repository.rows.getValue(10L).libraryId shouldBe 5066L
        }
    }

    @Test
    fun `the track sheet's remove with the box ticked deletes the entry from the owner's list`() {
        runBlocking {
            fixture.signedIn()
            val row = track()
            fixture.repository.insert(row)
            fixture.server.enqueue(json(200, DELETED_BODY))

            RemoveTrack(fixture.repository, store()).await(fixture.tracker, row, alsoRemote = true)

            fixture.server.requestCount shouldBe 1
            fixture.server.takeRequest().graphql().first shouldContain "DeleteMediaListEntry"
            fixture.repository.rows.keys.shouldBeEmpty()
        }
    }
}
