package com.shinsaikou.tracking.myanimelist

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
import com.shinsaikou.tracking.myanimelist.dto.MALOAuth
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ONE_MONTH
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.REFRESH_1
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.REFRESH_2
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.form
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.json
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.statusBody
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.tokenBody
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.track
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * MyAnimeList's token refresh (phase 3 unit T2): upstream's single-flight refresh in its interceptor,
 * with a refresh the token endpoint refuses (400 `invalid_grant`, which is what MyAnimeList sends,
 * or 401) ending in "sign in again" (Q13, R95 item 15) and never retried, while a refresh that never
 * reached the endpoint, or a server error, stays retryable. The retry worker's pass over the
 * tracker is run for each outcome, so what the worker does with it is checked end to end.
 *
 * [MyAnimeList.refresh] itself, the call the worker makes before every push (review 1, blocking 1):
 * an entry on the owner's list comes back as the list has it and nothing more is sent; an entry off
 * the list is added as the row has it (upstream's `findListItem(track) ?: add(track)`). Both are run
 * alone and through the worker.
 */
class MyAnimeListRefreshTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { MyAnimeListFixture(cacheDir) }
    private val notifier = FakeSignInNotifier()

    @AfterEach
    fun close() {
        fixture.close()
    }

    /** A token stored an hour past its lifetime, as the clock reads it. */
    private fun expiredSignIn() {
        fixture.signedIn(access = ACCESS_1, refresh = REFRESH_1, createdAt = NOW_SECONDS - ONE_MONTH - 3600)
    }

    private val searchBody = """{"data":[],"paging":{}}"""

    /** `GET manga/{id}` with the list fields: the entry's chapter count, and its list status if it has one. */
    private fun entryBody(listStatus: String?): String =
        """{"id":1001,"title":"Fixture Title One","num_chapters":40""" +
            (listStatus?.let { ""","my_list_status":$it""" } ?: "") + "}"

    /** What a stray call would get: a list status nothing in these tests expects. */
    private val strayBody = statusBody(status = "dropped", chapters = 99)

    private fun date(text: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(text)!!.time

    /**
     * The five keys a backup with private settings restores, in their stored form, with the grant as
     * a literal string rather than one this tracker serialized: what `BackupImporterTrackerRestoreTest`
     * in `:backup` restores (R96 carry-over 4).
     */
    private fun restoredSignIn(createdAt: Long): MutableMap<String, Any> = mutableMapOf(
        "__PRIVATE_track_token_1" to
            """{"token_type":"Bearer","refresh_token":"fixture-refresh","access_token":"fixture-access",""" +
            """"expires_in":$ONE_MONTH,"created_at":$createdAt}""",
        "__PRIVATE_pref_mangasync_username_1" to "fixture-reader",
        "__PRIVATE_pref_mangasync_displayname_1" to "Fixture Reader",
        "__PRIVATE_pref_mangasync_password_1" to "fixture-access",
        "__PRIVATE_pref_tracker_auth_expired_1" to false,
    )

    @Test
    fun `an expired token is refreshed with upstream's grant before the call, and the new one is stored and sent`() {
        runBlocking {
            expiredSignIn()
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
            fixture.server.enqueue(json(200, searchBody))

            fixture.tracker.search("fixture").shouldBeEmpty()

            val refresh = fixture.server.takeRequest()
            refresh.method shouldBe "POST"
            refresh.url.encodedPath shouldBe "/v1/oauth2/token"
            refresh.form() shouldBe mapOf(
                "client_id" to MyAnimeListClient.TEST_BASE_CLIENT_ID,
                "refresh_token" to REFRESH_1,
                "grant_type" to "refresh_token",
            )
            refresh.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            val search = fixture.server.takeRequest()
            search.headers["Authorization"] shouldBe "Bearer $ACCESS_2"

            val stored = checkNotNull(fixture.storedToken())
            stored.accessToken shouldBe ACCESS_2
            stored.refreshToken shouldBe REFRESH_2
            stored.createdAt shouldBe NOW_SECONDS
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `a fresh token is sent as it is, with no refresh`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, searchBody))

            fixture.tracker.search("fixture")

            fixture.server.requestCount shouldBe 1
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_1"
        }
    }

    @Test
    fun `a grant restored from a backup is read as the backup wrote it, and a fresh one is sent with no refresh`() {
        MyAnimeListFixture(cacheDir, values = restoredSignIn(createdAt = NOW_SECONDS)).use { restored ->
            runBlocking {
                restored.storedToken() shouldBe MALOAuth("Bearer", "fixture-refresh", "fixture-access", ONE_MONTH, NOW_SECONDS)
                restored.tracker.isLoggedIn shouldBe true
                restored.server.enqueue(json(200, searchBody))

                restored.tracker.search("fixture")

                restored.server.requestCount shouldBe 1
                val search = restored.server.takeRequest()
                search.method shouldBe "GET"
                search.headers["Authorization"] shouldBe "Bearer fixture-access"
            }
        }
    }

    @Test
    fun `an expired grant restored from a backup is refreshed with the refresh token the backup carried`() {
        MyAnimeListFixture(cacheDir, values = restoredSignIn(createdAt = NOW_SECONDS - ONE_MONTH - 3600)).use { restored ->
            runBlocking {
                restored.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
                restored.server.enqueue(json(200, searchBody))

                restored.tracker.search("fixture")

                val refresh = restored.server.takeRequest()
                refresh.method shouldBe "POST"
                refresh.url.encodedPath shouldBe "/v1/oauth2/token"
                refresh.form()["refresh_token"] shouldBe "fixture-refresh"
                restored.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_2"
            }
        }
    }

    @Test
    fun `a refresh refused with 400 invalid_grant or with 401 sets auth expired, throws its key, and is never tried again`() {
        for (refusal in listOf(json(400, """{"error":"invalid_grant","message":"Invalid refresh token"}"""), json(401, "{}"))) {
            MyAnimeListFixture(cacheDir).use { local ->
                runBlocking {
                    local.signedIn(access = ACCESS_1, refresh = REFRESH_1, createdAt = NOW_SECONDS - ONE_MONTH - 3600)
                    local.server.enqueue(refusal)

                    val expired = shouldThrow<TrackerAuthExpiredException> { local.tracker.search("fixture") }

                    ErrorKey.of(expired) shouldBe ErrorKey.TRACKER_AUTH_EXPIRED
                    local.tracker.isAuthExpired shouldBe true
                    // The refresh alone went out; the search was never sent.
                    local.server.requestCount shouldBe 1
                    local.server.takeRequest().url.encodedPath shouldBe "/v1/oauth2/token"
                    // The credentials stay, so settings shows "Sign in again" rather than signed out.
                    local.tracker.isLoggedIn shouldBe true

                    // Nothing more reaches the service until the owner signs in again.
                    shouldThrow<TrackerAuthExpiredException> { local.tracker.search("fixture") }
                    local.server.requestCount shouldBe 1
                }
            }
        }
    }

    @Test
    fun `a refresh that never reaches the endpoint, or a server error, stays retryable and leaves the sign-in alone`() {
        val failures = listOf(
            MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build(),
            json(503, """{"error":"temporarily_unavailable"}"""),
        )
        for (failure in failures) {
            MyAnimeListFixture(cacheDir).use { local ->
                runBlocking {
                    local.signedIn(access = ACCESS_1, refresh = REFRESH_1, createdAt = NOW_SECONDS - ONE_MONTH - 3600)
                    // Three, since OkHttp may retry a dropped connection once on its own.
                    repeat(3) { local.server.enqueue(failure) }

                    val thrown = shouldThrow<IOException> { local.tracker.search("fixture") }

                    thrown.shouldBeInstanceOf<MALTokenRefreshFailed>()
                    local.tracker.isAuthExpired shouldBe false
                    checkNotNull(local.storedToken()).refreshToken shouldBe REFRESH_1
                }
            }
        }
    }

    @Test
    fun `two calls on an expired token refresh it once, and both send the new token`() {
        runBlocking {
            expiredSignIn()
            val refreshes = AtomicInteger()
            val bearers = Collections.synchronizedList(mutableListOf<String?>())
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.url.encodedPath == "/v1/oauth2/token") {
                        refreshes.incrementAndGet()
                        // Long enough for the second call to reach the lock while this one holds it.
                        Thread.sleep(300)
                        json(200, tokenBody(ACCESS_2, REFRESH_2))
                    } else {
                        bearers += request.headers["Authorization"]
                        json(200, searchBody)
                    }
            }

            listOf(
                async(Dispatchers.IO) { fixture.tracker.search("one") },
                async(Dispatchers.IO) { fixture.tracker.search("two") },
            ).awaitAll()

            refreshes.get() shouldBe 1
            bearers shouldContainExactly listOf("Bearer $ACCESS_2", "Bearer $ACCESS_2")
        }
    }

    @Test
    fun `the retry worker keeps a push whose refresh was refused, notices it once, and sends it after the next sign-in`() {
        runBlocking {
            expiredSignIn()
            fixture.repository.insert(track(id = 10L, lastChapterRead = 4.0))
            val store = DelayedTrackingStore(fixture.preferences, fixture.preferences::write)
            store.add(MANGA_ID, TrackerIds.MYANIMELIST, 1001L, 5.0)
            fixture.server.enqueue(json(400, """{"error":"invalid_grant"}"""))
            val worker = DelayedTrackingUpdate(store, fixture.repository, TrackerManager(setOf(fixture.tracker)), notifier)

            worker.run() shouldBe true
            worker.run() shouldBe true

            store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(MANGA_ID, TrackerIds.MYANIMELIST, 1001L, 5.0))
            fixture.tracker.isAuthExpired shouldBe true
            notifier.expired shouldBe listOf(TrackerIds.MYANIMELIST)
            fixture.server.requestCount shouldBe 1
            fixture.repository.rows.getValue(10L).lastChapterRead shouldBe 4.0

            // A new sign-in clears the expiry; the kept push goes out on the next run.
            fixture.signedIn(access = ACCESS_2, refresh = REFRESH_2)
            fixture.server.takeRequest()
            fixture.server.enqueue(json(200, entryBody(statusBody(status = "reading", chapters = 4))))
            fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 5)))

            worker.run() shouldBe true

            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_2"
            fixture.server.takeRequest().form()["num_chapters_read"] shouldBe "5"
            store.getItems().shouldBeEmpty()
            fixture.repository.rows.getValue(10L).lastChapterRead shouldBe 5.0
        }
    }

    /**
     * MyAnimeList's two answers no retry changes, end to end: the entry gone from its database (404
     * on the refresh's read), and a title waiting for approval refused as it is added back (400
     * `invalid_content`). Each push is dropped, and nothing is sent again on the next run.
     */
    @Test
    fun `the retry worker drops a push on an entry MyAnimeList no longer has or will not list yet`() {
        val refusals = listOf(
            listOf(json(404, """{"message":"","error":"not_found"}""")),
            listOf(
                json(200, entryBody(listStatus = null)),
                json(400, """{"message":"Invalid content","error":"invalid_content"}"""),
            ),
        )
        for (answers in refusals) {
            MyAnimeListFixture(cacheDir).use { local ->
                runBlocking {
                    local.signedIn()
                    local.repository.insert(track(id = 10L, lastChapterRead = 4.0))
                    val store = DelayedTrackingStore(local.preferences, local.preferences::write)
                    store.add(MANGA_ID, TrackerIds.MYANIMELIST, 1001L, 5.0)
                    answers.forEach(local.server::enqueue)
                    val worker = DelayedTrackingUpdate(store, local.repository, TrackerManager(setOf(local.tracker)), notifier)

                    worker.run() shouldBe true
                    worker.run() shouldBe true

                    store.getItems().shouldBeEmpty()
                    local.server.requestCount shouldBe answers.size
                    local.repository.rows.getValue(10L).lastChapterRead shouldBe 4.0
                }
            }
        }
    }

    /**
     * A token the clock still counts as live, refused by the API (revoked early, or a clock that is
     * off): one forced refresh with upstream's grant, then the call once more with the new token.
     */
    @Test
    fun `a 401 from the API on a live token forces one refresh, and the call is sent again with the new token`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(401, """{"error":"invalid_token"}"""))
            fixture.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
            fixture.server.enqueue(json(200, searchBody))

            fixture.tracker.search("fixture").shouldBeEmpty()

            fixture.server.requestCount shouldBe 3
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            val refresh = fixture.server.takeRequest()
            refresh.url.encodedPath shouldBe "/v1/oauth2/token"
            refresh.form()["refresh_token"] shouldBe REFRESH_1
            fixture.server.takeRequest().headers["Authorization"] shouldBe "Bearer $ACCESS_2"
            checkNotNull(fixture.storedToken()).accessToken shouldBe ACCESS_2
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `a 401 whose forced refresh is refused sets auth expired, and a second 401 is the call's own failure`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(401, """{"error":"invalid_token"}"""))
            fixture.server.enqueue(json(400, """{"error":"invalid_grant"}"""))

            shouldThrow<TrackerAuthExpiredException> { fixture.tracker.search("fixture") }

            fixture.tracker.isAuthExpired shouldBe true
            fixture.server.requestCount shouldBe 2
        }
        MyAnimeListFixture(cacheDir).use { local ->
            runBlocking {
                local.signedIn()
                local.server.enqueue(json(401, """{"error":"invalid_token"}"""))
                local.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2)))
                local.server.enqueue(json(401, """{"error":"invalid_token"}"""))
                local.server.enqueue(json(200, strayBody))

                shouldThrow<HttpException> { local.tracker.search("fixture") }.code shouldBe 401

                local.server.requestCount shouldBe 3
                local.tracker.isAuthExpired shouldBe false
            }
        }
    }

    @Test
    fun `two calls refused with 401 on the same token refresh it once, and both are sent again with the new token`() {
        runBlocking {
            fixture.signedIn()
            val refreshes = AtomicInteger()
            val bearers = Collections.synchronizedList(mutableListOf<String?>())
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/v1/oauth2/token" -> {
                        refreshes.incrementAndGet()
                        Thread.sleep(300)
                        json(200, tokenBody(ACCESS_2, REFRESH_2))
                    }
                    request.headers["Authorization"] == "Bearer $ACCESS_1" -> json(401, """{"error":"invalid_token"}""")
                    else -> {
                        bearers += request.headers["Authorization"]
                        json(200, searchBody)
                    }
                }
            }

            listOf(
                async(Dispatchers.IO) { fixture.tracker.search("one") },
                async(Dispatchers.IO) { fixture.tracker.search("two") },
            ).awaitAll()

            refreshes.get() shouldBe 1
            bearers shouldContainExactly listOf("Bearer $ACCESS_2", "Bearer $ACCESS_2")
        }
    }

    @Test
    fun `the retry worker keeps a push whose refresh could not reach the endpoint`() {
        runBlocking {
            expiredSignIn()
            fixture.repository.insert(track(id = 10L, lastChapterRead = 4.0))
            val store = DelayedTrackingStore(fixture.preferences, fixture.preferences::write)
            store.add(MANGA_ID, TrackerIds.MYANIMELIST, 1001L, 5.0)
            repeat(3) { fixture.server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()) }

            val settled = DelayedTrackingUpdate(store, fixture.repository, TrackerManager(setOf(fixture.tracker)), notifier).run()

            settled shouldBe false
            store.getItems() shouldBe listOf(DelayedTrackingStore.DelayedTrackingItem(MANGA_ID, TrackerIds.MYANIMELIST, 1001L, 5.0))
            fixture.tracker.isAuthExpired shouldBe false
        }
    }

    @Test
    fun `refresh returns an entry on the owner's list as the list has it, with its chapter count, and sends nothing more`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, entryBody(statusBody(status = "reading", chapters = 7, score = 6, start = "2026-01-02"))))
            fixture.server.enqueue(json(200, strayBody))
            val row = track(id = 10L, lastChapterRead = 3.0, status = MyAnimeList.PLAN_TO_READ)

            val refreshed = fixture.tracker.refresh(row)

            fixture.server.requestCount shouldBe 1
            val lookup = fixture.server.takeRequest()
            lookup.method shouldBe "GET"
            lookup.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001")
            lookup.url.queryParameter("fields") shouldBe "num_chapters,my_list_status{start_date,finish_date}"
            refreshed shouldBe row.copy(
                status = MyAnimeList.READING,
                lastChapterRead = 7.0,
                score = 6.0,
                totalChapters = 40L,
                startDate = date("2026-01-02"),
            )
        }
    }

    @Test
    fun `refresh adds an entry the owner's list lacks as the row has it, with the entry's chapter count`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, entryBody(listStatus = null)))
            fixture.server.enqueue(json(200, statusBody(status = "plan_to_read", chapters = 0)))
            fixture.server.enqueue(json(200, strayBody))
            val row = track(id = 10L, lastChapterRead = 0.0, status = MyAnimeList.PLAN_TO_READ)

            val refreshed = fixture.tracker.refresh(row)

            fixture.server.requestCount shouldBe 2
            fixture.server.takeRequest().url.pathSegments shouldContainExactly listOf("v2", "manga", "1001")
            val put = fixture.server.takeRequest()
            put.method shouldBe "PUT"
            put.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001", "my_list_status")
            put.form() shouldBe mapOf(
                "status" to "plan_to_read",
                "is_rereading" to "false",
                "score" to "0.0",
                "num_chapters_read" to "0",
                "start_date" to "",
                "finish_date" to "",
            )
            refreshed shouldBe row.copy(totalChapters = 40L)
        }
    }

    @Test
    fun `the retry worker sends a push on an entry the owner's list has as one update from the list's state`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(id = 10L, lastChapterRead = 4.0))
            val store = DelayedTrackingStore(fixture.preferences, fixture.preferences::write)
            store.add(MANGA_ID, TrackerIds.MYANIMELIST, 1001L, 5.0)
            fixture.server.enqueue(json(200, entryBody(statusBody(status = "reading", chapters = 4, score = 8, start = "2026-01-02"))))
            fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 5, score = 8, start = "2026-01-02")))
            fixture.server.enqueue(json(200, strayBody))

            val settled = DelayedTrackingUpdate(store, fixture.repository, TrackerManager(setOf(fixture.tracker)), notifier).run()

            settled shouldBe true
            store.getItems().shouldBeEmpty()
            fixture.server.requestCount shouldBe 2
            fixture.server.takeRequest().method shouldBe "GET"
            fixture.server.takeRequest().form() shouldBe mapOf(
                "status" to "reading",
                "is_rereading" to "false",
                "score" to "8.0",
                "num_chapters_read" to "5",
                "start_date" to "2026-01-02",
                "finish_date" to "",
            )
            val stored = fixture.repository.rows.getValue(10L)
            stored.lastChapterRead shouldBe 5.0
            stored.totalChapters shouldBe 40L
            stored.score shouldBe 8.0
            stored.status shouldBe MyAnimeList.READING
        }
    }

    @Test
    fun `the retry worker adds an entry the owner's list lacks, then sends the push, as on the device`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(id = 10L, lastChapterRead = 0.0, status = MyAnimeList.PLAN_TO_READ))
            val store = DelayedTrackingStore(fixture.preferences, fixture.preferences::write)
            store.add(MANGA_ID, TrackerIds.MYANIMELIST, 1001L, 3.0)
            fixture.server.enqueue(json(200, entryBody(listStatus = null)))
            fixture.server.enqueue(json(200, statusBody(status = "plan_to_read", chapters = 0)))
            fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 3)))
            fixture.server.enqueue(json(200, strayBody))

            val settled = DelayedTrackingUpdate(store, fixture.repository, TrackerManager(setOf(fixture.tracker)), notifier).run()

            settled shouldBe true
            store.getItems().shouldBeEmpty()
            fixture.server.requestCount shouldBe 3
            fixture.server.takeRequest().method shouldBe "GET"
            val add = fixture.server.takeRequest().form()
            add["status"] shouldBe "plan_to_read"
            add["num_chapters_read"] shouldBe "0"
            val push = fixture.server.takeRequest().form()
            push["status"] shouldBe "reading"
            push["num_chapters_read"] shouldBe "3"
            val stored = fixture.repository.rows.getValue(10L)
            stored.lastChapterRead shouldBe 3.0
            stored.totalChapters shouldBe 40L
            stored.status shouldBe MyAnimeList.READING
        }
    }

    @Test
    fun `the track sheet's refresh on open adds an entry the owner's list lacks with a PUT, and stores the answer`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(id = 10L, lastChapterRead = 0.0, status = MyAnimeList.PLAN_TO_READ))
            fixture.server.enqueue(json(200, entryBody(listStatus = null)))
            fixture.server.enqueue(json(200, statusBody(status = "plan_to_read", chapters = 0)))
            fixture.server.enqueue(json(200, strayBody))

            val store = DelayedTrackingStore(fixture.preferences, fixture.preferences::write)
            val failures = RefreshTracks(fixture.repository, TrackerManager(setOf(fixture.tracker)), store, DelayedTrackingScheduler {})
                .await(MANGA_ID)

            failures.shouldBeEmpty()
            fixture.server.requestCount shouldBe 2
            fixture.server.takeRequest().method shouldBe "GET"
            val put = fixture.server.takeRequest()
            put.method shouldBe "PUT"
            put.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001", "my_list_status")
            put.form()["status"] shouldBe "plan_to_read"
            fixture.repository.rows.getValue(10L).totalChapters shouldBe 40L
        }
    }

    @Test
    fun `the track sheet's remove with the box ticked deletes the entry from the owner's list`() {
        runBlocking {
            fixture.signedIn()
            val row = track(id = 10L)
            fixture.repository.insert(row)
            val preferences = fixture.preferences
            fixture.server.enqueue(MockResponse.Builder().code(200).build())
            fixture.server.enqueue(json(200, strayBody))

            RemoveTrack(fixture.repository, DelayedTrackingStore(preferences, preferences::write))
                .await(fixture.tracker, row, alsoRemote = true)

            fixture.server.requestCount shouldBe 1
            val delete = fixture.server.takeRequest()
            delete.method shouldBe "DELETE"
            delete.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001", "my_list_status")
            fixture.repository.rows.keys.shouldBeEmpty()
        }
    }
}
