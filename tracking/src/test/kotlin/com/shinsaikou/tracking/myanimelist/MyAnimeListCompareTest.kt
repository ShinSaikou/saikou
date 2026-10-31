package com.shinsaikou.tracking.myanimelist

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.tracking.CompareLink
import com.shinsaikou.tracking.CompareSection
import com.shinsaikou.tracking.CompareTrackProgress
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.EntryRead
import com.shinsaikou.tracking.FakeLibrary
import com.shinsaikou.tracking.ListEntry
import com.shinsaikou.tracking.ListRead
import com.shinsaikou.tracking.RemoteAnswer
import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ONE_MONTH
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.json
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.statusBody
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.track
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * What "Compare with MyAnimeList" reads from MyAnimeList (phase 4 unit TC, the design's tests 1 to
 * 10), against the fixture: the owner's list read page by page with its progress, following the
 * offset `paging.next` names without requesting it, and read as unusable (for the one-at-a-time
 * fallback) when an answer cannot be trusted; one entry read with the service's dates and no write;
 * and a token endpoint's refusal or throttling seen by the error keys. An unusable list is then read
 * one entry at a time by [CompareTrackProgress].
 */
class MyAnimeListCompareTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { MyAnimeListFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    /** One entry of a list page: its id and, unless null, a list status at [chapters]. */
    private fun node(id: Long, chapters: Int? = 0, numChapters: Long = 0, rereading: Boolean = false): String =
        """{"node":{"id":$id,"title":"Fixture Title $id","num_chapters":$numChapters}""" +
            (chapters?.let { ""","list_status":${statusBody(status = "reading", chapters = it, rereading = rereading)}""" } ?: "") +
            "}"

    /** A list page of [nodes], with `paging.next` when [next] is not null. */
    private fun page(nodes: List<String>, next: String? = null): String =
        """{"data":[${nodes.joinToString(",")}],"paging":{${next?.let { """"next":"$it"""" } ?: ""}}}"""

    /** A `paging.next` on a host the tests never serve: a request to it would fail the test. */
    private fun nextAt(offset: Int): String =
        "https://elsewhere.invalid/v2/users/@me/mangalist?offset=$offset&limit=250&fields=list_status"

    /** `GET manga/{id}` with the list fields: the entry's chapter count, and its list status if it has one. */
    private fun entryBody(id: Long, numChapters: Long, listStatus: String?): String =
        """{"id":$id,"title":"Fixture Title $id","num_chapters":$numChapters""" +
            (listStatus?.let { ""","my_list_status":$it""" } ?: "") + "}"

    private fun RecordedRequest.isListRead() = url.encodedPath == "/v2/users/@me/mangalist"

    private val library = FakeLibrary()

    private val compare by lazy {
        CompareTrackProgress(
            library.getFavorites,
            fixture.repository,
            library.chapterRepository,
            library.historyRepository,
            DelayedTrackingStore(fixture.preferences, fixture.preferences::write),
        )
    }

    /** Library entries 1 and 2, chapters 1 to 10 each with 1 to 5 read, linked to 1001 and 1002. */
    private fun twoLinks(): List<CompareLink> = listOf(1L, 2L).map { mangaId ->
        library.addChapters(mangaId, 1..10, readThrough = 5.0)
        CompareLink(library.addManga(mangaId), track(id = mangaId, remoteId = 1000 + mangaId).copy(mangaId = mangaId))
    }

    /** Answers the list with [list] and each entry alone as on the list at 9 chapters, recording the paths. */
    private fun serveEntries(paths: MutableList<String>, list: () -> MockResponse) {
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.url.encodedPath}"
                return if (request.isListRead()) {
                    list()
                } else {
                    val id = request.url.pathSegments.last().toLong()
                    json(200, entryBody(id, numChapters = 0, listStatus = statusBody(status = "reading", chapters = 9)))
                }
            }
        }
    }

    @Test
    fun `the list is read page by page with its progress, following the offset paging names, and keeps the linked entries`() {
        runBlocking {
            fixture.signedIn()
            val first = (1L..250L).map { id -> node(id, chapters = (id % 7).toInt(), numChapters = 40) }
            val second = (251L..253L).map { id -> node(id, chapters = 3, numChapters = if (id == 252L) 0 else 12, rereading = id == 252L) }
            fixture.server.enqueue(json(200, page(first, next = nextAt(250))))
            fixture.server.enqueue(json(200, page(second)))

            val read = fixture.tracker.readList(setOf(7L, 252L, 9999L))

            read shouldBe ListRead.Entries(
                mapOf(
                    7L to ListEntry(remoteId = 7L, chaptersRead = 0, totalChapters = 40, status = MyAnimeList.READING),
                    252L to ListEntry(remoteId = 252L, chaptersRead = 3, totalChapters = 0, status = MyAnimeList.REREADING),
                ),
            )
            fixture.server.requestCount shouldBe 2
            val one = fixture.server.takeRequest()
            one.method shouldBe "GET"
            one.isListRead() shouldBe true
            one.url.queryParameter("fields")!!.split(',').toSet() shouldBe setOf("num_chapters", "list_status")
            one.url.queryParameter("limit") shouldBe "250"
            one.url.queryParameter("nsfw") shouldBe "true"
            one.url.queryParameter("status").shouldBeNull()
            one.url.queryParameter("offset").shouldBeNull()
            val two = fixture.server.takeRequest()
            two.isListRead() shouldBe true
            two.url.host shouldBe one.url.host
            two.url.queryParameter("offset") shouldBe "250"
            two.url.queryParameter("nsfw") shouldBe "true"
        }
    }

    @Test
    fun `a page capped below the limit is followed from the offset paging names, not the limit`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, page((1L..100L).map { node(it) }, next = nextAt(100))))
            fixture.server.enqueue(json(200, page(listOf(node(101L, chapters = 5)))))

            val read = fixture.tracker.readList(setOf(101L))

            read shouldBe ListRead.Entries(mapOf(101L to ListEntry(101L, 5, 0, MyAnimeList.READING)))
            fixture.server.takeRequest()
            fixture.server.takeRequest().url.queryParameter("offset") shouldBe "100"
        }
    }

    @Test
    fun `an entry without its list status makes the list unreadable, never 0 chapters, and each linked entry is read alone`() {
        runBlocking {
            fixture.signedIn()
            val paths = Collections.synchronizedList(mutableListOf<String>())
            serveEntries(paths) { json(200, page(listOf(node(1001L, chapters = 4), node(1002L, chapters = null)))) }
            val links = twoLinks()

            fixture.tracker.readList(setOf(1001L, 1002L)).shouldBeInstanceOf<ListRead.Unreadable>()
            paths shouldBe listOf("GET /v2/users/@me/mangalist")

            paths.clear()
            val remote = compare.readRemote(fixture.tracker, links)
            val comparison = compare.compare(fixture.tracker, links, remote)

            paths shouldBe listOf("GET /v2/users/@me/mangalist", "GET /v2/manga/1001", "GET /v2/manga/1002")
            remote.listUnreadable shouldBe "an entry on the page at offset 0 had no list_status"
            remote.answers.values.map { (it as RemoteAnswer.Listed).entry.chaptersRead } shouldBe listOf(9, 9)
            // The groups the answers of the entries alone give: the list's 4 would read as the app ahead.
            comparison.rows.map { it.manga.id to it.section } shouldBe
                listOf(1L to CompareSection.REMOTE_AHEAD, 2L to CompareSection.REMOTE_AHEAD)
        }
    }

    @Test
    fun `a page refused with 400 is asked again without nsfw, and every later page goes without it`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(400, """{"error":"bad_request"}"""))
            fixture.server.enqueue(json(200, page(listOf(node(1001L, chapters = 4)), next = nextAt(1))))
            fixture.server.enqueue(json(200, page(listOf(node(1002L, chapters = 6)))))

            val read = fixture.tracker.readList(setOf(1001L, 1002L))

            read shouldBe ListRead.Entries(
                mapOf(1001L to ListEntry(1001L, 4, 0, MyAnimeList.READING), 1002L to ListEntry(1002L, 6, 0, MyAnimeList.READING)),
            )
            val requests = List(3) { fixture.server.takeRequest() }
            requests.map { it.url.queryParameter("nsfw") } shouldBe listOf("true", null, null)
            requests.map { it.url.queryParameter("offset") } shouldBe listOf(null, null, "1")
        }
    }

    @Test
    fun `a page refused with 400 with and without nsfw makes the list unreadable, and each linked entry is read alone`() {
        runBlocking {
            fixture.signedIn()
            val paths = Collections.synchronizedList(mutableListOf<String>())
            serveEntries(paths) { json(400, """{"error":"bad_request"}""") }
            val links = twoLinks()

            fixture.tracker.readList(setOf(1001L, 1002L)).shouldBeInstanceOf<ListRead.Unreadable>()
            paths shouldBe List(2) { "GET /v2/users/@me/mangalist" }

            paths.clear()
            val remote = compare.readRemote(fixture.tracker, links)

            paths shouldBe List(2) { "GET /v2/users/@me/mangalist" } + listOf("GET /v2/manga/1001", "GET /v2/manga/1002")
            remote.answers.keys shouldBe setOf(1L, 2L)
        }
    }

    @Test
    fun `a next offset that is not past the current one makes the list unreadable instead of looping`() {
        runBlocking {
            fixture.signedIn()
            val served = Collections.synchronizedList(mutableListOf<String?>())
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    served += request.url.queryParameter("offset")
                    // The cap: a read that follows the offset back is refused after three pages.
                    if (served.size > 3) return json(500, "{}")
                    return when (request.url.queryParameter("offset")) {
                        null -> json(200, page(listOf(node(1001L, chapters = 1)), next = nextAt(250)))
                        else -> json(200, page(listOf(node(1002L, chapters = 2)), next = nextAt(100)))
                    }
                }
            }

            val read = fixture.tracker.readList(setOf(1001L, 1002L))

            read.shouldBeInstanceOf<ListRead.Unreadable>()
            served shouldBe listOf(null, "250")
        }
    }

    @Test
    fun `readEntry writes nothing on an entry off the list`() {
        runBlocking {
            fixture.signedIn()
            val row = track(id = 10L, remoteId = 1001L, lastChapterRead = 5.0, status = MyAnimeList.READING)
            fixture.server.enqueue(json(200, entryBody(1001L, numChapters = 40, listStatus = null)))
            // What a PUT would take: nothing here may ask for it.
            fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 5)))

            val read = fixture.tracker.readEntry(row)

            read shouldBe EntryRead.OffList(row.copy(totalChapters = 40))
            fixture.server.requestCount shouldBe 1
            fixture.server.takeRequest().method shouldBe "GET"
            fixture.repository.inserted shouldBe emptyList()
        }
    }

    @Test
    fun `readEntry answers with the service's dates only, never the stored row's`() {
        runBlocking {
            fixture.signedIn()
            val row = track(id = 10L, remoteId = 1001L, lastChapterRead = 5.0, startDate = 1_700_000_000_000L, finishDate = 1_710_000_000_000L)
            fixture.server.enqueue(json(200, entryBody(1001L, numChapters = 40, listStatus = statusBody(status = "on_hold", chapters = 9, score = 9))))

            val read = fixture.tracker.readEntry(row)

            val onList = read.shouldBeInstanceOf<EntryRead.OnList>().track
            onList.startDate shouldBe 0L
            onList.finishDate shouldBe 0L
            onList.lastChapterRead shouldBe 9.0
            onList.score shouldBe 9.0
            onList.status shouldBe MyAnimeList.ON_HOLD
            onList.totalChapters shouldBe 40L
            onList.id shouldBe row.id
        }
    }

    @Test
    fun `readEntry reads a 404 as an entry MyAnimeList no longer has`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(404, """{"message":"not found","error":"not_found"}"""))

            fixture.tracker.readEntry(track(id = 10L, remoteId = 1001L)) shouldBe EntryRead.Gone
        }
    }

    @Test
    fun `a sign-in refused during the list read is thrown with no partial list`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/v1/oauth2/token" -> json(400, """{"error":"invalid_grant"}""")
                    request.url.queryParameter("offset") == null -> {
                        // The token expires while page one is answered: page two needs a refresh.
                        fixture.now = fixture.now + (ONE_MONTH + 3600).seconds
                        json(200, page(listOf(node(1001L, chapters = 4)), next = nextAt(1)))
                    }
                    else -> json(200, page(listOf(node(1002L, chapters = 6))))
                }
            }

            shouldThrow<TrackerAuthExpiredException> { fixture.tracker.readList(setOf(1001L, 1002L)) }

            fixture.tracker.isAuthExpired shouldBe true
            fixture.server.requestCount shouldBe 2
        }
    }

    @Test
    fun `a token endpoint answering 429 is a refresh failure whose status the error keys read`() {
        runBlocking {
            fixture.signedIn(createdAt = NOW_SECONDS - ONE_MONTH - 3600)
            fixture.server.enqueue(json(429, """{"error":"too_many_requests"}"""))

            val thrown = shouldThrow<MALTokenRefreshFailed> { fixture.tracker.readList(setOf(1001L)) }

            // The interceptor's failure crosses OkHttp's thread, so under the JVM tests' assertions
            // the coroutines' stack trace recovery may hand over a copy whose cause is the original.
            val failure = generateSequence<Throwable>(thrown) { it.cause }.first { it.cause !is MALTokenRefreshFailed }
            failure.shouldBeInstanceOf<MALTokenRefreshFailed>()
            failure.cause.shouldBeInstanceOf<HttpException>().code shouldBe 429
            ErrorKey.httpCode(failure) shouldBe 429
            fixture.tracker.isAuthExpired shouldBe false
        }
    }
}
